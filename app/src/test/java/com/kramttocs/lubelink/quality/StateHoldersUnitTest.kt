package com.kramttocs.lubelink.quality

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.kramttocs.lubelink.core.FieldReadiness
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.GasStationFieldCheck
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.LubeLoggerReader
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordRef
import com.kramttocs.lubelink.core.RecordsLoad
import com.kramttocs.lubelink.core.ReminderId
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.core.VehiclePreferences
import com.kramttocs.lubelink.ui.AccountDataState
import com.kramttocs.lubelink.ui.EditorSessionState
import com.kramttocs.lubelink.ui.NavigationState
import com.kramttocs.lubelink.ui.Tab
import com.kramttocs.lubelink.ui.VehicleHistoryState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** App state holders, exercised without Android or a server (synthetic data only). */
class StateHoldersUnitTest {
    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", false)
    private val truck = Vehicle(VehicleId(2), 2018, "Synthetic", "Truck", "", false)
    private val record = ServiceRecord(RecordId(15), car.id, "2026-10-01", "Oil change", 1200, "10.00",
        "", "", emptyList(), RecordKind.SERVICE)

    private fun nav() = NavigationState(mutableStateOf(Tab.HOME), mutableStateOf(null), mutableStateOf(false))
    private fun editor() = EditorSessionState(mutableStateOf(false), mutableIntStateOf(0), mutableStateOf(null),
        mutableStateOf(null), mutableStateOf(RecordKind.SERVICE))

    private class FakeReader(var garage: GarageData, var calendar: List<ServiceRecord> = emptyList()) : LubeLoggerReader {
        var garageLoads = 0
        var calendarLoads = 0
        var failWith: Exception? = null
        override suspend fun loadGarage(): GarageData { garageLoads++; failWith?.let { throw it }; return garage }
        override suspend fun loadCalendarHistory(includeFuel: Boolean): RecordsLoad {
            calendarLoads++; failWith?.let { throw it }; return RecordsLoad(calendar)
        }
        override suspend fun checkGasStationFields() = GasStationFieldCheck(FieldReadiness.READY)
        override suspend fun loadHistory(vehicleId: VehicleId, includeFuel: Boolean) = RecordsLoad(emptyList())
    }
    private class Prefs(val hidden: Set<VehicleId> = emptySet(), val names: Map<VehicleId, String> = emptyMap()) : VehiclePreferences {
        override fun nicknameFor(vehicleId: VehicleId) = names[vehicleId].orEmpty()
        override fun isVehicleHidden(vehicleId: VehicleId) = vehicleId in hidden
    }

    @Test fun firstGarageLoadAppliesTheStartupVehicleOnlyOnce() {
        val nav = nav()
        nav.reconcileSelection(listOf(car.id, truck.id)) { truck.id }
        assertEquals(truck.id, nav.selectedId)
        nav.goToGarage()
        // A refresh never jumps from All vehicles back to the startup vehicle.
        nav.reconcileSelection(listOf(car.id, truck.id)) { truck.id }
        assertNull(nav.selectedId)
    }

    @Test fun restoredSelectionIsKeptWhileVisibleAndDroppedWhenHidden() {
        val nav = NavigationState(mutableStateOf(Tab.HISTORY), mutableStateOf(car.id), mutableStateOf(true))
        nav.reconcileSelection(listOf(car.id, truck.id)) { truck.id }
        assertEquals("A restored selection survives the Garage reload", car.id, nav.selectedId)
        nav.reconcileSelection(listOf(truck.id)) { truck.id }
        assertNull("A vehicle no longer visible is deselected", nav.selectedId)
    }

    @Test fun backFromAnOpenedVehicleReturnsToTheScreenItWasOpenedFrom() {
        val nav = nav()
        nav.switchTab(Tab.CALENDAR)
        nav.openVehicle(car.id)
        assertEquals(Tab.HOME, nav.tab)
        assertTrue(nav.backFromVehicle())
        assertEquals(Tab.CALENDAR, nav.tab)
        assertNull(nav.selectedId)
        assertFalse(nav.backFromVehicle())
    }

    @Test fun reportsReturnToTheTabAndVehicleTheyWereOpenedFrom() {
        val nav = nav()
        nav.selectFromGarage(car.id)
        nav.openReports(car.id)
        assertEquals(Tab.REPORTS, nav.tab)
        nav.closeReports()
        assertEquals(Tab.HOME, nav.tab)
        assertEquals(car.id, nav.selectedId)
    }

