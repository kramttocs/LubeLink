package com.kramttocs.lubelink.core

import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ordinary Editor requests; recurring reminders are handled separately in LubeLogger. */
data class EditorDraft(
    val kind: RecordKind,
    val date: String,
    val odometer: String,
    val description: String,
    val cost: String,
    val notes: String,
    val tags: String,
    val gallons: String = "",
    val filledTank: Boolean = true,
    val missedFill: Boolean = false,
    val stationName: String = "",
    val stationAddress: String = ""
)

object EditorRules {
    /** Save limits, also applied while typing so the form's saved state stays bounded. */
    const val NOTES_MAX = 5000
    const val STATION_NAME_MAX = 100
    const val STATION_ADDRESS_MAX = 240
    /** Checked at Save only, for the types that have a description (not fuel). */
    const val DESCRIPTION_MAX = 250
    /** Odometer, cost and quantity: far longer than any valid value, so valid entry is unaffected. */
    const val NUMBER_ENTRY_MAX = 32
    /** Tags are not editable in LubeLink; this only bounds what is kept from the server. */
    const val TAGS_SAVED_MAX = 1000

    /**
     * Entry-time limit for a text field. Text up to [max] is accepted as typed; a paste that would
     * go past it is cut at [max]. A value already longer than [max] (only possible when prefilled
     * from LubeLogger) may be shortened but not lengthened.
     */
    fun limitEntry(current: String, proposed: String, max: Int): String = when {
        proposed.length <= max || proposed.length <= current.length -> proposed
        current.length > max -> current
        else -> proposed.take(max)
    }

    /** The v1.7.3 API exposes a computed value, not editable charge energy for EVs. */
    fun unsupportedChargingWrite(vehicle: Vehicle, kind: RecordKind): Boolean =
        vehicle.isElectric && kind == RecordKind.FUEL

    /** An async GasRecord field check can finish after any Add form opens or changes
     * type. Don't trap a new Fuel entry behind hidden, unsaveable station values.
     * Existing records must never have their server metadata cleared here. */
    internal fun shouldClearUnavailableNewFuelStation(
        kind: RecordKind, isEdit: Boolean, readiness: FieldReadiness,
        name: String, address: String
    ): Boolean = kind == RecordKind.FUEL && !isEdit && readiness == FieldReadiness.MISSING &&
        (name.isNotBlank() || address.isNotBlank())

    /** UI preview and no-op validation share exactly the same persisted-field semantics. */
    enum class PersistedField(val label: String) {
        DATE("Date"), READING("Odometer"), COST("Cost"), NOTES("Notes"), TAGS("Tags"),
        GALLONS("Gallons"), FILLED("Filled tank"), MISSED("Missed fill-up"),
        STATION("Station"), ADDRESS("Station address"), DESCRIPTION("Description")
    }
    data class PersistedChange(val code: PersistedField, val before: String, val after: String) {
        val label: String get() = code.label
    }

    fun persistedEditChanges(original: EditorDraft, proposed: EditorDraft): List<PersistedChange> {
        require(original.kind == proposed.kind) { "Record kind cannot change during Edit" }
        val changes = mutableListOf<PersistedChange>()
        fun changed(field: PersistedField, before: String, after: String) {
            if (before.trim() != after.trim()) changes += PersistedChange(field, before, after)
        }
        fun decimalChanged(field: PersistedField, before: String, after: String) {
            val a = before.trim().toBigDecimalOrNull()
            val b = after.trim().toBigDecimalOrNull()
            if (if (a != null && b != null) a.compareTo(b) != 0 else before != after)
                changes += PersistedChange(field, before, after)
        }
        changed(PersistedField.DATE, original.date, proposed.date)
        changed(PersistedField.READING, original.odometer, proposed.odometer)
        decimalChanged(PersistedField.COST, original.cost, proposed.cost)
        changed(PersistedField.NOTES, original.notes, proposed.notes)
        changed(PersistedField.TAGS, original.tags, proposed.tags)
        if (original.kind == RecordKind.FUEL) {
            decimalChanged(PersistedField.GALLONS, original.gallons, proposed.gallons)
            if (original.filledTank != proposed.filledTank) changes +=
                PersistedChange(PersistedField.FILLED, original.filledTank.toString(), proposed.filledTank.toString())
            if (original.missedFill != proposed.missedFill) changes +=
                PersistedChange(PersistedField.MISSED, original.missedFill.toString(), proposed.missedFill.toString())
            changed(PersistedField.STATION, original.stationName, proposed.stationName)
            changed(PersistedField.ADDRESS, original.stationAddress, proposed.stationAddress)
        } else changed(PersistedField.DESCRIPTION, original.description, proposed.description)
        return changes
    }

    fun hasPersistedEditChanges(original: EditorDraft, proposed: EditorDraft): Boolean =
        persistedEditChanges(original, proposed).isNotEmpty()

    /** Never send a write for a missing or invalid vehicle reference. */
    fun canWrite(vehicle: Vehicle, expectedId: VehicleId): Boolean =
        vehicle.id.isValid && vehicle.id == expectedId

    fun resource(kind: RecordKind): String = when (kind) {
        RecordKind.SERVICE -> "servicerecords"
        RecordKind.REPAIR -> "repairrecords"
        RecordKind.UPGRADE -> "upgraderecords"
        RecordKind.FUEL -> "gasrecords"
    }

    fun positiveDecimal(value: String, allowZero: Boolean = true): Boolean =
        value.trim().takeIf { it.matches(Regex("(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,3})?")) }
            ?.let { runCatching { BigDecimal(it) }.getOrNull() }
            ?.let { if (allowZero) it.signum() >= 0 else it.signum() > 0 } ?: false

