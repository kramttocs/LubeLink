package com.kramttocs.lubelink.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth

/** One read-only dataset powers Expenses, Fuel and Maintenance on demand.
 * No reports are fetched or calculated by normal Garage/History navigation. */
data class ReportSnapshot(val records: List<ServiceRecord>, val generation: Int,
                          val loadedAtMillis: Long) {
    fun isFresh(currentGeneration: Int, nowMillis: Long): Boolean =
        generation == currentGeneration && (nowMillis - loadedAtMillis) in 0..(10 * 60 * 1000L)
}

enum class ReportSection { EXPENSES, FUEL, MAINTENANCE }
enum class ReportPeriod { LAST_TWELVE_MONTHS, THIS_YEAR, ALL_TIME }

/** Specific vehicle id, or null for all accessible vehicles (optionally including locally hidden ones). */
data class ReportScope(val vehicleId: VehicleId? = null, val includeHidden: Boolean = false)

data class ReportDataset(val records: List<ServiceRecord>) {
    val fuelRecords: List<ServiceRecord> get() = records.filter { it.kind == RecordKind.FUEL }
    val maintenanceRecords: List<ServiceRecord> get() = records.filter { it.kind != RecordKind.FUEL }
}

data class ReportMoneyGroup(val name: String, val total: BigDecimal, val records: List<ServiceRecord>)
/** Per-vehicle totals carry the vehicle's ID; the UI looks up its current label. */
data class ReportVehicleGroup(val vehicleId: VehicleId, val total: BigDecimal, val records: List<ServiceRecord>)
data class ReportMonthGroup(val month: YearMonth, val total: BigDecimal, val records: List<ServiceRecord>)
data class ReportExpenseResult(
    val total: BigDecimal,
    val validCostRecords: Int,
    val missingCostRecords: Int,
    val undatedRecords: Int,
    val recordsInPeriod: List<ServiceRecord>,
    val categories: List<ReportMoneyGroup>,
    val months: List<ReportMonthGroup>,
    val vehicles: List<ReportVehicleGroup>
)

/** Pure Kotlin and Android-free. Decimal math avoids floating-point expense drift. */
object ReportRules {
    val ZERO: BigDecimal = BigDecimal.ZERO.setScale(2)

    fun startDate(period: ReportPeriod, today: LocalDate): LocalDate? = when (period) {
        ReportPeriod.LAST_TWELVE_MONTHS -> YearMonth.from(today).minusMonths(11).atDay(1)
        ReportPeriod.THIS_YEAR -> LocalDate.of(today.year, 1, 1)
        ReportPeriod.ALL_TIME -> null
    }

    /** One row's cost, rounded to cents for display. Never added up: totals use [moneyTotal] (OD-77). */
    fun readCost(value: String): BigDecimal? = exactCost(value)?.setScale(2, RoundingMode.HALF_UP)

    /** The one way a report adds money: the exact costs, rounded to cents once (OD-77). */
    fun moneyTotal(exactCosts: Iterable<BigDecimal>): BigDecimal =
        exactCosts.fold(BigDecimal.ZERO) { total, cost -> total + cost }.setScale(2, RoundingMode.HALF_UP)

    /** The cost as LubeLogger stores it. Totals add these and round once (OD-77). */
    fun exactCost(value: String): BigDecimal? {
        val raw = value.trim()
        // LubeLogger returns invariant JSON decimal values, not localized or formatted money.
        if (!raw.matches(Regex("[0-9]{1,12}(?:\\.[0-9]{1,8})?"))) return null
        return runCatching { BigDecimal(raw) }.getOrNull()?.takeIf { it >= BigDecimal.ZERO }
    }

    /** The records' costs added up; a cost that cannot be read adds nothing. */
    fun costTotal(records: List<ServiceRecord>): BigDecimal = records.sumOfMoney { exactCost(it.cost) }

    fun money(value: BigDecimal): String = "\$" + String.format(java.util.Locale.US, "%,.2f", value)

