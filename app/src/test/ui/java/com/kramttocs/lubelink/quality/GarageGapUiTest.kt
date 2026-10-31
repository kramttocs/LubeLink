package com.kramttocs.lubelink.quality

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.GarageSection
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.ReminderId
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.GarageGapNotice
import com.kramttocs.lubelink.ui.GarageScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** OD-24 at the real screens: when reminders or counts did not load, the Garage says so and never shows "none".
 * Runs on the JVM (Robolectric) and on the emulator. Synthetic data; no network. */
@RunWith(AndroidJUnit4::class)
class GarageGapUiTest {
    @get:Rule val compose = createComposeRule()
    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", useHours = false)
    private val reminder = MaintenanceReminder(ReminderId(4), car.id, "Rotate tires", "Urgent", "Date", "2026-11-01",
        null, 8, null, "")
    private val api = LubeLoggerApi("https://lubelogger.invalid", "synthetic")

    private fun garage(data: GarageData, retry: () -> Unit = {}) = compose.setContent {
        Column {
            if (data.missing.isNotEmpty()) GarageGapNotice(data.missing, retry)
            GarageScreen(data, api, 0, emptyMap(), SecureConfig.GarageLayout.LIST, onManageVisibility = {},
                onSelect = {}, onReports = {}, onAddVehicle = {})
        }
    }

    @Test fun missingRemindersAreShownAsUnavailableNotAsNone() {
        var retries = 0
        garage(GarageData(listOf(car), emptyMap(), emptyList(), setOf(GarageSection.REMINDERS))) { retries++ }
        compose.onNodeWithText("Reminders unavailable").assertExists()
        compose.onNodeWithText("No reminders configured").assertDoesNotExist()
        compose.onNodeWithText("Reminders couldn't be loaded", substring = true).assertExists()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun missingCountsSayMileageAndCountsCouldNotBeLoaded() {
        garage(GarageData(listOf(car), emptyMap(), listOf(reminder), setOf(GarageSection.SUMMARIES)))
        compose.onNodeWithText("Mileage and record counts couldn't be loaded", substring = true).assertExists()
        compose.onNodeWithText("Next: Rotate tires").assertExists()
    }

    @Test fun controlACompleteGarageShowsNoNotice() {
        garage(GarageData(listOf(car), emptyMap(), emptyList()))
        compose.onNodeWithText("No reminders configured").assertExists()
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }
}
