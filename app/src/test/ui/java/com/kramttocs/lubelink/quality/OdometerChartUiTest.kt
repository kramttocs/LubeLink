package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-52: the odometer chart on Reports, for one vehicle, through the whole app against the synthetic garage
 * server. TalkBack's summary names the count and the first and last readings, worked out here from the garage's own
 * records; tapping the chart's right and left ends shows the last and the first reading; TalkBack's Next and Previous
 * reading actions step through them; All vehicles has no chart.
 * Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class OdometerChartUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private var server: ScreenshotServer? = null
    private val driver = TourDriver(compose) {}
    private val dateLabel = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    private val chart = hasContentDescription("Odometer chart", substring = true)

    @After fun cleanUp() {
        settings.removeManagerKey()
        settings.disconnect()
        server?.close()
    }

    private fun open() {
        server = ScreenshotServer()
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server!!.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }
        driver.waitFor("2019 Honda CR-V")
    }

    private fun crvReports() {
        driver.tap("2019 Honda CR-V")
        driver.tapDescription("Vehicle reports")
        driver.waitFor("Total recorded expenses"); driver.waitForGone("Calculating")
    }

    private fun label(r: ScreenshotGarage.Rec) =
        "${LocalDate.now().minusDays(r.daysAgo).format(dateLabel)} · ${String.format(Locale.US, "%,d", r.odometer)} mi"

    private val crv = ScreenshotGarage.records.filter { it.car == 1 }.sortedByDescending { it.daysAgo }

    private fun scrollToChart() {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(chart)
        compose.waitForIdle()
    }

    private fun tapAt(fraction: Float) {
        compose.onNode(chart).performTouchInput { click(Offset(width * fraction, height / 2f)) }
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    @Test fun theChartSummarySaysHowManyReadingsAndTheFirstAndLast() {
        open(); crvReports(); scrollToChart()
        val spoken = compose.onNode(chart).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].single()
        assertEquals("Odometer chart, ${crv.size} readings: from ${label(crv.first())} to ${label(crv.last())}", spoken)
    }

    @Test fun tappingTheEndsShowsTheLastAndTheFirstReading() {
        open(); crvReports(); scrollToChart()
        assertTrue("Before a tap, the hint shows", shows("Tap a point for its date and reading"))
        tapAt(0.99f)
        assertTrue("The right end is the latest reading: ${label(crv.last())}", shows(label(crv.last())))
        tapAt(0.01f)
        assertTrue("The left end is the first reading: ${label(crv.first())}", shows(label(crv.first())))
    }

    /** Performs the chart's TalkBack action named [action], as TalkBack does (by its label). The node is read on the
     * test thread; only the action runs on the main thread (reading inside runOnIdle fails on a device). */
    private fun talkBack(action: String) {
        val found = compose.onNode(chart).fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == action }
        compose.runOnIdle { found.action() }
        compose.waitForIdle()
    }

    private fun spokenState() = compose.onNode(chart).fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)

    /** TalkBack cannot tap a point: its Next and Previous reading actions step through them, and each is spoken. */
    @Test fun talkBackStepsThroughTheReadings() {
        open(); crvReports(); scrollToChart()
        talkBack("Next reading")
        assertEquals(label(crv.first()), spokenState()); assertTrue(shows(label(crv.first())))
        talkBack("Next reading")
        assertEquals(label(crv[1]), spokenState())
        talkBack("Previous reading"); talkBack("Previous reading")
        assertEquals("It stays at the first", label(crv.first()), spokenState())
    }

    @Test fun allVehiclesHaveNoChart() {
        open()
        driver.tapDescription("Garage reports")
        driver.waitFor("Total recorded expenses"); driver.waitForGone("Calculating")
        assertFalse(compose.onAllNodes(chart).fetchSemanticsNodes().isNotEmpty())
        assertFalse(shows("Odometer in this period"))
    }
}
