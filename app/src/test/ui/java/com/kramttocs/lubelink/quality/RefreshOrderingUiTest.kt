package com.kramttocs.lubelink.quality

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.kramttocs.lubelink.ui.AccountRefreshEffect
import com.kramttocs.lubelink.ui.NavigationState
import com.kramttocs.lubelink.ui.RefreshRequest
import com.kramttocs.lubelink.ui.Tab
import com.kramttocs.lubelink.ui.rememberAccountData
import com.kramttocs.lubelink.ui.rememberVehicleHistory
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selected vehicle's History with the production holder and refresh
 * effect, wired as LubeLinkApp wires them. A pull-to-refresh (H0) is held at the server; a confirmed
 * save bumps the reload generation and the new History (H1) appears; then H0 is released. H1 must stay.
 */
@RunWith(AndroidJUnit4::class)
class RefreshOrderingUiTest {
    @get:Rule val compose = createComposeRule()

    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", false)
    private fun record(id: Int, description: String) = ServiceRecord(RecordId(id), car.id, "2026-10-01",
        description, 1200, "10.00", "", "", emptyList(), RecordKind.SERVICE)
    private val noPrefs = object : VehiclePreferences {
        override fun nicknameFor(vehicleId: VehicleId) = ""
        override fun isVehicleHidden(vehicleId: VehicleId) = false
    }

