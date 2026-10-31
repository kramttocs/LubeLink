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
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-52: History shows a header above each month, with the month's total and record count, through the whole app
 * against the synthetic garage server. The expected months and totals are worked out here from the garage's own
 * records, not with the app's rules: every month of the CR-V's records, the Fuel filter's months, and no total while
 * a record type the list shows did not load. Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class HistoryMonthsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private var server: ScreenshotServer? = null
    private val driver = TourDriver(compose) {}
    private val monthName = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    @After fun cleanUp() {
        settings.removeManagerKey()
        settings.disconnect()
        server?.close()
    }

    private fun openCrvHistory(running: ScreenshotServer = ScreenshotServer()) {
        server = running
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = running.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }
        driver.waitFor("2019 Honda CR-V")
        driver.tap("2019 Honda CR-V")
        driver.waitFor("Add record")
        driver.tap("History")
        driver.waitFor("Oil and filter change")
    }

    /** Month title -> (total, count) for the CR-V's records of [kinds], from the garage's data. */
    private fun expected(kinds: Set<String>): Map<String, Pair<String, Int>> =
        ScreenshotGarage.records.filter { it.car == 1 && it.kind in kinds }
            .groupBy { YearMonth.from(LocalDate.now().minusDays(it.daysAgo)) }
            .entries.associate { (month, rows) ->
                month.format(monthName) to ("$" + String.format(Locale.US, "%,.2f", rows.sumOf { it.cost }) to rows.size)
            }

    private fun countText(n: Int) = if (n == 1) "1 record" else "$n records"
    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    private fun assertHeaders(months: Map<String, Pair<String, Int>>, withTotals: Boolean) {
        for ((title, value) in months) {
            driver.scrollToText(title)
            val (total, count) = value
            val summary = if (withTotals) "$total · ${countText(count)}" else countText(count)
            assertTrue("$title's header reads \"$summary\"", shows(summary))
        }
    }

    @Test fun everyMonthHasAHeaderWithItsTotalAndCount() {
        openCrvHistory()
        val months = expected(setOf("servicerecords", "repairrecords", "upgraderecords", "gasrecords"))
        assertTrue("The CR-V's records span more than one month", months.size > 1)
        assertHeaders(months, withTotals = true)
    }

    @Test fun theFuelFilterTotalsOnlyFuel() {
        openCrvHistory()
        driver.tap("Fuel")
        driver.waitFor("29.4", substring = true)
        assertHeaders(expected(setOf("gasrecords")), withTotals = true)
    }

    @Test fun noTotalIsShownWhileARecordTypeDidNotLoad() {
        openCrvHistory(ScreenshotServer(fails = { it.startsWith("/api/vehicle/repairrecords") }))
        val loaded = expected(setOf("servicerecords", "upgraderecords", "gasrecords"))
        assertHeaders(loaded, withTotals = false)
        assertFalse("No month total while Repair is missing",
            compose.onAllNodes(hasText("$ ·", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                compose.onAllNodes(hasText(" · 1 record", substring = true)).fetchSemanticsNodes().isNotEmpty() ||
                loaded.values.any { (total, _) -> compose.onAllNodes(hasText("$total · ", substring = true)).fetchSemanticsNodes().isNotEmpty() })
    }

    @Test fun aFilterWithoutTheMissingTypeKeepsItsTotals() {
        openCrvHistory(ScreenshotServer(fails = { it.startsWith("/api/vehicle/repairrecords") }))
        driver.tap("Fuel")
        driver.waitFor("29.4", substring = true)
        assertHeaders(expected(setOf("gasrecords")), withTotals = true)
    }
}
