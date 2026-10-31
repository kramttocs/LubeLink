package com.kramttocs.lubelink.quality

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.LubeTheme
import com.kramttocs.lubelink.ui.VehicleScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** OD-37: a vehicle's Recent history says "Loading…" while History loads and "No records yet" when it has none,
 * and records are dated like Reminders. The real VehicleScreen with synthetic data. Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class RecentHistoryUiTest {
    @get:Rule val compose = createComposeRule()

    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", useHours = false)
    private val fuel = ServiceRecord(RecordId(16), car.id, "2026-10-20", "", 1250, "52.10", "", "", emptyList(),
        RecordKind.FUEL, fuelConsumed = 13.2, fuelEconomy = 29.4)

    @Test fun recentHistorySaysWhatIsHappening() {
        var records by mutableStateOf(emptyList<ServiceRecord>())
        var loading by mutableStateOf(true)
        var incomplete by mutableStateOf(false)
        compose.setContent {
            LubeTheme {
                VehicleScreen(car, GarageData(listOf(car), emptyMap(), emptyList()),
                    LubeLoggerApi("https://lubelogger.invalid", "synthetic"), records, loading, incomplete,
                    refreshGeneration = 0, nickname = null, onHistory = {}, onReports = {}, onAdd = null,
                    onLogReminder = null, onEdit = null, onCopy = null, onDelete = null)
            }
        }
        compose.onNodeWithText("Loading…").assertExists()
        compose.onNodeWithText("No records yet").assertDoesNotExist()

        loading = false
        compose.onNodeWithText("No records yet").assertExists()
        compose.onNodeWithText("Loading…").assertDoesNotExist()

        incomplete = true // a failed or partial load has its own notice; it is not "no records"
        compose.onNodeWithText("No records yet").assertDoesNotExist()

        incomplete = false
        records = listOf(fuel)
        compose.onNodeWithText("No records yet").assertDoesNotExist()
        compose.onNodeWithText("Oct 20, 2026", substring = true).assertExists()
        compose.onNodeWithText("29.4 mpg · 13.2 gal", substring = true).assertExists()
    }
}
