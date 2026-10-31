package com.kramttocs.lubelink.quality

import com.kramttocs.lubelink.core.HistoryMonthRules
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.VehicleId
import java.math.BigDecimal
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OD-52: History's month headers, from records only (synthetic data). */
class HistoryMonthRulesUnitTest {
    private fun record(id: Int, date: String, cost: String, kind: RecordKind = RecordKind.SERVICE) =
        ServiceRecord(RecordId(id), VehicleId(1), date, "Synthetic $id", 1000L + id, cost, "", "", emptyList(), kind)

    private val october = listOf(record(1, "2026-10-25", "52.10", RecordKind.FUEL), record(2, "2026-10-22", "68.40"),
        record(3, "2026-10-17", "49.75", RecordKind.FUEL))
    private val september = listOf(record(4, "2026-09-28", "50.20", RecordKind.FUEL), record(5, "2026-09-14", "24.99"))
    private val newestFirst = MaintenanceRules.orderedHistory(october + september)

    @Test fun eachMonthHasItsRecordsInTheOrderShownWithTheirTotalAndCount() {
        val months = HistoryMonthRules.months(newestFirst, missingShown = false)
        assertEquals(listOf(YearMonth.of(2026, 10), YearMonth.of(2026, 9)), months.map { it.yearMonth })
        assertEquals(listOf(1, 2, 3), months[0].records.map { it.id.value })
        assertEquals(BigDecimal("170.25"), months[0].total)
        assertEquals("October 2026", HistoryMonthRules.title(months[0]))
        assertEquals("$170.25 · 3 records", HistoryMonthRules.summary(months[0]))
        assertEquals("$75.19 · 2 records", HistoryMonthRules.summary(months[1]))
    }

    @Test fun oldestFirstKeepsTheOrderAndTheSameTotals() {
        val months = HistoryMonthRules.months(newestFirst.asReversed(), missingShown = false)
        assertEquals(listOf(YearMonth.of(2026, 9), YearMonth.of(2026, 10)), months.map { it.yearMonth })
        assertEquals(listOf(5, 4), months[0].records.map { it.id.value })
        assertEquals(BigDecimal("75.19"), months[0].total)
    }

    @Test fun oneRecordIsSingularAndAZeroCostCountsAsZero() {
        val month = HistoryMonthRules.months(listOf(record(7, "2026-08-02", "0")), missingShown = false).single()
        assertEquals("$0.00 · 1 record", HistoryMonthRules.summary(month))
    }

    @Test fun aCostThatCannotBeReadAddsNothingButTheRecordIsCounted() {
        val month = HistoryMonthRules.months(listOf(record(8, "2026-07-02", "12.50"), record(9, "2026-07-01", "n/a")),
            missingShown = false).single()
        assertEquals("$12.50 · 2 records", HistoryMonthRules.summary(month))
    }

    @Test fun aRecordWithoutAReadableDateIsUnderDateUnknown() {
        val months = HistoryMonthRules.months(MaintenanceRules.orderedHistory(listOf(record(10, "2026-06-03", "5"),
            record(11, "not a date", "7"))), missingShown = false)
        assertEquals(listOf(YearMonth.of(2026, 6), null), months.map { it.yearMonth })
        assertEquals("Date unknown", HistoryMonthRules.title(months[1]))
    }

    @Test fun whenATypeTheListShowsDidNotLoadNoTotalIsShown() {
        val months = HistoryMonthRules.months(newestFirst, missingShown = true)
        assertNull(months[0].total)
        assertEquals("3 records", HistoryMonthRules.summary(months[0]))
    }

    @Test fun aMissingTypeMattersOnlyWhenTheFilterShowsIt() {
        val repairMissing = setOf(RecordKind.REPAIR)
        assertTrue("All types shown", HistoryMonthRules.missingShown(repairMissing, null))
        assertTrue("The missing type itself", HistoryMonthRules.missingShown(repairMissing, RecordKind.REPAIR))
        assertFalse("Fuel only: Fuel loaded", HistoryMonthRules.missingShown(repairMissing, RecordKind.FUEL))
        assertFalse("Nothing missing", HistoryMonthRules.missingShown(emptySet(), null))
    }
}
