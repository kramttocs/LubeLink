package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** OD-31 at the real screens: when one record type fails to load, History and Calendar show the types that
 * loaded with a notice naming the missing one; Reports shows no totals until every type loads; Retry reads again.
 * Whole app against the synthetic garage, with Repair records failing. Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class RecordGapUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    @Volatile private var repairFails = true
    private val server = ScreenshotServer(fails = { path -> repairFails && path.startsWith("/api/vehicle/repairrecords") })
    private val driver = TourDriver(compose) {}
    private val notice = "Repair records couldn't be loaded"

    @Before fun connect() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }
        driver.waitFor("2019 Honda CR-V")
    }

    @After fun cleanUp() {
        settings.disconnect()
        server.close()
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test fun aVehiclesHistoryShowsTheTypesThatLoadedAndNamesTheMissingOne() {
        driver.tap("2019 Honda CR-V")
        driver.tap("History")
        driver.waitFor("Oil and filter change")
        driver.waitFor(notice, substring = true)
        assertTrue("A repair must not be shown while Repair records are missing", !shows("Replace rear wiper motor"))
        repairFails = false
        driver.tap("Retry")
        // The CR-V's oldest record, below the screen's end since History has a reminders-due panel (OD-49).
        driver.scrollToText("Replace rear wiper motor")
        driver.waitForGone(notice)
    }

    /** The Calendar's Retry is tapped and its effect checked. The synthetic garage's only record 14 days ago is a
     * repair (the Ford's brake pads), so that day has recorded maintenance only once Repair records load. */
    @Test fun theCalendarNamesTheMissingTypeAndRetryLoadsIt() {
        driver.tap("Calendar")
        driver.waitFor(notice, substring = true)
        driver.waitForDescription("Previous month")
        val repairDay = java.time.LocalDate.now().minusDays(14)
        if (repairDay.month != java.time.LocalDate.now().month) driver.tapDescription("Previous month")
        val day = repairDay.format(java.time.format.DateTimeFormatter.ofPattern("MMM d, uuuu", java.util.Locale.US)) + ","
        fun dayDescription() = compose.onAllNodes(hasContentDescription(day, substring = true)).fetchSemanticsNodes()
            .single().config[androidx.compose.ui.semantics.SemanticsProperties.ContentDescription].joinToString()
        assertTrue("Without Repair records the day shows none: ${dayDescription()}",
            !dayDescription().contains("recorded maintenance"))
        repairFails = false
        driver.tap("Retry")
        driver.waitForGone(notice)
        compose.awaitCondition(20_000) { dayDescription().contains("recorded maintenance") }
        compose.onNode(hasContentDescription(day, substring = true)).performClick()
        driver.waitFor("Front brake pads and rotors")
    }

    @Test fun reportsShowNoTotalsUntilEveryTypeLoads() {
        driver.tapDescription("Garage reports")
        driver.waitFor("reports aren't shown", substring = true)
        assertTrue("No total may be shown over some record types", !shows("Total recorded expenses"))
        repairFails = false
        driver.tap("Retry")
        driver.waitFor("Total recorded expenses")
    }
}
