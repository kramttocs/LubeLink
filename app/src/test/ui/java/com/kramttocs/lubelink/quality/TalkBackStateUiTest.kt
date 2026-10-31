package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.LubeLoggerEditorApi
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordWriteIntent
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.core.WriteAction
import com.kramttocs.lubelink.ui.LocalStationLookups
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import com.kramttocs.lubelink.ui.WriteSafety
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * TalkBack reads each control with its name and its state. Every switch and checkbox is one control with its label
 * (a switch next to a separate text is read as an unnamed "switch"), and a tap on the label toggles it: checked by
 * finding a toggleable node that carries the label's text, then tapping that node. The Calendar's chosen day is
 * "selected". Whole app against the synthetic garage server; runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class TalkBackStateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private val server = ScreenshotServer()
    private val driver = TourDriver(compose) {}

    @Before fun connectToTheSyntheticGarage() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        ScreenshotGarage.preferNearbyList(context, server.baseUrl)
    }

    @After fun cleanUp() {
        compose.runOnIdle { if (WriteSafety.state !is WriteSafety.State.Idle && WriteSafety.state !is WriteSafety.State.Pending)
            WriteSafety.acknowledge(context, checkedServer = true) }
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Idle }
        context.getSharedPreferences(StoredFormat.UNCONFIRMED_WRITE.fileName, Context.MODE_PRIVATE).edit().clear().commit()
        settings.setVehicleHidden(VehicleId(4), false)
        settings.disconnect()
        server.close()
    }

    private fun open() {
        compose.setContent {
            LubeTheme {
                CompositionLocalProvider(LocalStationLookups provides ScreenshotGarage.stationLookups) {
                    Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) }
                }
            }
        }
        driver.waitFor("2019 Honda CR-V")
    }

    private fun control(label: String): SemanticsMatcher = hasText(label) and isToggleable()

    /** The control named [label] starts [on], and a tap on it (its label included) changes it. */
    private fun assertLabelled(label: String, on: Boolean) {
        val nodes = compose.onAllNodes(control(label))
        assertTrue("\"$label\" is one control with its label", nodes.fetchSemanticsNodes().isNotEmpty())
        if (on) nodes.onFirst().assertIsOn() else nodes.onFirst().assertIsOff()
        nodes.onFirst().performClick()
        compose.waitForIdle()
        if (on) nodes.onFirst().assertIsOff() else nodes.onFirst().assertIsOn()
    }

    private fun addFuel() {
        driver.tap("2019 Honda CR-V")
        driver.tap("History")
        driver.tap("Fuel")
        driver.tap("Add record")
        driver.waitFor("Gas station (optional)")
    }

    @Test fun theFuelFormsSwitches() {
        open(); addFuel()
        assertLabelled("Filled tank", on = true)
        assertLabelled("Missed previous fill-up", on = false)
    }

    @Test fun saveAsFavoriteOnTheManualStationPage() {
        open(); addFuel()
        driver.tapDescription("Find or choose gas station")
        driver.tap("Manual")
        driver.waitFor("Save as favorite")
        assertLabelled("Save as favorite", on = false)
    }

    @Test fun reportsIncludeHiddenVehicles() {
        settings.setVehicleHidden(VehicleId(4), true)
        open()
        driver.tapDescription("Garage reports")
        driver.waitFor("Include 1 hidden vehicle")
        assertLabelled("Include 1 hidden vehicle", on = false)
    }

    @Test fun theWriteLocksCheck() {
        open()
        compose.runOnIdle {
            WriteSafety.start(context, "Edit Service for synthetic car", RecordWriteIntent(WriteAction.EDIT, RecordKind.SERVICE)) {
                throw LubeLoggerEditorApi.WriteUncertainException("Synthetic: the saved record could not be read back.")
            }
        }
        driver.waitFor("I checked LubeLogger and reconciled this write")
        assertLabelled("I checked LubeLogger and reconciled this write", on = false)
    }

    /** The Calendar's chosen day is read as selected, and only that day. */
    @Test fun theCalendarsChosenDayIsSelected() {
        open()
        driver.tap("Calendar")
        driver.waitForDescription("Previous month")
        val format = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)
        val today = LocalDate.now()
        val other = if (today.dayOfMonth == 1) today.plusDays(1) else today.minusDays(1)
        fun day(date: LocalDate) = compose.onNode(hasContentDescription("${date.format(format)}, ", substring = true))
        day(today).assertIsSelected()
        day(other).assertIsNotSelected()
        day(other).performClick(); compose.waitForIdle()
        day(other).assertIsSelected()
        day(today).assertIsNotSelected()
    }
}
