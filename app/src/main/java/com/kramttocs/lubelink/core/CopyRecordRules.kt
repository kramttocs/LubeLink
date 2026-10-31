package com.kramttocs.lubelink.core

import java.time.LocalDate
import java.math.BigDecimal
import java.math.RoundingMode

/** A local form seed only. Copying never calls an API, preserves an ID, or writes data.
 * Attachments, hidden tags and transaction-specific fuel quantities are deliberately excluded. */
object CopyRecordRules {
    /** Never silently convert a legacy fractional cost to zero. Round only if needed. */
    fun copiedCost(raw: String): String = runCatching {
        val text = raw.trim()
        if (!text.matches(Regex("[0-9]{1,12}(?:\\.[0-9]{1,12})?"))) return@runCatching null
        BigDecimal(text).takeIf { it.signum() >= 0 }
            ?.setScale(3, RoundingMode.HALF_UP)?.stripTrailingZeros()?.toPlainString()
            ?.takeIf { EditorRules.positiveDecimal(it) }
    }.getOrNull() ?: ""

    fun notesTooLongToCopy(source: ServiceRecord): Boolean = source.notes.length > EditorRules.NOTES_MAX

    fun prefill(source: ServiceRecord, currentReading: Long?, today: LocalDate = LocalDate.now()): EditorDraft {
        require(source.id.isValid && source.vehicleId.isValid) { "Invalid source record" }
        val reading = currentReading?.takeIf { it > 0L && it <= 999_999_999L }?.toString().orEmpty()
        return EditorDraft(
            kind = source.kind,
            date = today.toString(),
            odometer = reading,
            description = if (source.kind == RecordKind.FUEL) "" else source.description,
            cost = if (source.kind == RecordKind.FUEL) "" else copiedCost(source.cost),
            // Notes a new record could not save are left out; the form says so.
            notes = if (notesTooLongToCopy(source)) "" else source.notes,
            tags = "", // This field is not editable in the Add form.
            gallons = "", // An old fuel purchase must never be submitted by accident.
            filledTank = true,
            missedFill = false,
            stationName = if (source.kind == RecordKind.FUEL) source.fuelStation else "",
            stationAddress = if (source.kind == RecordKind.FUEL) source.fuelStationAddress else ""
        )
    }
}
