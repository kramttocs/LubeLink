package com.kramttocs.lubelink.quality

import com.kramttocs.lubelink.core.FieldReadiness
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.GasStationFieldCheck
import com.kramttocs.lubelink.core.LubeLoggerReader
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordsLoad
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.core.VehiclePreferences
import com.kramttocs.lubelink.ui.AccountDataState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Within one account, the most recently started Garage or Calendar request wins. An older
 * response that arrives last (a pull-to-refresh begun before a save's reload) must not replace it.
 * Each case is also run in the reverse completion order as a control. Synthetic data only.
 */
class AccountOrderingUnitTest {
    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", false)
    private val truck = Vehicle(VehicleId(2), 2018, "Synthetic", "Truck", "", false)
    private val g0 = GarageData(listOf(car), emptyMap(), emptyList())
    private val g1 = GarageData(listOf(car, truck), emptyMap(), emptyList())
    private fun record(description: String) = ServiceRecord(RecordId(15), car.id, "2026-10-01", description, 1200,
        "10.00", "", "", emptyList(), RecordKind.SERVICE)
    private val h0 = listOf(record("Before the save"))
    private val h1 = listOf(record("Before the save"), record("Just saved").copy(id = RecordId(16)))
    private val noPrefs = object : VehiclePreferences {
        override fun nicknameFor(vehicleId: VehicleId) = ""
        override fun isVehicleHidden(vehicleId: VehicleId) = false
    }

    /** One scripted response per call, in call order; a response with a gate waits for it. */
    private class Step<T>(val value: T, val gate: CompletableDeferred<Unit>? = null)
    private class ScriptedReader(val garage: List<Step<GarageData>>, val calendar: List<Step<List<ServiceRecord>>> = emptyList())
        : LubeLoggerReader {
        private var garageCalls = 0
        private var calendarCalls = 0
        override suspend fun loadGarage(): GarageData = garage[garageCalls++].let { it.gate?.await(); it.value }
        override suspend fun loadCalendarHistory(includeFuel: Boolean): RecordsLoad =
            calendar[calendarCalls++].let { it.gate?.await(); RecordsLoad(it.value) }
        override suspend fun checkGasStationFields() = GasStationFieldCheck(FieldReadiness.READY)
        override suspend fun loadHistory(vehicleId: VehicleId, includeFuel: Boolean) = RecordsLoad(emptyList())
    }

    @Test fun anOlderGarageRefreshArrivingLastDoesNotReplaceTheReloadAfterASave() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val account = AccountDataState(ScriptedReader(listOf(Step(g0), Step(g0, held), Step(g1))), noPrefs)
        account.loadGarage()
        val refresh = launch { runCatching { account.refreshGarage() } } // pull-to-refresh, response held
        yield()
        account.loadGarage() // a confirmed save bumps reload; the new Garage arrives first
        assertEquals(g1, account.garage)
        held.complete(Unit)  // the older refresh finally answers
        refresh.join()
        assertEquals("The older refresh must not replace the newer Garage", g1, account.garage)
    }

    @Test fun controlAnOlderGarageRefreshThatFinishesFirstIsThenReplaced() = runBlocking {
        val account = AccountDataState(ScriptedReader(listOf(Step(g0), Step(g0), Step(g1))), noPrefs)
        account.loadGarage()
        account.refreshGarage()
        account.loadGarage()
        assertEquals(g1, account.garage)
    }

    @Test fun anOlderCalendarRefreshArrivingLastDoesNotReplaceTheNewerLoad() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val account = AccountDataState(ScriptedReader(listOf(Step(g0)), listOf(Step(h0, held), Step(h1))), noPrefs)
        account.loadGarage()
        val refresh = launch { account.refreshCalendar(includeFuel = true, generation = 0) { true } }
        yield()
        account.loadCalendarIfStale(includeFuel = true, generation = 1) // after a save
        assertEquals(h1, account.calendarRecords)
        held.complete(Unit)
        refresh.join()
        assertEquals("The older refresh must not replace the newer Calendar", h1, account.calendarRecords)
    }

    @Test fun controlCalendarResponsesInOrderShowTheNewest() = runBlocking {
        val account = AccountDataState(ScriptedReader(listOf(Step(g0)), listOf(Step(h0), Step(h1))), noPrefs)
        account.loadGarage()
        account.refreshCalendar(includeFuel = true, generation = 0) { true }
        account.loadCalendarIfStale(includeFuel = true, generation = 1)
        assertEquals(h1, account.calendarRecords)
    }

    /** A refresh abandoned because the Fuel filter changed must not make the current load stale. */
    @Test fun anAbandonedCalendarRefreshDoesNotSupersedeTheCurrentFiltersLoad() = runBlocking {
        val held = CompletableDeferred<Unit>()
        val account = AccountDataState(ScriptedReader(listOf(Step(g0)), listOf(Step(h1, held), Step(h0))), noPrefs)
        account.loadGarage()
        val current = launch { account.loadCalendarIfStale(includeFuel = false, generation = 0) }
        yield()
        account.refreshCalendar(includeFuel = true, generation = 0) { false } // the filter has changed
        held.complete(Unit)
        current.join()
        assertEquals("The current filter's load must still publish", h1, account.calendarRecords)
    }

    /** The loading indicator belongs to the newest Calendar request. */
    @Test fun calendarLoadingStaysOnUntilTheNewestRequestFinishes() = runBlocking {
        val older = CompletableDeferred<Unit>()
        val newer = CompletableDeferred<Unit>()
        val account = AccountDataState(ScriptedReader(listOf(Step(g0)), listOf(Step(h0, older), Step(h1, newer))), noPrefs)
        account.loadGarage()
        val first = launch { account.loadCalendarIfStale(includeFuel = true, generation = 0) }
        yield()
        val second = launch { account.loadCalendarIfStale(includeFuel = true, generation = 1) }
        yield()
        older.complete(Unit)
        first.join()
        assertTrue("The newer request is still loading", account.calendarLoading)
        newer.complete(Unit)
        second.join()
        assertFalse(account.calendarLoading)
        assertEquals(h1, account.calendarRecords)
    }

    /** A new generation of description suggestions loads even while an older load is finishing. */
    @Test fun aNewDescriptionGenerationLoadsWhileAnOlderOneIsInFlight() = runBlocking {
        val older = CompletableDeferred<Unit>()
        val account = AccountDataState(ScriptedReader(listOf(Step(g0)), listOf(Step(h0, older), Step(h1))), noPrefs)
        account.loadGarage()
        val first = launch { account.loadDescriptionsIfStale(generation = 0) }
        yield()
        account.loadDescriptionsIfStale(generation = 1) // after a save
        assertEquals(h1, account.descriptionRecords)
        older.complete(Unit)
        first.join()
        assertEquals("The older load must not replace the newer suggestions", h1, account.descriptionRecords)
        assertFalse(account.descriptionsLoading)
    }
}
