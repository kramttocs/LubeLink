package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-49: three vehicle-page and History additions, tapped through the whole app against the synthetic garage server.
 * A selected vehicle's reading under its name in the History, Reminders and Calendar headers (none for All
 * vehicles, none when the summaries did not load). One "reminders due" panel on a single vehicle's History,
 * naming the most urgent and opening Reminders (none when nothing is due or the reminders did not load). The
 * vehicle page's ⓘ opens the details LubeLogger sends; unreadable details say so and the Garage still loads. Runs on
 * the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class DesignIdeasUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private var server: ScreenshotServer? = null
    private val driver = TourDriver(compose) {}

    @After fun cleanUp() {
        settings.removeManagerKey()
        settings.disconnect()
        server?.close()
    }

    private fun open(running: ScreenshotServer = ScreenshotServer()) {
        server = running
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = running.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }
        driver.waitFor("2019 Honda CR-V")
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun openVehicle(name: String) { driver.tap(name); driver.waitFor("Add record") }

    // ---------- The reading under the vehicle name ----------

    @Test fun aSelectedVehiclesReadingIsInTheHistoryRemindersAndCalendarHeaders() {
        open()
        openVehicle("2019 Honda CR-V")
        for (page in listOf("History", "Reminders", "Calendar")) {
            driver.tap(page)
            driver.waitFor(page)
            assertTrue("$page shows the CR-V's reading", shows("48,210 mi"))
        }
    }

    @Test fun allVehiclesHaveNoReadingInTheHeader() {
        open()
        driver.tap("History")
        driver.waitFor("All vehicles")
        listOf("48,210 mi", "102,730 mi", "18,940 mi", "1,284 hr").forEach {
            assertFalse("No single vehicle's reading ($it) for All vehicles", shows(it))
        }
    }

    @Test fun noReadingIsShownWhenTheSummariesDidNotLoad() {
        open(ScreenshotServer(fails = { it.startsWith("/api/vehicle/info") }))
        openVehicle("2019 Honda CR-V")
        driver.tap("History")
        driver.waitFor("Oil and filter change")
        assertFalse("No reading guessed without a summary", shows("48,210 mi"))
        assertFalse("The header does not stand in for the overview's notice", shows("Reading unavailable"))
    }

    // ---------- Reminders due on History ----------

    @Test fun dueRemindersAreSummedUpOnHistoryAndOpenReminders() {
        open()
        openVehicle("2016 Ford F-150")
        driver.tap("History")
        driver.waitFor("2 reminders due")
        assertTrue("The most urgent is named", shows("State inspection"))
        assertTrue("…with its urgency", shows("Past due"))
        driver.tap("Reminders")
        driver.waitFor("Coolant flush")
        assertTrue("Reminders opened for the F-150", shows("2016 Ford F-150"))
        assertFalse("The panel was History's", shows("2 reminders due"))
    }

    @Test fun oneDueReminderIsCountedAsOne() {
        open()
        openVehicle("2019 Honda CR-V")
        driver.tap("History")
        driver.waitFor("1 reminder due")
        assertTrue(shows("Tire rotation"))
    }

    @Test fun noPanelWhenNothingIsDueOrForAllVehicles() {
        open()
        openVehicle("2022 Hyundai Ioniq 5")
        driver.tap("History")
        driver.waitFor("History")
        compose.waitForIdle()
        assertFalse("Not urgent is not due", shows("reminder due") || shows("reminders due"))
        driver.tap("Garage")
        driver.waitFor("2019 Honda CR-V")
        driver.tap("History")
        driver.waitFor("All vehicles")
        assertFalse("No panel for All vehicles", shows("reminder due") || shows("reminders due"))
    }

    @Test fun noPanelWhenTheRemindersDidNotLoad() {
        open(ScreenshotServer(fails = { it.startsWith("/api/vehicle/reminders") }))
        openVehicle("2016 Ford F-150")
        driver.tap("History")
        driver.waitFor("History")
        compose.waitForIdle()
        assertFalse("An unknown list is never summed up", shows("reminder due") || shows("reminders due"))
    }

    // ---------- Vehicle details ----------

    @Test fun theDetailsButtonShowsWhatLubeLoggerSends() {
        open()
        openVehicle("2019 Honda CR-V")
        driver.tapDescription("Vehicle details")
        driver.waitFor("From LubeLogger; edit them there.")
        listOf("SYN-101", "Gasoline", "Miles", "Mar 14, 2019 · \$27,450.00", "family, commuter").forEach {
            assertTrue("Shows $it", shows(it))
        }
        assertFalse("No sale recorded", shows("Sold"))
        driver.tap("Close")
        driver.waitFor("Add record")
        driver.tap("Garage")
        openVehicle("2016 Ford F-150")
        driver.tapDescription("Vehicle details")
        driver.waitFor("Diesel")
        assertFalse("Nothing purchased is shown when none is set", shows("Purchased"))
        assertFalse("No tags row when there are none", shows("Tags"))
    }

    @Test fun unreadableDetailsSaySoAndTheGarageStillLoads() {
        open(ScreenshotServer(override = { path ->
            if (path == "/api/vehicles") JSONArray(ScreenshotGarage.answer(path)!!)
                .also { it.getJSONObject(0).put("tags", 7) }.toString() else null
        }))
        openVehicle("2019 Honda CR-V")
        driver.tapDescription("Vehicle details")
        driver.waitFor("LubeLink could not read this vehicle's details from LubeLogger.")
        assertFalse("Nothing half-read is shown", shows("SYN-101"))
    }
}
