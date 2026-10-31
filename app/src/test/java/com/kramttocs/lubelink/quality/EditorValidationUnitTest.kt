package com.kramttocs.lubelink.quality

import com.kramttocs.lubelink.core.EditorDraft
import com.kramttocs.lubelink.core.EditorRules
import com.kramttocs.lubelink.core.RecordKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The form's checks before a save (synthetic data): each limit applies to what the record type shows and sends. */
class EditorValidationUnitTest {
    private val long = "x".repeat(EditorRules.DESCRIPTION_MAX + 1)
    private val service = EditorDraft(RecordKind.SERVICE, "2026-10-12", "1200", "Inspection", "10.00", "", "")

    @Test fun aServiceDescriptionOverTheLimitIsRefused() {
        assertEquals("Description or notes are too long.", EditorRules.validate(service.copy(description = long)))
        assertNull(EditorRules.validate(service.copy(description = "x".repeat(EditorRules.DESCRIPTION_MAX))))
    }

    /** A fuel record has no description in LubeLogger, so text left from another type never blocks its save. */
    @Test fun aFuelRecordIsNotBlockedByADescriptionItDoesNotHave() {
        val fuel = service.copy(kind = RecordKind.FUEL, description = long, gallons = "12.5")
        assertNull(EditorRules.validate(fuel))
    }

    @Test fun anUnchangedLongDescriptionFromLubeLoggerDoesNotBlockAnEdit() {
        val original = service.copy(description = long)
        assertNull(EditorRules.validate(original.copy(cost = "11.00"), original))
    }
}
