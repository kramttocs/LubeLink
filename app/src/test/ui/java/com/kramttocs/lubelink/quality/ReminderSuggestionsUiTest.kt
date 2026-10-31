package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-52 and OD-58: on Reminders, a vehicle with no reminders gets common ones to set up in LubeLogger, through the
 * whole app against the synthetic garage server (here the Ioniq, an EV, and the Kubota have none). An EV leaves out the
 * oil change; "Set up in LubeLogger" opens the server's page and sends nothing; a vehicle with reminders, All
 * vehicles, and reminders that did not load show no suggestions. The switch in Settings → Maintenance notifications is
 * on by default; off hides the suggestions and is saved (OD-59). Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class ReminderSuggestionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private var server: ScreenshotServer? = null
    private val driver = TourDriver(compose) {}
    private val opened = mutableListOf<String>()
    private val full = "Oil change · Tire rotation · Cabin air filter · Brake inspection · Wiper blades"
    private val electric = "Tire rotation · Cabin air filter · Brake inspection · Wiper blades"

    @After fun cleanUp() {
        settings.removeManagerKey()
        settings.disconnect()
        server?.close()
    }

    /** Reminders only for the CR-V and the F-150, unless [remindersFail]. */
    private fun open(remindersFail: Boolean = false) {
        server = ScreenshotServer(
            override = { path ->
                if (path.substringBefore('?') == "/api/vehicle/reminders/all")
                    JSONArray(ScreenshotGarage.reminders.filter { it.car in setOf(1, 2) }.map(ScreenshotGarage::reminderJson)).toString()
                else null
            },
            fails = { remindersFail && it.startsWith("/api/vehicle/reminders") })
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server!!.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        val handler = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
        compose.setContent {
            LubeTheme { Surface(Modifier.fillMaxSize()) {
                CompositionLocalProvider(LocalUriHandler provides handler) { LubeLinkApp(settings) }
            } }
        }
        driver.waitFor("2019 Honda CR-V")
    }

    private fun reminders(vehicle: String) {
        driver.tap(vehicle)
        driver.waitFor("Add record")
        driver.tap("Reminders")
        driver.waitFor("Reminders are managed in LubeLogger.")
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun aVehicleWithNoRemindersGetsTheCommonOnes() {
        open()
        reminders("2012 Kubota L3301")
        driver.waitFor("No reminders for this vehicle yet.")
        assertTrue(shows("Common reminders to set up"))
        assertTrue("The full list", shows(full))
    }

    @Test fun anElectricVehicleLeavesOutTheOilChange() {
        open()
        reminders("2022 Hyundai Ioniq 5")
        driver.waitFor("No reminders for this vehicle yet.")
        assertTrue("No oil change for an EV", shows(electric))
        assertFalse(shows(full))
    }

    @Test fun setUpOpensLubeLoggerAndSendsNothing() {
        open()
        reminders("2012 Kubota L3301")
        val before = server!!.posts.get()
        driver.tap("Set up in LubeLogger ↗")
        compose.waitForIdle()
        assertEquals(listOf(settings.baseUrl), opened.filter { it == settings.baseUrl })
        assertEquals("No record or reminder is written", before, server!!.posts.get())
    }

    @Test fun aVehicleWithRemindersAllVehiclesAndUnloadedRemindersHaveNoSuggestions() {
        open()
        reminders("2019 Honda CR-V")
        driver.waitFor("Tire rotation")
        assertFalse(shows("Common reminders to set up"))
        driver.tap("Overview"); driver.tap("Garage")
        driver.waitFor("2016 Ford F-150")
        driver.tap("Reminders")
        driver.waitFor("Reminders are managed in LubeLogger.")
        assertFalse("All vehicles", shows("Common reminders to set up"))
    }

    @Test fun remindersThatDidNotLoadAreNeverCalledNone() {
        open(remindersFail = true)
        reminders("2012 Kubota L3301")
        driver.waitFor("Reminders couldn't be loaded from LubeLogger.")
        assertFalse(shows("No reminders for this vehicle yet."))
        assertFalse(shows("No reminders in this view"))
        assertFalse(shows("Common reminders to set up"))
        driver.tap("Overview"); driver.tap("Garage")
        driver.waitFor("2016 Ford F-150")
        driver.tap("Reminders")
        driver.waitFor("Reminders couldn't be loaded from LubeLogger.")
        assertFalse("All vehicles", shows("No reminders in this view"))
    }

    /** The same rule on the vehicle's overview and the Calendar: reminders that did not load are not "none". */
    @Test fun theOverviewAndCalendarNeverCallUnloadedRemindersNone() {
        open(remindersFail = true)
        driver.tap("2012 Kubota L3301")
        driver.waitFor("Add record")
        driver.waitFor("Reminders couldn't be loaded from LubeLogger.")
        assertFalse(shows("No reminders configured for this vehicle"))
        driver.tap("Calendar")
        driver.waitFor("Reminders couldn't be loaded from LubeLogger.")
        assertFalse(shows("No dated maintenance or reminders on this day."))
    }

    private val suggestSwitch get() = compose.onNode(hasText("Suggest common reminders", substring = true) and isToggleable())

    private fun notificationSettings() {
        driver.tap("Settings")
        driver.tap("Maintenance notifications")
        driver.scrollToText("Suggest common reminders")
    }

    @Test fun theSwitchIsOnByDefaultAndOffHidesTheSuggestionsUntilItIsOnAgain() {
        open()
        notificationSettings()
        suggestSwitch.assertIsOn()
        suggestSwitch.performClick(); compose.waitForIdle()
        suggestSwitch.assertIsOff()
        assertFalse("Off is saved on the phone", settings.suggestReminders)
        driver.tap("Overview")
        reminders("2012 Kubota L3301")
        driver.waitFor("No reminders for this vehicle yet.")
        assertFalse("Off: no suggestions", shows("Common reminders to set up"))
        notificationSettings()
        suggestSwitch.performClick(); compose.waitForIdle()
        driver.tap("Reminders")
        driver.waitFor("Common reminders to set up")
        assertTrue(settings.suggestReminders)
    }
}