    /** History call 1 = initial H0, call 2 = the refresh (H0, held until [held]), call 3+ = H1 after the save. */
    private class Reader(val held: CompletableDeferred<Unit>, val h0: List<ServiceRecord>, val h1: List<ServiceRecord>)
        : LubeLoggerReader {
        val historyCalls = AtomicInteger()
        override suspend fun loadGarage() = GarageData(listOf(Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", false)),
            emptyMap(), emptyList())
        override suspend fun loadCalendarHistory(includeFuel: Boolean) = RecordsLoad(emptyList())
        override suspend fun checkGasStationFields() = GasStationFieldCheck(FieldReadiness.READY)
        override suspend fun loadHistory(vehicleId: VehicleId, includeFuel: Boolean): RecordsLoad = RecordsLoad(
            when (historyCalls.incrementAndGet()) {
                1 -> h0
                2 -> { held.await(); h0 }
                else -> h1
            })
    }

    @Test fun anOlderRefreshArrivingAfterTheSaveReloadDoesNotHideTheSavedRecord() {
        val held = CompletableDeferred<Unit>()
        val reader = Reader(held, listOf(record(15, "Oil change")),
            listOf(record(15, "Oil change"), record(16, "Just saved repair")))
        val nav = NavigationState(mutableStateOf(Tab.HOME), mutableStateOf(car.id), mutableStateOf(true))
        var reload by mutableIntStateOf(0)
        var requestRefresh: () -> Unit = {}
        compose.setContent {
            val account = rememberAccountData(reader, noPrefs)
            LaunchedEffect(account, reload) { account.loadGarage() }
            val history = rememberVehicleHistory(account.historySource, nav.selectedId, true, account.garage != null, reload)
            var request by remember(account) { mutableStateOf<RefreshRequest?>(null) }
            AccountRefreshEffect(account, history, nav, request, includeFuelNow = true) { request = null }
            requestRefresh = { request = RefreshRequest(Tab.HOME, car.id, true, reload) }
            Text(history.records.joinToString(" / ") { it.description }.ifEmpty { "No records" })
        }
        compose.onNodeWithText("Oil change").assertExists()
        compose.runOnIdle { requestRefresh() }
        compose.awaitCondition(5_000) { reader.historyCalls.get() == 2 } // the refresh is waiting on the server
        compose.runOnIdle { reload++ }                                // a confirmed save
        compose.awaitCondition(5_000) {
            compose.onAllNodesWithText("Oil change / Just saved repair").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { held.complete(Unit) }                     // the older refresh answers last
        compose.waitForIdle()
        compose.onNodeWithText("Oil change / Just saved repair").assertExists()
        compose.onNodeWithText("Oil change").assertDoesNotExist()
    }

    /**
     * The refresh's Garage read is held; meanwhile the user moves to another vehicle or Fuel
     * filter, whose own History load is also held. The abandoned refresh then finishes first. The
     * current History must still appear, whichever order the responses arrive in.
     */
    private class ContextReader(val garage: GarageData) : LubeLoggerReader {
        val garageCalls = AtomicInteger()
        val heldGarage = CompletableDeferred<Unit>()
        val heldHistory = mutableMapOf<Pair<VehicleId, Boolean>, CompletableDeferred<Unit>>()
        val historyCalls = java.util.concurrent.CopyOnWriteArrayList<Pair<VehicleId, Boolean>>()
        override suspend fun loadGarage(): GarageData { if (garageCalls.incrementAndGet() == 2) heldGarage.await(); return garage }
        override suspend fun loadCalendarHistory(includeFuel: Boolean) = RecordsLoad(emptyList())
        override suspend fun checkGasStationFields() = GasStationFieldCheck(FieldReadiness.READY)
        override suspend fun loadHistory(vehicleId: VehicleId, includeFuel: Boolean): RecordsLoad {
            historyCalls += vehicleId to includeFuel
            heldHistory[vehicleId to includeFuel]?.await()
            return RecordsLoad(listOf(ServiceRecord(RecordId(20 + vehicleId.value), vehicleId, "2026-10-01",
                "History of vehicle ${vehicleId.value}, fuel ${if (includeFuel) "on" else "off"}", 1200, "10.00", "", "",
                emptyList(), RecordKind.SERVICE)))
        }
    }

    private val truck = Vehicle(VehicleId(2), 2018, "Synthetic", "Truck", "", false)

    private fun abandonedRefresh(changeFuel: Boolean, currentAnswersFirst: Boolean) {
        val reader = ContextReader(GarageData(listOf(car, truck), emptyMap(), emptyList()))
        val nav = NavigationState(mutableStateOf(Tab.HOME), mutableStateOf(car.id), mutableStateOf(true))
        var fuel by mutableStateOf(true)
        var requestRefresh: () -> Unit = {}
        compose.setContent {
            val account = rememberAccountData(reader, noPrefs)
            LaunchedEffect(account) { account.loadGarage() }
            val history = rememberVehicleHistory(account.historySource, nav.selectedId, fuel, account.garage != null, 0)
            var request by remember(account) { mutableStateOf<RefreshRequest?>(null) }
            AccountRefreshEffect(account, history, nav, request, includeFuelNow = fuel) { request = null }
            requestRefresh = { request = RefreshRequest(Tab.HOME, nav.selectedId, fuel, 0) }
            Text(history.records.joinToString(" / ") { it.description }.ifEmpty { "No records" })
        }
        compose.onNodeWithText("History of vehicle 1, fuel on").assertExists()
        val next = if (changeFuel) car.id to false else truck.id to true
        val heldCurrent = CompletableDeferred<Unit>().also { reader.heldHistory[next] = it }
        compose.runOnIdle { requestRefresh() }
        compose.awaitCondition(5_000) { reader.garageCalls.get() == 2 }      // the refresh's Garage read is held
        compose.runOnIdle { if (changeFuel) fuel = false else nav.selectedId = truck.id }
        compose.awaitCondition(5_000) { next in reader.historyCalls }         // the current context's own load is held
        if (currentAnswersFirst) { heldCurrent.complete(Unit); compose.waitForIdle() }
        compose.runOnIdle { reader.heldGarage.complete(Unit) }           // the abandoned refresh continues
        compose.waitForIdle()
        heldCurrent.complete(Unit)
        val expected = "History of vehicle ${next.first.value}, fuel ${if (next.second) "on" else "off"}"
        compose.awaitCondition(5_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        compose.onNodeWithText(expected).assertExists()
    }

    @Test fun movingToAnotherVehicleDuringARefreshStillShowsItsHistory() = abandonedRefresh(false, false)
    @Test fun movingToAnotherVehicleDuringARefreshControlCurrentAnswersFirst() = abandonedRefresh(false, true)
    @Test fun changingTheFuelFilterDuringARefreshStillShowsTheNewHistory() = abandonedRefresh(true, false)
}
