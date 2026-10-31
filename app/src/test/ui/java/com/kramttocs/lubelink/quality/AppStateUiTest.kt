package com.kramttocs.lubelink.quality

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.kramttocs.lubelink.core.FieldReadiness
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.GasStationFieldCheck
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.LubeLoggerReader
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordRef
import com.kramttocs.lubelink.core.RecordsLoad
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.core.VehiclePreferences
import com.kramttocs.lubelink.ui.AccountDataState
import com.kramttocs.lubelink.ui.AccountRefreshEffect
import com.kramttocs.lubelink.ui.RefreshRequest
import com.kramttocs.lubelink.ui.rememberVehicleHistory
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicInteger
import com.kramttocs.lubelink.ui.EditorSessionState
import com.kramttocs.lubelink.ui.NavigationState
import com.kramttocs.lubelink.ui.Tab
import com.kramttocs.lubelink.ui.rememberAccountData
import com.kramttocs.lubelink.ui.rememberEditorSession
import com.kramttocs.lubelink.ui.rememberNavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** App-level state holders inside real compositions: credential isolation and restoration. */
@RunWith(AndroidJUnit4::class)
class AppStateUiTest {
    @get:Rule val compose = createComposeRule()

    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", false)
    private val record = ServiceRecord(RecordId(15), car.id, "2026-10-01", "Oil change", 1200, "10.00",
        "", "", emptyList(), RecordKind.SERVICE)
    private val noPrefs = object : VehiclePreferences {
        override fun nicknameFor(vehicleId: VehicleId) = ""
        override fun isVehicleHidden(vehicleId: VehicleId) = false
    }

    private class Reader(val garage: GarageData?, val calendar: List<ServiceRecord>) : LubeLoggerReader {
        override suspend fun loadGarage() = garage ?: throw LubeLoggerApi.ApiException("HTTP 401. Check your connection and Editor API key.", 401)
        override suspend fun loadCalendarHistory(includeFuel: Boolean) =
            if (garage == null) throw LubeLoggerApi.ApiException("HTTP 401", 401) else RecordsLoad(calendar)
        override suspend fun checkGasStationFields() = GasStationFieldCheck(FieldReadiness.READY)
        override suspend fun loadHistory(vehicleId: VehicleId, includeFuel: Boolean) = RecordsLoad(emptyList())
    }

    /** After a key change whose first load fails, nothing from the previous account is shown. */
    @Test fun keyChangeDropsThePreviousAccountsDataEvenWhenTheNewKeyFails() {
        val oldKey = Reader(GarageData(listOf(car), emptyMap(), emptyList()), listOf(record))
        val rejectedKey = Reader(null, emptyList())
        var reader by mutableStateOf<LubeLoggerReader>(oldKey)
        var account: AccountDataState? = null
        compose.setContent {
            val current = rememberAccountData(reader, noPrefs)
            account = current
            LaunchedEffect(current) {
                current.loadGarage()
                current.loadCalendarIfStale(includeFuel = true, generation = 0)
            }
            Text(current.loadError ?: current.garage?.vehicles?.joinToString { it.model } ?: "Loading")
        }
        compose.onNodeWithText("Wagon").assertExists()
        compose.awaitCondition(5_000) { account?.calendarRecords?.isNotEmpty() == true }
        compose.runOnIdle { reader = rejectedKey }
        compose.onNodeWithText("HTTP 401. Check your connection and Editor API key.").assertExists()
        compose.onNodeWithText("Wagon").assertDoesNotExist()
        compose.runOnIdle {
            assertNull("Previous account's Garage must be gone", account!!.garage)
            assertTrue("Previous account's history must be gone", account!!.calendarRecords.isEmpty())
            assertTrue(account!!.descriptionRecords.isEmpty())
            assertNull(account!!.reportSnapshot)
        }
    }

