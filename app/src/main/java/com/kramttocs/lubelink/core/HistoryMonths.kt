package com.kramttocs.lubelink.core

import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One month of History (OD-52): its records in the order shown, and what its header says. */
data class HistoryMonth(val yearMonth: YearMonth?, val records: List<ServiceRecord>, val total: BigDecimal?) {
    val count: Int get() = records.size
}

object HistoryMonthRules {
    private val monthName = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)

    /**
     * Splits History's list, already filtered and in the order shown, into months, keeping that order. A record
     * whose date cannot be read goes under "Date unknown" (its own group, wherever it falls). The total is the sum
     * of the costs, as Reports adds them (a record's cost that cannot be read adds nothing); it is null when a record
     * type this list should hold did not load ([missingShown]), so a smaller total is never shown as the month's.
     */
    fun months(shown: List<ServiceRecord>, missingShown: Boolean): List<HistoryMonth> {
        val groups = mutableListOf<Pair<YearMonth?, MutableList<ServiceRecord>>>()
        for (record in shown) {
            val month = MaintenanceRules.parseDate(record.date)?.let(YearMonth::from)
            if (groups.isEmpty() || groups.last().first != month) groups += month to mutableListOf()
            groups.last().second += record
        }
        return groups.map { (month, records) ->
            HistoryMonth(month, records, if (missingShown) null else ReportRules.costTotal(records))
        }
    }

    /** Whether a record type the current filter shows did not load. */
    fun missingShown(missing: Set<RecordKind>, filterKind: RecordKind?): Boolean =
        if (filterKind == null) missing.isNotEmpty() else filterKind in missing

    fun title(month: HistoryMonth): String = month.yearMonth?.format(monthName) ?: "Date unknown"

    /** "$170.25 · 3 records"; without a total, the count only. */
    fun summary(month: HistoryMonth): String {
        val count = if (month.count == 1) "1 record" else "${month.count} records"
        return month.total?.let { "${ReportRules.money(it)} · $count" } ?: count
    }
}
