package com.kramttocs.lubelink.core

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** One reading on Reports' odometer chart (OD-52). */
data class OdometerPoint(val date: LocalDate, val reading: Long, val record: ServiceRecord)

object OdometerChartRules {
    /** The readings of [records] (one vehicle's, in the period), by date: each record with a date and a reading. */
    fun points(records: List<ServiceRecord>): List<OdometerPoint> =
        records.mapNotNull { r ->
            // 0 means "not recorded" (OD-76): LubeLogger records often hold 0 when no reading was entered.
            val reading = r.odometer?.takeIf { it > 0 } ?: return@mapNotNull null
            MaintenanceRules.parseDate(r.date)?.let { OdometerPoint(it, reading, r) }
        }.sortedWith(compareBy<OdometerPoint> { it.date }.thenBy { it.reading })

    /** Where each point sits across the chart, 0 to 1, by its date; evenly when every reading has the same date. */
    fun positions(points: List<OdometerPoint>): List<Float> {
        if (points.size < 2) return points.map { 0.5f }
        val span = ChronoUnit.DAYS.between(points.first().date, points.last().date).toFloat()
        return if (span == 0f) points.indices.map { it / (points.size - 1f) }
        else points.map { ChronoUnit.DAYS.between(points.first().date, it.date) / span }
    }

    /** The point a tap at [x] (0 to 1 across the chart) chooses: the nearest by position. */
    fun nearest(points: List<OdometerPoint>, x: Float): Int? =
        positions(points).withIndex().minByOrNull { kotlin.math.abs(it.value - x) }?.index

    /**
     * The reading TalkBack's Next or Previous action ([by] 1 or -1) chooses after [from]: the first or the last when
     * none is chosen yet, and it stays at either end.
     */
    fun step(count: Int, from: Int?, by: Int): Int =
        if (from == null) (if (by > 0) 0 else count - 1) else (from + by).coerceIn(0, count - 1)

    fun label(point: OdometerPoint, useHours: Boolean): String =
        "${MaintenanceRules.recordDateLabel(point.record.date)} · ${MaintenanceRules.mileageLabel(point.reading, useHours)}"

    /** What TalkBack reads for the chart: how many readings, and the first and the last. */
    fun summary(points: List<OdometerPoint>, useHours: Boolean): String =
        "Odometer chart, ${points.size} readings: from ${label(points.first(), useHours)} to ${label(points.last(), useHours)}"

    const val TAP_HINT = "Tap a point for its date and reading"
    const val NEXT_READING = "Next reading"
    const val PREVIOUS_READING = "Previous reading"
}