    /** OD-01 at app level: tab, vehicle and an open Edit (as an Edit, on the same record) survive restoration. */
    @Test fun navigationAndOpenEditSurviveStateRestoration() {
        var nav: NavigationState? = null
        var editor: EditorSessionState? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            nav = rememberNavigationState()
            editor = rememberEditorSession()
        }
        compose.runOnIdle {
            nav!!.reconcileSelection(listOf(car.id)) { null }
            nav!!.openVehicle(car.id)
            nav!!.tab = Tab.HISTORY
            editor!!.beginEdit(record)
        }
        val session = editor!!.session
        restoration.emulateSavedInstanceStateRestore()
        compose.runOnIdle {
            assertEquals(Tab.HISTORY, nav!!.tab)
            assertEquals(car.id, nav!!.selectedId)
            assertTrue(editor!!.isOpen)
            assertEquals("Restored as an Edit of the same record", RecordRef(car.id, RecordKind.SERVICE, RecordId(15)), editor!!.target)
            assertEquals(session, editor!!.session)
            // The startup preference must not override a restored selection.
            nav!!.reconcileSelection(listOf(car.id)) { null }
            assertEquals(car.id, nav!!.selectedId)
        }
    }

    /** History for [car] is [records]; from the second History call on (the refresh), waits for [gate]. */
    private class GatedReader(val garage: GarageData?, val records: List<ServiceRecord>,
                              val gate: CompletableDeferred<Unit>? = null) : LubeLoggerReader {
        val historyCalls = AtomicInteger()
        override suspend fun loadGarage() = garage ?: throw LubeLoggerApi.ApiException("HTTP 401", 401)
        override suspend fun loadCalendarHistory(includeFuel: Boolean) = RecordsLoad(emptyList())
        override suspend fun checkGasStationFields() = GasStationFieldCheck(FieldReadiness.READY)
        override suspend fun loadHistory(vehicleId: VehicleId, includeFuel: Boolean): RecordsLoad {
            if (historyCalls.incrementAndGet() >= 2) gate?.await()
            return RecordsLoad(records)
        }
    }

    /**
     * A pull-to-refresh started with the previous key, still in flight when the key changes,
     * must never publish into the new key's History, whether the new key's load succeeds or fails.
     * Mounts the production holders and refresh effect the way LubeLinkApp wires them.
     */
    private fun staleRefreshNeverPublishes(newKeySucceeds: Boolean) {
        val gate = CompletableDeferred<Unit>()
        val garage = GarageData(listOf(car), emptyMap(), emptyList())
        val oldKey = GatedReader(garage, listOf(record.copy(description = "Old account record")), gate)
        val newKey = GatedReader(if (newKeySucceeds) garage else null, listOf(record.copy(description = "New account record")))
        var reader by mutableStateOf<LubeLoggerReader>(oldKey)
        val nav = NavigationState(mutableStateOf(Tab.HOME), mutableStateOf(car.id), mutableStateOf(true))
        var requestRefresh: () -> Unit = {}
        compose.setContent {
            val account = rememberAccountData(reader, noPrefs)
            LaunchedEffect(account) { account.loadGarage() }
            val history = rememberVehicleHistory(account.historySource, nav.selectedId, true, account.garage != null, 0)
            var request by remember(account) { mutableStateOf<RefreshRequest?>(null) }
            AccountRefreshEffect(account, history, nav, request, includeFuelNow = true) { request = null }
            requestRefresh = { request = RefreshRequest(Tab.HOME, car.id, true, 0) }
            Text(account.loadError ?: history.records.joinToString { it.description }.ifEmpty { "No records" })
        }
        compose.onNodeWithText("Old account record").assertExists()
        compose.runOnIdle { requestRefresh() }
        compose.awaitCondition(5_000) { oldKey.historyCalls.get() == 2 } // the refresh is now waiting on the server
        compose.runOnIdle { reader = newKey }
        val expected = if (newKeySucceeds) "New account record" else "HTTP 401"
        compose.awaitCondition(5_000) { compose.onAllNodesWithText(expected).fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { gate.complete(Unit) } // the old key's response finally arrives
        compose.waitForIdle()
        compose.onNodeWithText("Old account record").assertDoesNotExist()
        compose.onNodeWithText(expected).assertExists()
    }

    @Test fun staleRefreshFromThePreviousKeyNeverReplacesTheNewKeysHistory() = staleRefreshNeverPublishes(true)

    @Test fun staleRefreshFromThePreviousKeyNeverAppearsWhenTheNewKeyFails() = staleRefreshNeverPublishes(false)
}