    // Material3 DatePicker uses UTC midnight, not the device's local time zone.
    // Convert via UTC epoch days to avoid off-by-one dates around midnight or DST.
    private const val MILLIS_PER_DAY = 86_400_000L

    fun dateToPickerMillis(date: String): Long = LocalDate.parse(date).toEpochDay() * MILLIS_PER_DAY

    fun dateFromPickerMillis(selectedUtcMillis: Long): String =
        LocalDate.ofEpochDay(Math.floorDiv(selectedUtcMillis, MILLIS_PER_DAY)).toString()

    fun validate(draft: EditorDraft, original: EditorDraft? = null): String? {
        fun changed(now: String, before: String?) = before == null || now != before
        val date = runCatching { LocalDate.parse(draft.date) }.getOrNull()
        if (date == null || date.toString() != draft.date) return "Enter a valid date (YYYY-MM-DD)."
        val reading = draft.odometer.takeIf { it.matches(Regex("(?:0|[1-9][0-9]{0,8})")) }?.toIntOrNull()
        if (reading == null) return "Odometer must be a non-negative whole number."
        // A blank description already in LubeLogger (possible through its API) does not block an unrelated edit.
        if (draft.kind != RecordKind.FUEL && changed(draft.description, original?.description) &&
            draft.description.trim().isBlank()) return "Enter a description."
        if ((draft.kind != RecordKind.FUEL && changed(draft.description, original?.description) &&
                draft.description.length > DESCRIPTION_MAX) ||
            (changed(draft.notes, original?.notes) && draft.notes.length > NOTES_MAX)) return "Description or notes are too long."
        if (draft.kind == RecordKind.FUEL && draft.cost.isBlank())
            return "Fuel total cost is required (enter 0 if fuel was free)."
        if (changed(draft.cost, original?.cost) && !positiveDecimal(draft.cost)) return "Cost must be a valid non-negative amount (up to three decimals)."
        if (draft.kind == RecordKind.FUEL && changed(draft.gallons, original?.gallons) && !positiveDecimal(draft.gallons, allowZero = false))
            return "Gallons must be greater than zero (up to three decimals)."
        // Like Description and Notes, the limits apply to new or changed values: an unchanged longer value
        // from LubeLogger is preserved and must not block an unrelated edit.
        if ((changed(draft.stationName, original?.stationName) && draft.stationName.length > STATION_NAME_MAX) ||
            (changed(draft.stationAddress, original?.stationAddress) && draft.stationAddress.length > STATION_ADDRESS_MAX))
            return "Station name or address is too long."
        if (draft.kind != RecordKind.FUEL && (draft.stationName.isNotBlank() || draft.stationAddress.isNotBlank()))
            return "Gas station fields are only supported for fuel records."
        if (changed(draft.tags, original?.tags) && draft.tags.isNotBlank() && !draft.tags.matches(Regex("[A-Za-z0-9_-]+(?: [A-Za-z0-9_-]+)*")))
            return "Tags may contain letters, numbers, hyphens and underscores, separated by spaces."
        return null
    }

    fun endpoint(vehicle: Vehicle, kind: RecordKind, confirmedId: VehicleId): String {
        require(canWrite(vehicle, confirmedId)) { "Selected vehicle identity mismatch" }
        require(vehicle.id.isValid)
        return "/api/vehicle/${resource(kind)}/add?vehicleId=${vehicle.id}"
    }
}

/** Display-only edit confirmation strings. Never involved in POST/PUT serialization. */
object EditConfirmationRules {
    private val dateFormat = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)

    fun dateLabel(isoDate: String): String =
        runCatching { LocalDate.parse(isoDate).format(dateFormat) }.getOrDefault(isoDate)

    fun readingValue(reading: String, useHours: Boolean): String {
        if (reading.isBlank()) return "—"
        val display = reading.toLongOrNull()?.let { String.format(Locale.US, "%,d", it) } ?: reading
        return if (useHours) display else "$display mi"
    }

    /** The read-only station line shown when a fuel record's station fields cannot be edited: each stored value
     * bounded for display; the stored values are kept as they are. */
    fun existingStationLabel(name: String, address: String): String = "Existing station: " +
        listOf(name, address).filter { it.isNotBlank() }.joinToString(" · ") { reviewPreview(it) }

    /** A changed text value as the review shows it: line breaks shown as " ⏎ ", then at most
     * [MaintenanceRules.DISPLAY_LINE_MAX_CHARS] characters with "…". Display only; the saved value is untouched. */
    fun reviewPreview(raw: String): String =
        MaintenanceRules.lineForDisplay(raw.take(MaintenanceRules.DISPLAY_LINE_MAX_CHARS * 2)
            .replace(Regex("\\s*\\R\\s*"), " ⏎ ")).let {
                if (raw.length > MaintenanceRules.DISPLAY_LINE_MAX_CHARS * 2 && !it.endsWith("…")) "$it…" else it
            }

    fun changeLabel(change: EditorRules.PersistedChange, useHours: Boolean): String {
        val field = change.code
        val title = if (field == EditorRules.PersistedField.READING && useHours) "Hours" else field.label
        fun display(raw: String): String = when (field) {
            EditorRules.PersistedField.DATE -> dateLabel(raw)
            EditorRules.PersistedField.READING -> readingValue(raw, useHours)
            EditorRules.PersistedField.FILLED, EditorRules.PersistedField.MISSED ->
                if (raw == "true") "Yes" else if (raw == "false") "No" else raw
            else -> if (raw.isBlank()) "(empty)" else reviewPreview(raw)
        }
        return "$title: ${display(change.before)} → ${display(change.after)}"
    }
}