    @Test fun eachOpenedFormGetsANewSessionAndStaleCallbacksAreRejected() {
        val editor = editor()
        editor.beginEdit(record)
        val first = editor.session
        assertEquals(RecordRef(car.id, RecordKind.SERVICE, RecordId(15)), editor.target)
        editor.beginAdd(truck.id, RecordKind.REPAIR)
        assertNull("Add has no Edit target", editor.target)
        assertFalse("A callback from the previous form must not close the new one", editor.closeIfCurrent(first))
        assertTrue(editor.isOpen)
        assertTrue(editor.closeIfCurrent(editor.session))
        assertFalse(editor.isShowing)
    }

    @Test fun copyAndReminderOpenAddFormsNeverAnEdit() {
        val editor = editor()
        editor.beginCopy(record)
        assertNull(editor.target)
        assertEquals(record, editor.copySource)
        val reminder = MaintenanceReminder(ReminderId(4), car.id, "Rotate tires", "Urgent", "Date", "2026-11-01",
            null, 10, null, "")
        editor.beginFromReminder(reminder)
        assertNull(editor.target)
        assertNull("Opening another form clears the copy source", editor.copySource)
        assertEquals(RecordKind.SERVICE, editor.initialKind)
    }

    @Test fun hidingAVehicleClosesItsFormAndDeleteConfirmation() {
        val editor = editor()
        editor.beginEdit(record)
        editor.deleteTarget = record
        editor.closeForVehicle(truck.id)
        assertTrue(editor.isOpen)
        editor.closeForVehicle(car.id)
        assertFalse(editor.isShowing)
        assertNull(editor.deleteTarget)
    }

    @Test fun accountDataAppliesNicknamesAndHiddenVehicles() = runBlocking {
        val reader = FakeReader(GarageData(listOf(car, truck), emptyMap(), emptyList()))
        val account = AccountDataState(reader, Prefs(hidden = setOf(truck.id), names = mapOf(car.id to "Daily")))
        assertTrue(account.loadGarage().isSuccess)
        assertEquals(setOf(car.id), account.visibleVehicleIds)
        assertEquals("Daily", account.nicknames[car.id])
    }

    @Test fun failedFirstLoadShowsAnErrorButAFailedReloadKeepsTheGarage() = runBlocking {
        val reader = FakeReader(GarageData(listOf(car), emptyMap(), emptyList()))
        val first = AccountDataState(reader.apply { failWith = LubeLoggerApi.ApiException("HTTP 401", 401) }, Prefs())
        assertTrue(first.loadGarage().isFailure)
        assertEquals("HTTP 401", first.loadError)
        assertNull(first.garage)

        val sameAccount = FakeReader(GarageData(listOf(car), emptyMap(), emptyList()))
        val loaded = AccountDataState(sameAccount, Prefs())
        loaded.loadGarage()
        sameAccount.failWith = LubeLoggerApi.ApiException("timeout")
        assertTrue(loaded.loadGarage().isFailure)
        assertNull("A failed reload is reported, not shown as a load error", loaded.loadError)
        assertEquals(listOf(car), loaded.garage?.vehicles)
    }

    @Test fun calendarLoadsOncePerFuelFilterAndGeneration() = runBlocking {
        val reader = FakeReader(GarageData(listOf(car), emptyMap(), emptyList()), calendar = listOf(record))
        val account = AccountDataState(reader, Prefs())
        account.loadCalendarIfStale(includeFuel = true, generation = 0)
        assertEquals("Nothing loads before the Garage", 0, reader.calendarLoads)
        account.loadGarage()
        account.loadCalendarIfStale(true, 0)
        account.loadCalendarIfStale(true, 0)
        assertEquals(1, reader.calendarLoads)
        assertEquals(listOf(record), account.visibleCalendarRecords)
        account.loadCalendarIfStale(false, 0)
        account.invalidateCalendar()
        account.loadCalendarIfStale(false, 0)
        assertEquals(3, reader.calendarLoads)
    }

    @Test fun onlyTheMostRecentlyBegunHistoryLoadPublishes() {
        val history = VehicleHistoryState()
        val older = history.begin()
        val newer = history.begin(clear = false)
        history.publish(newer, RecordsLoad(listOf(record)))
        history.publish(older, RecordsLoad(emptyList()))
        history.fail(older, "late failure")
        assertEquals("the older load is dropped", listOf(record), history.records)
        assertNull(history.error)
        val refresh = history.begin(clear = false)
        assertEquals("A refresh keeps showing the current History", listOf(record), history.records)
        history.fail(refresh, "timeout")
        assertEquals("timeout", history.error)
    }
}
