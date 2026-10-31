package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.ReportRateRules
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-52 and OD-55: Reports' four tiles through the whole app against the synthetic garage server, for the default
 * period (12 months). The expected values are worked out here from the garage's own records: All vehicles adds the
 * miles of the vehicles counted in miles that have two readings (the CR-V and the F-150; the Ioniq has one), with
 * their costs, and cost per day over every vehicle, and says hourly vehicles are left out (the Kubota). One vehicle
 * shows its own figures; the Kubota's are in hours, and its later reading is lower than its earlier one, so it has
 * no distance and its tiles say "Readings go down" (OD-61). Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class ReportTilesUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private var server: ScreenshotServer? = null
    private val driver = TourDriver(compose) {}
    private val today = LocalDate.now()
    private val days = ChronoUnit.DAYS.between(YearMonth.from(today).minusMonths(11).atDay(1), today) + 1

    @After fun cleanUp() {
        settings.removeManagerKey()
        settings.disconnect()
        server?.close()
    }

    private fun open(extra: List<ScreenshotGarage.Rec> = emptyList()) {
        server = ScreenshotServer()
        ScreenshotGarage.startFresh(context)
        ScreenshotGarage.extraRecords = extra
        settings.baseUrl = server!!.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }
        driver.waitFor("2019 Honda CR-V")
    }

    private fun waitForReport() { driver.waitFor("Total recorded expenses"); driver.waitForGone("Calculating") }
    private fun shows(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
    private fun money(v: BigDecimal) = "$" + String.format(Locale.US, "%,.2f", v.setScale(2, RoundingMode.HALF_UP))
    private fun cost(car: Int) = ScreenshotGarage.served().filter { it.car == car }.sumOf { BigDecimal.valueOf(it.cost) }

    /** A vehicle's readings in date order (oldest first; on one day, lowest first). */
    private fun readings(car: Int) = ScreenshotGarage.served().filter { it.car == car }
        .sortedWith(compareByDescending<ScreenshotGarage.Rec> { it.daysAgo }.thenBy { it.odometer }).map { it.odometer.toLong() }

    /** OD-61: some reading is lower than an earlier one (any pair, not only the ends). */
    private fun goesDown(car: Int): Boolean = readings(car).let { r -> r.indices.any { j -> (0 until j).any { i -> r[j] < r[i] } } }

    /** First-to-last distance by date, or null as the tiles have it: fewer than two readings, none gained, or a drop. */
    private fun distance(car: Int): Long? {
        val r = readings(car)
        return if (r.size < 2 || goesDown(car)) null else (r.last() - r.first()).takeIf { it > 0 }
    }

    /** One tile (a merged node) holds both the label and the value, so a value shown in another tile never passes. */
    private fun assertTile(label: String, value: String) =
        assertTrue("Tile \"$label\" reads \"$value\"",
            compose.onAllNodes(hasText(label) and hasText(value)).fetchSemanticsNodes().isNotEmpty())

    @Test fun allVehiclesAddTheMileageVehiclesAndSayHourlyOnesAreLeftOut() {
        open()
        driver.tapDescription("Garage reports")
        waitForReport()
        val counted = listOf(1, 2, 3).filter { distance(it) != null }
        assertTrue("Two vehicles counted in miles have a distance", counted == listOf(1, 2))
        val miles = counted.sumOf { distance(it)!! }
        val perMile = counted.sumOf { cost(it) }.divide(BigDecimal.valueOf(miles), 2, RoundingMode.HALF_UP)
        val perDay = ScreenshotGarage.records.sumOf { BigDecimal.valueOf(it.cost) }.divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP)
        assertTile("Cost per mile", money(perMile))
        assertTile("Cost per day", money(perDay))
        assertTile("Distance", String.format(Locale.US, "%,d mi", miles))
        assertTile("Per day", "${(miles.toDouble() / days).let { if (it >= 10) Math.round(it).toString() else "%.1f".format(Locale.US, it) }} mi")
        assertTrue("The hourly line shows", shows("Miles and cost per mile don't include hourly vehicles."))
    }

    /**
     * OD-61, OD-63: a reading below an earlier one between the first and the last (a correction recorded
     * later; the last reading is still above the first) makes that vehicle's distance tiles say "Readings go down",
     * and All vehicles leaves it out of the miles.
     */
    @Test fun aDropBetweenTheFirstAndLastReadingsIsReadingsGoDown() {
        val dip = ScreenshotGarage.Rec("servicerecords", 50, 1, 100, 40_000, "Synthetic odometer correction", 0.0)
        open(listOf(dip))
        assertTrue("The CR-V's last reading is still above its first", readings(1).last() > readings(1).first())
        assertTrue(goesDown(1))
        driver.tap("2019 Honda CR-V")
        driver.tapDescription("Vehicle reports")
        waitForReport()
        assertTile("Cost per mile", ReportRateRules.READINGS_GO_DOWN)
        assertTile("Distance", ReportRateRules.READINGS_GO_DOWN)
        assertTile("Per day", ReportRateRules.READINGS_GO_DOWN)
        assertTile("Cost per day", money(cost(1).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP)))
        driver.tap("Overview"); driver.tap("Garage")
        driver.tapDescription("Garage reports")
        waitForReport()
        assertTile("Distance", String.format(Locale.US, "%,d mi", distance(2)!!))
    }

    @Test fun oneVehicleShowsItsOwnFigures() {
        open()
        driver.tap("2019 Honda CR-V")
        driver.tapDescription("Vehicle reports")
        waitForReport()
        val miles = distance(1)!!
        assertTile("Cost per mile", money(cost(1).divide(BigDecimal.valueOf(miles), 2, RoundingMode.HALF_UP)))
        assertTile("Cost per day", money(cost(1).divide(BigDecimal.valueOf(days), 2, RoundingMode.HALF_UP)))
        assertTile("Distance", String.format(Locale.US, "%,d mi", miles))
        assertFalse("One vehicle: no hourly line", shows("Miles and cost per mile don't include hourly vehicles."))
    }

    @Test fun anHourlyVehicleIsInHoursAndALowerLaterReadingIsNoDistance() {
        open()
        driver.tap("2012 Kubota L3301")
        driver.tapDescription("Vehicle reports")
        waitForReport()
        assertTrue(shows("Cost per hour")); assertTrue(shows("Hours"))
        assertFalse("No mile tiles for an hourly vehicle", shows("Cost per mile"))
        assertTrue("Its readings go down, so no distance is invented and the tiles say why (OD-61)",
            shows(ReportRateRules.READINGS_GO_DOWN))
        assertFalse(shows(ReportRateRules.NOT_ENOUGH))
    }

    /**
     * The tiles never mix a new choice with the report of the old one: frame by frame after choosing one vehicle, no
     * frame shows All vehicles' added miles without All vehicles' hourly line (a stale report read as one vehicle's).
     */
    @Test fun noFrameShowsTheOldReportWithTheNewChoice() {
        open()
        driver.tapDescription("Garage reports")
        waitForReport()
        val allMiles = String.format(Locale.US, "%,d mi", listOf(1, 2).sumOf { distance(it)!! })
        assertTrue(shows(allMiles))
        driver.tap("All vehicles")
        driver.waitFor("2019 Honda CR-V")
        compose.mainClock.autoAdvance = false
        try {
            compose.onAllNodes(hasText("2019 Honda CR-V")).onLast().performClick()
            repeat(120) {
                compose.mainClock.advanceTimeByFrame()
                assertFalse("Frame $it: All vehicles' miles without its hourly line",
                    shows(allMiles) && !shows(ReportRateRules.HOURLY_NOTE))
            }
            compose.mainClock.autoAdvance = true
            driver.waitFor(String.format(Locale.US, "%,d mi", distance(1)!!))
        } finally { compose.mainClock.autoAdvance = true }
    }
}