    fun expenses(
        dataset: ReportDataset,
        vehicles: List<Vehicle>,
        visibleIds: Set<VehicleId>,
        scope: ReportScope,
        period: ReportPeriod,
        today: LocalDate
    ): ReportExpenseResult {
        val allowed = vehicles.map { it.id }.toSet()
        val included = if (scope.includeHidden) allowed else allowed.intersect(visibleIds)
        val includedScope = scope.vehicleId?.let { setOf(it).intersect(included) } ?: included
        val start = startDate(period, today)
        val dated = dataset.records.asSequence()
            .filter { it.vehicleId in includedScope }
            .map { it to MaintenanceRules.parseDate(it.date) }
            .toList()
        val missingDate = dated.count { it.second == null }
        val matching = dated.filter { (_, date) -> date != null && !date.isAfter(today) && (start == null || !date.isBefore(start)) }
            .map { it.first }
        val costed = matching.mapNotNull { record -> exactCost(record.cost)?.let { record to it } }
        val uncosted = matching.size - costed.size
        fun sum(rows: List<Pair<ServiceRecord, BigDecimal>>): BigDecimal = moneyTotal(rows.map { it.second })
        val kinds = RecordKind.entries.map { kind ->
            val group = costed.filter { it.first.kind == kind }
            ReportMoneyGroup(kind.label, sum(group), matching.filter { it.kind == kind })
        }
        val byVehicle = vehicles.filter { it.id in includedScope }.map { vehicle ->
            val group = costed.filter { it.first.vehicleId == vehicle.id }
            ReportVehicleGroup(vehicle.id, sum(group), matching.filter { it.vehicleId == vehicle.id })
        }.sortedByDescending { it.total }
        val months = when (period) {
            ReportPeriod.LAST_TWELVE_MONTHS -> (0L..11L).map { YearMonth.from(today).minusMonths(11L - it) }
            ReportPeriod.THIS_YEAR -> (1..today.monthValue).map { YearMonth.of(today.year, it) }
            ReportPeriod.ALL_TIME -> matching.mapNotNull { MaintenanceRules.parseDate(it.date)?.let(YearMonth::from) }
                .distinct().sorted()
        }.map { month ->
            val group = costed.filter { (record, _) ->
                MaintenanceRules.parseDate(record.date)?.let(YearMonth::from) == month
            }
            ReportMonthGroup(month, sum(group), matching.filter { MaintenanceRules.parseDate(it.date)?.let(YearMonth::from) == month })
        }
        return ReportExpenseResult(sum(costed), costed.size, uncosted, missingDate,
            MaintenanceRules.orderedHistory(matching), kinds, months, byVehicle)
    }
}

/** Fuel reporting uses LubeLogger's server-computed per-fill MPG values. It never
 * estimates MPG from adjacent odometers or averages dissimilar vehicles. */
data class ReportFuelResult(
    val records: List<ServiceRecord>,
    val totalSpent: BigDecimal,
    val purchasedGallons: BigDecimal,
    val pricedGallons: BigDecimal,
    val paidForPricedGallons: BigDecimal,
    val missingCostRecords: Int,
    val missingGallonsRecords: Int,
    val months: List<ReportMonthGroup>,
    val stations: List<ReportMoneyGroup>,
    val mpgSamples: List<ServiceRecord>
) {
    val averagePaidPerGallon: BigDecimal? get() =
        paidForPricedGallons.takeIf { pricedGallons > BigDecimal.ZERO }
            ?.divide(pricedGallons, 2, RoundingMode.HALF_UP)
}

data class ReportMaintenanceResult(
    val records: List<ServiceRecord>,
    val totalSpent: BigDecimal,
    val missingCostRecords: Int,
    val categories: List<ReportMoneyGroup>,
    val months: List<ReportMonthGroup>
)

