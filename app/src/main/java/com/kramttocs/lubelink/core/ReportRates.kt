package com.kramttocs.lubelink.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Reports' four tiles (OD-52, OD-55). A null value cannot be worked out from the records and is shown as such,
 * never as 0. [hours] is true only for one vehicle LubeLogger counts in hours; otherwise distances are miles.
 */
data class ReportRates(
    val hours: Boolean,
    val costPerDistance: BigDecimal?,
    val costPerDay: BigDecimal?,
    val distance: Long?,
    /** Not rounded: [ReportRateRules.perDay] rounds it once, for display. */
    val distancePerDay: BigDecimal?,
    /** All vehicles only: a vehicle counted in hours is in the report and left out of the mile figures. */
    val hourlyLeftOut: Boolean,
    /** No distance because a reading is lower than an earlier one (OD-61, OD-63), rather than too few readings. */
    val readingsGoDown: Boolean = false
)

object ReportRateRules {
    /** Days the period covers, today included: from its start, or for All time from its first dated record. */
    fun days(period: ReportPeriod, records: List<ServiceRecord>, today: LocalDate): Long? {
        val start = ReportRules.startDate(period, today)
            ?: records.mapNotNull { MaintenanceRules.parseDate(it.date) }.minOrNull() ?: return null
        return ChronoUnit.DAYS.between(start, today) + 1
    }

    /** What a vehicle's readings in the period allow, over the same readings as the odometer chart. */
    private sealed interface Span {
        data object TooFew : Span
        /** A reading is lower than an earlier one (a correction or a reset), anywhere in the period (OD-61). */
        data object GoesDown : Span
        /** The last reading minus the first, every reading at least the one before. */
        data class Gained(val distance: Long) : Span
    }

    /** Readings are in date order, and on one day lowest first (the order within a day is not known), so a drop is
     * any reading below the one before it. */
    private fun span(records: List<ServiceRecord>): Span {
        val points = OdometerChartRules.points(records)
        return when {
            points.size < 2 -> Span.TooFew
            points.zipWithNext().any { (before, after) -> after.reading < before.reading } -> Span.GoesDown
            else -> Span.Gained(points.last().reading - points.first().reading)
        }
    }

    /** A vehicle's distance in the period: what it gained, when above zero, so a distance is never invented. */
    fun distance(records: List<ServiceRecord>): Long? = (span(records) as? Span.Gained)?.distance?.takeIf { it > 0 }

    private fun per(amount: BigDecimal, by: Long): BigDecimal = amount.divide(BigDecimal.valueOf(by), 2, RoundingMode.HALF_UP)

    /**
     * From the expense report's records in the period, for the vehicles it covers ([report] already holds only
     * those). One vehicle: its distance, in its own unit. All vehicles: distance, distance a day and cost per mile
     * add up only the vehicles counted in miles that have a distance (each its own first-to-last), with those
     * vehicles' costs; cost per day covers every vehicle.
     */
    fun rates(report: ReportExpenseResult, vehicles: List<Vehicle>, single: Boolean, period: ReportPeriod,
              today: LocalDate): ReportRates {
        val byId = vehicles.associateBy { it.id }
        val covered = report.vehicles.mapNotNull { byId[it.vehicleId] }
        val records = report.recordsInPeriod
        val days = days(period, records, today)
        val costPerDay = days?.let { per(report.total, it) }
        val counted = if (single) covered else covered.filterNot { it.useHours }
        val spans = counted.map { v -> v to span(records.filter { it.vehicleId == v.id }) }
        val distances = spans.mapNotNull { (v, span) -> (span as? Span.Gained)?.distance?.takeIf { it > 0 }?.let { v to it } }
        val distance = distances.takeIf { it.isNotEmpty() }?.sumOf { it.second }
        val costed = ReportRules.costTotal(records.filter { r -> distances.any { it.first.id == r.vehicleId } })
        return ReportRates(
            hours = single && covered.singleOrNull()?.useHours == true,
            costPerDistance = distance?.let { per(costed, it) },
            costPerDay = costPerDay,
            distance = distance,
            distancePerDay = if (distance != null && days != null)
                BigDecimal.valueOf(distance).divide(BigDecimal.valueOf(days), MathContext.DECIMAL64) else null,
            hourlyLeftOut = !single && covered.any { it.useHours },
            readingsGoDown = distance == null && spans.any { (_, span) -> span == Span.GoesDown })
    }

    const val NOT_ENOUGH = "Not enough readings"
    const val READINGS_GO_DOWN = "Readings go down"
    const val NO_DATED_RECORDS = "No dated records"
    /** Tile values that say why there is no figure; shown in body text, not as a figure. */
    val MESSAGES = setOf(NOT_ENOUGH, READINGS_GO_DOWN, NO_DATED_RECORDS)
    const val READINGS_NOTE = "From the first and last odometer readings in this period."
    const val HOURLY_NOTE = "Miles and cost per mile don't include hourly vehicles."

    /** The four tiles, label and value, in order. */
    fun tiles(r: ReportRates): List<Pair<String, String>> {
        val unit = if (r.hours) "hr" else "mi"
        val noDistance = if (r.readingsGoDown) READINGS_GO_DOWN else NOT_ENOUGH
        return listOf(
            (if (r.hours) "Cost per hour" else "Cost per mile") to (r.costPerDistance?.let(ReportRules::money) ?: noDistance),
            "Cost per day" to (r.costPerDay?.let(ReportRules::money) ?: NO_DATED_RECORDS),
            (if (r.hours) "Hours" else "Distance") to (r.distance?.let { "%,d $unit".format(Locale.US, it) } ?: noDistance),
            "Per day" to (r.distancePerDay?.let { "${perDay(it)} $unit" } ?: noDistance))
    }

    /** Whole units from 10 a day; below that one decimal, without a trailing ".0". */
    fun perDay(value: BigDecimal): String =
        if (value >= BigDecimal.TEN) "%,d".format(Locale.US, value.setScale(0, RoundingMode.HALF_UP).toLong())
        else value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun notes(r: ReportRates): List<String> = listOfNotNull(READINGS_NOTE, HOURLY_NOTE.takeIf { r.hourlyLeftOut })
}
