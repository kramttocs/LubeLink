package com.kramttocs.lubelink.quality

import com.kramttocs.lubelink.core.EditConfirmationRules
import com.kramttocs.lubelink.core.EditorDraft
import com.kramttocs.lubelink.core.EditorRules
import com.kramttocs.lubelink.core.FormSessionRules
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.RecordKind
import org.junit.Assert.*
import org.junit.Test

/** Ordinary JVM Gradle tests; real project toolchain required for evidence. */
class EngineeringDomainUnitTest {
    @Test fun editSummaryUsesMeterTypeAndReadableValues() {
        val original = EditorDraft(RecordKind.FUEL, "2026-10-01", "1200", "", "30.00", "", "", "10", true)
        val edited = original.copy(date = "2026-10-02", odometer = "1300", filledTank = false)
        val labels = EditorRules.persistedEditChanges(original, edited).map {
            EditConfirmationRules.changeLabel(it, useHours = true)
        }
        assertTrue(labels.contains("Date: Oct 1, 2026 → Oct 2, 2026"))
        assertTrue(labels.contains("Hours: 1,200 → 1,300"))
        assertTrue(labels.contains("Filled tank: Yes → No"))
        assertFalse(labels.any { it.startsWith("Odometer:") || it.contains("true → false") })
    }

    @Test fun sessionGuardRejectsClosedAndStaleForms() {
        assertTrue(FormSessionRules.accepts(8, 8, true))
        assertFalse(FormSessionRules.accepts(9, 8, true))
        assertFalse(FormSessionRules.accepts(8, 8, false))
    }

    @Test fun noOpEditRecognisesNormalizedDecimalsAndPreservesLiteralNull() {
        val original = EditorDraft(RecordKind.SERVICE, "2026-10-01", "18000", "Oil", "10.00", "null", "")
        assertFalse(EditorRules.hasPersistedEditChanges(original, original.copy(cost = "10.0")))
        assertTrue(EditorRules.hasPersistedEditChanges(original, original.copy(notes = "Changed")))
    }

    /** The edit review and the read-only station line show huge or many-line server values bounded, while
     * the values that would be saved are untouched. */
    @Test fun reviewAndExistingStationShowHugeValuesBounded() {
        val huge = "Huge synthetic description ".repeat(22_500)
        val manyLines = "line\n".repeat(5_000)
        val original = EditorDraft(RecordKind.SERVICE, "2026-10-01", "18000", huge, "10.00", manyLines, "")
        val edited = original.copy(description = "Short", notes = manyLines + "added")
        val changes = EditorRules.persistedEditChanges(original, edited)
        val labels = changes.map { EditConfirmationRules.changeLabel(it, useHours = false) }
        assertEquals(2, labels.size)
        for (label in labels) {
            assertTrue("${label.length} characters shown", label.length <= 2 * 320)
            assertFalse("Line breaks are shown as a mark, not laid out", label.contains('\n'))
            assertTrue("A shortened value is marked", label.contains("…"))
        }
        assertEquals("The saved values are untouched", setOf(huge, manyLines, "Short", manyLines + "added"),
            changes.flatMap { listOf(it.before, it.after) }.toSet())
        val station = EditConfirmationRules.existingStationLabel(huge, "1 Synthetic Rd")
        assertTrue("${station.length} characters shown", station.length <= 360)
        assertTrue(station.endsWith("· 1 Synthetic Rd"))
        assertEquals("Existing station: Hilltop Gas · 22 Ridge Rd",
            EditConfirmationRules.existingStationLabel("Hilltop Gas", "22 Ridge Rd"))
    }

    /** OD-37: fuel figures with at most two decimals; record dates like Reminders'. */
    @Test fun fuelFiguresAndRecordDatesFollowOd37() {
        assertEquals("29.4 mpg", MaintenanceRules.mpgLabel(29.4))
        assertEquals("13.25 gal", MaintenanceRules.gallonsLabel(13.249))
        assertEquals("30 mpg", MaintenanceRules.mpgLabel(30.0))
        assertEquals("0.01 gal", MaintenanceRules.gallonsLabel(0.005))
        assertEquals(null, MaintenanceRules.mpgLabel(0.0))
        assertEquals("Oct 20, 2026", MaintenanceRules.recordDateLabel("2026-10-20"))
        assertEquals("Oct 20, 2026", MaintenanceRules.recordDateLabel("2026-10-20T00:00:00"))
        assertEquals("Shown as sent when unreadable", "sometime", MaintenanceRules.recordDateLabel("sometime"))
    }
}