/** Shared record filtering is reused by all report sections and future CSV/PDF exports. */
fun ReportRules.recordsFor(
    dataset: ReportDataset, vehicles: List<Vehicle>, visibleIds: Set<VehicleId>,
    scope: ReportScope, period: ReportPeriod, today: LocalDate
): List<ServiceRecord> {
    val knownIds = vehicles.mapTo(mutableSetOf()) { it.id }
    val allowed = if (scope.includeHidden) knownIds else knownIds.intersect(visibleIds)
    val scoped = scope.vehicleId?.let { setOf(it).intersect(allowed) } ?: allowed
    val start = startDate(period, today)
    return MaintenanceRules.orderedHistory(dataset.records.filter { record ->
        if (record.vehicleId !in scoped) return@filter false
        val date = MaintenanceRules.parseDate(record.date) ?: return@filter false
        !date.isAfter(today) && (start == null || !date.isBefore(start))
    })
}

fun ReportRules.fuel(
    dataset: ReportDataset, vehicles: List<Vehicle>, visibleIds: Set<VehicleId>,
    scope: ReportScope, period: ReportPeriod, today: LocalDate
): ReportFuelResult {
    val records = recordsFor(dataset, vehicles, visibleIds, scope, period, today)
        .filter { it.kind == RecordKind.FUEL }
    val costs = records.mapNotNull { record -> exactCost(record.cost)?.let { record to it } }
    val gallons = records.mapNotNull { record ->
        record.fuelConsumed?.takeIf { it.isFinite() && it > 0 && it < 1_000_000 }
            ?.let { record to BigDecimal.valueOf(it) }
    }
    val priced = gallons.mapNotNull { (record, volume) ->
        exactCost(record.cost)?.let { Triple(record, volume, it) }
    }
    val monthly = records.groupBy { YearMonth.from(MaintenanceRules.parseDate(it.date)!!) }
        .entries.sortedByDescending { it.key }.map { (month, rows) ->
            ReportMonthGroup(month, rows.sumOfMoney { exactCost(it.cost) }, rows)
        }
    val stations = records.groupBy { it.fuelStation.trim().ifBlank { "Station not recorded" } }
        .map { (name, rows) -> ReportMoneyGroup(name, rows.sumOfMoney { exactCost(it.cost) }, rows) }
        .sortedByDescending { it.total }
    return ReportFuelResult(records, moneyTotal(costs.map { it.second }),
        gallons.fold(BigDecimal.ZERO) { sum, (_, amount) -> sum + amount },
        priced.fold(BigDecimal.ZERO) { sum, (_, volume, _) -> sum + volume },
        moneyTotal(priced.map { it.third }),
        records.size - costs.size, records.size - gallons.size,
        monthly, stations, records.filter {
            it.fuelEconomy?.let { mpg -> mpg.isFinite() && mpg > 0 && mpg < 500 } == true
        })
}

fun ReportRules.maintenance(
    dataset: ReportDataset, vehicles: List<Vehicle>, visibleIds: Set<VehicleId>,
    scope: ReportScope, period: ReportPeriod, today: LocalDate
): ReportMaintenanceResult {
    val records = recordsFor(dataset, vehicles, visibleIds, scope, period, today)
        .filter { it.kind != RecordKind.FUEL }
    val types = listOf(RecordKind.SERVICE, RecordKind.REPAIR, RecordKind.UPGRADE)
        .map { kind ->
            val rows = records.filter { it.kind == kind }
            ReportMoneyGroup(kind.label, rows.sumOfMoney { exactCost(it.cost) }, rows)
        }
    val months = records.groupBy { YearMonth.from(MaintenanceRules.parseDate(it.date)!!) }
        .entries.sortedByDescending { it.key }.map { (month, rows) ->
            ReportMonthGroup(month, rows.sumOfMoney { exactCost(it.cost) }, rows)
        }
    return ReportMaintenanceResult(records, records.sumOfMoney { exactCost(it.cost) },
        records.count { exactCost(it.cost) == null }, types, months)
}

private fun List<ServiceRecord>.sumOfMoney(getCost: (ServiceRecord) -> BigDecimal?): BigDecimal =
    ReportRules.moneyTotal(mapNotNull(getCost))
