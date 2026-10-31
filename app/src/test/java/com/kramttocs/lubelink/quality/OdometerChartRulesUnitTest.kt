package com.kramttocs.lubelink.quality

import com.kramttocs.lubelink.core.OdometerChartRules
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.VehicleId
import org.junit.Assert.assertEquals
import org.junit.Test

/** OD-52: the odometer chart's readings, positions, taps and words (synthetic data). */
class OdometerChartRulesUnitTest {
    private var nextId = 1
    private fun rec(date: String, odometer: Long?) =
        ServiceRecord(RecordId(nextId++), VehicleId(1), date, "Synthetic", odometer, "1.00", "", "", emptyList(), RecordKind.SERVICE)

    private val records = listOf(rec("2026-10-25", 48_190), rec("2026-03-11", 41_200), rec("2026-06-30", 44_900),
        rec("2026-07-01", null), rec("not a date", 45_000), rec("2026-08-14", 0))

    @Test fun onlyRecordsWithADateAndAReadingArePointsInDateOrder() {
        assertEquals(listOf(41_200L, 44_900L, 48_190L), OdometerChartRules.points(records).map { it.reading })
    }

    @Test fun pointsSitAcrossTheChartByDate() {
        val points = OdometerChartRules.points(records)
        val at = OdometerChartRules.positions(points)
        assertEquals(0f, at.first(), 0f); assertEquals(1f, at.last(), 0f)
        assertEquals(111f / 228f, at[1], 0.0001f)                    // Mar 11 → Jun 30 of Mar 11 → Oct 25
        val sameDay = OdometerChartRules.points(listOf(rec("2026-05-01", 10), rec("2026-05-01", 20), rec("2026-05-01", 30)))
        assertEquals(listOf(0f, 0.5f, 1f), OdometerChartRules.positions(sameDay))
    }

    @Test fun aTapChoosesTheNearestPoint() {
        val points = OdometerChartRules.points(records)
        assertEquals(0, OdometerChartRules.nearest(points, 0.05f))
        assertEquals(1, OdometerChartRules.nearest(points, 0.5f))
        assertEquals(2, OdometerChartRules.nearest(points, 0.97f))
    }

    @Test fun labelsAndTheSpokenSummaryNameDatesAndReadings() {
        val points = OdometerChartRules.points(records)
        assertEquals("Oct 25, 2026 · 48,190 mi", OdometerChartRules.label(points.last(), useHours = false))
        assertEquals("Odometer chart, 3 readings: from Mar 11, 2026 · 41,200 mi to Oct 25, 2026 · 48,190 mi",
            OdometerChartRules.summary(points, useHours = false))
        assertEquals("Mar 11, 2026 · 41,200 hr", OdometerChartRules.label(points.first(), useHours = true))
    }

    @Test fun talkBackStepsFromEitherEndAndStaysThere() {
        assertEquals(0, OdometerChartRules.step(3, null, 1))
        assertEquals(2, OdometerChartRules.step(3, null, -1))
        assertEquals(1, OdometerChartRules.step(3, 0, 1))
        assertEquals(2, OdometerChartRules.step(3, 2, 1))
        assertEquals(0, OdometerChartRules.step(3, 0, -1))
    }
}
