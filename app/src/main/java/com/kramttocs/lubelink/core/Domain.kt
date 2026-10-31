package com.kramttocs.lubelink.core

import java.math.BigDecimal
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** LubeLogger's vehicle ID. Each identity has its own type, so a record, reminder or raw number
 * can't be passed where a vehicle is meant. `toString` is the bare number, for URLs. */
@JvmInline value class VehicleId(val value: Int) {
    val isValid: Boolean get() = value > 0
    override fun toString(): String = value.toString()
}

/** LubeLogger record ID. Unique only within one record type, so operations take a [RecordRef]. */
@JvmInline value class RecordId(val value: Int) {
    val isValid: Boolean get() = value > 0
    override fun toString(): String = value.toString()
}

/** LubeLogger reminder ID. */
@JvmInline value class ReminderId(val value: Int) {
    override fun toString(): String = value.toString()
}

/** A record's full identity: owning vehicle, record type, and ID within that type. */
data class RecordRef(val vehicleId: VehicleId, val kind: RecordKind, val id: RecordId)

data class Vehicle(
    val id: VehicleId,
    val year: Int,
    val make: String,
    val model: String,
    val imageLocation: String,
    val useHours: Boolean,
    val isElectric: Boolean = false,
    /** The read-only details for the vehicle page's details dialog; null when LubeLogger's could not be read. */
    val details: VehicleDetails? = null
) {
    val displayName: String get() = MaintenanceRules.lineForDisplay(listOf(year.takeIf { it > 0 }?.toString().orEmpty(), make, model)
        .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Vehicle $id" })
}

/**
 * OD-49: the read-only vehicle details LubeLogger sends in `GET /api/vehicles`, shown in the vehicle page's
 * details dialog. LubeLogger sends an unset date as null and an unset price as 0 (docs/API-CONTRACT.md).
 */
data class VehicleDetails(
    val licensePlate: String,
    val isDiesel: Boolean,
    val purchaseDate: LocalDate?,
    val purchasePrice: BigDecimal?,
    val soldDate: LocalDate?,
    val soldPrice: BigDecimal?,
    val tags: List<String>
)

data class VehicleSummary(
    val vehicleId: VehicleId,
    val lastOdometer: Long?,
    val urgentCount: Int,
    val overdueCount: Int,
    val serviceCount: Int
)

enum class RecordKind(val label: String) {
    SERVICE("Service"), REPAIR("Repair"), UPGRADE("Upgrade"), FUEL("Fuel")
}

data class ServiceRecord(
    val id: RecordId,
    val vehicleId: VehicleId,
    val date: String,
    val description: String,
    val odometer: Long?,
    val cost: String,
    val notes: String,
    val tags: String,
    val attachments: List<Attachment>,
    val kind: RecordKind,
    val fuelEconomy: Double? = null,
    val fuelConsumed: Double? = null,
    val fuelStation: String = "",
    val fuelStationAddress: String = ""
) {
    val ref: RecordRef get() = RecordRef(vehicleId, kind, id)
}

data class Attachment(val name: String, val location: String)

/** v1.7.3 may rewrite `metric` to the urgent trigger; `userMetric` stays configured. */
object ReminderScheduleRules {
    fun configuredMetric(userMetric: String, metric: String): String = userMetric.ifBlank { metric }
}

data class MaintenanceReminder(
    val id: ReminderId,
    val vehicleId: VehicleId,
    val description: String,
    val urgency: String,
    val metric: String,
    val dueDate: String,
    val dueOdometer: Long?,
    val dueDays: Int?,
    val dueDistance: Long?,
    val notes: String,
    /** Null means this API response did not provide recurrence information. */
    val isRecurring: Boolean? = null
)

/** A part of the Garage that LubeLogger did not deliver this time. The vehicle list always loads, or the whole
 * Garage fails (OD-24). */
enum class GarageSection { SUMMARIES, REMINDERS }

data class GarageData(
    val vehicles: List<Vehicle>,
    val summaries: Map<VehicleId, VehicleSummary>,
    val reminders: List<MaintenanceReminder>,
    /** Sections that failed to load: their maps and lists are empty because they are unknown, not because
     * LubeLogger has none. */
    val missing: Set<GarageSection> = emptySet(),
    /** Why each missing section failed, so a reminder check can tell a rejected key or unreadable data from a
     * passing fault. Never shown or stored as is. */
    val causes: Map<GarageSection, Exception> = emptyMap()
)

/**
 * Records read one request per record type. [missing] are the types whose request failed: [records] then hold only
 * the types that loaded, and a screen says which are missing rather than presenting the rest as complete (OD-31).
 */
data class RecordsLoad(val records: List<ServiceRecord>, val missing: Set<RecordKind> = emptySet()) {
    /** "Repair records", "Repair and Fuel records", in the order the types are listed. */
    val missingText: String get() = RecordKind.entries.filter { it in missing }.map { it.label }.let { names ->
        (if (names.size <= 1) names.joinToString() else names.dropLast(1).joinToString(", ") + " and " + names.last()) +
            " records"
    }
}

/** Reports need every record type: a total over some types would be silently incomplete (OD-31). */
class IncompleteRecordsException(val load: RecordsLoad) : Exception(
    "${load.missingText} couldn't be loaded from LubeLogger, so reports aren't shown. Pull down or tap Retry to try again.")

/**
 * A reminder may seed an ordinary service entry; it is never linked to the
 * reminder or marked complete through the v1.7.3 API.
 */
object ReminderRecordRules {
    fun canCreate(reminder: MaintenanceReminder, visibleVehicles: List<Vehicle>): Boolean =
        reminder.id.value > 0 && reminder.vehicleId.isValid &&
            reminder.description.isNotBlank() &&
            visibleVehicles.any { it.id == reminder.vehicleId }

    fun seedDescription(reminder: MaintenanceReminder): String = reminder.description
    /** Reminder Notes a new record could not save are left out, and the form says so (OD-12). */
    fun notesTooLongToSeed(reminder: MaintenanceReminder): Boolean = reminder.notes.length > EditorRules.NOTES_MAX
    fun seedNotes(reminder: MaintenanceReminder): String = if (notesTooLongToSeed(reminder)) "" else reminder.notes
}

/** Pure Kotlin rules, exercised by tests without an Android SDK. */
object MaintenanceRules {
    const val DISPLAY_NOTES_MAX_CHARS = 5000
    const val DISPLAY_NOTES_MAX_LINES = 200

    /**
     * Notes as shown in a details dialog. Compose cannot lay out text taller than about 260,000 px,
     * so very long notes from LubeLogger are shown up to a bound, with a pointer to the rest.
     */
    const val DISPLAY_LINE_MAX_CHARS = 300

    /** Server text shown in a title, a row or one line (descriptions, stations, vehicle names): at most 300
     * characters, so a pathological value from LubeLogger cannot break the layout as long notes once did. The full
     * text is unchanged in LubeLogger, and Edit and Delete compare the full value. */
    fun lineForDisplay(text: String): String =
        if (text.length <= DISPLAY_LINE_MAX_CHARS) text else text.take(DISPLAY_LINE_MAX_CHARS).trimEnd() + "…"

    fun notesForDisplay(notes: String): String {
        var end = minOf(notes.length, DISPLAY_NOTES_MAX_CHARS)
        var lines = 1
        for (i in 0 until end) if (notes[i] == '\n' && ++lines > DISPLAY_NOTES_MAX_LINES) { end = i; break }
        return if (end >= notes.length) notes
            else notes.substring(0, end).trimEnd() + "\n…\nThe rest of these notes is in LubeLogger."
    }

    /** Compact but row-consistent Garage grid heights, scaled for accessibility text. */
    fun garageGridHeightDp(fontScale: Float): Float =
        190f + 102f * maxOf(0f, (fontScale.takeIf { it.isFinite() } ?: 1f) - 1f)

    /** Match the exact encoded photo proportions so Fit fills the banner with no crop. */
    fun artworkAspectRatio(width: Int, height: Int): Float =
        if (width > 0 && height > 0) width.toFloat() / height.toFloat() else 2.7f

    /**
     * Contain an entire encoded bitmap inside a frame without stretching or cropping.
     * Return draw dimensions in the same units as frameWidth/frameHeight (dp in Compose).
     * Different source aspect ratios naturally produce side/top mattes; never zoom
     * past either frame boundary just to make unlike vehicle images full-bleed.
     */
    fun artworkContainSize(imageWidth: Int, imageHeight: Int,
                           frameWidth: Float, frameHeight: Float): Pair<Float, Float> {
        if (imageWidth <= 0 || imageHeight <= 0 ||
            !frameWidth.isFinite() || !frameHeight.isFinite() ||
            frameWidth <= 0f || frameHeight <= 0f) return 0f to 0f
        val scale = minOf(frameWidth / imageWidth.toFloat(), frameHeight / imageHeight.toFloat())
        return (imageWidth * scale) to (imageHeight * scale)
    }

    // Strict: an impossible date (2/30) is unreadable, never moved to the last day of the month.
    private val usDate = DateTimeFormatter.ofPattern("M/d/uuuu", Locale.US).withResolverStyle(java.time.format.ResolverStyle.STRICT)
    private val usTwoYear = DateTimeFormatter.ofPattern("M/d/uu", Locale.US).withResolverStyle(java.time.format.ResolverStyle.STRICT)

    fun parseDate(value: String): LocalDate? {
        val v = value.trim().take(40).substringBefore(' ').substringBefore('T')
        if (v.isBlank() || v.startsWith("0001-")) return null
        val candidates = listOfNotNull(
            runCatching { LocalDate.parse(v.take(10)) }.getOrNull(),
            runCatching { LocalDate.parse(v, usDate) }.getOrNull(),
            runCatching { LocalDate.parse(v, usTwoYear) }.getOrNull()?.let { parsed ->
                // For legacy imports interpret 00–49 as 2000–2049, 50–99 as 1950–1999.
                val suffix = v.substringAfterLast('/').toIntOrNull()
                if (suffix != null && suffix in 50..99) parsed.withYear(1900 + suffix) else parsed
            }
        )
        return candidates.firstOrNull()
    }

    fun orderedHistory(records: List<ServiceRecord>): List<ServiceRecord> =
        records.sortedWith(compareByDescending<ServiceRecord> { parseDate(it.date) ?: LocalDate.MIN }
            .thenByDescending { it.id.value })

    /**
     * LubeLogger API v1.7.3 does not inherit the browser user's fuel-unit preference.
     * MethodParameter.UseMPG defaults to false, returning the reciprocal (100 / mpg).
     * Request the same US MPG formula the user's LubeLogger web UI uses.
     * Applies to both vehicle history and aggregate Calendar history.
     */
    fun historyEndpoint(kind: RecordKind, vehicleId: VehicleId? = null): String {
        if (vehicleId != null) require(vehicleId.isValid)
        val resource = when (kind) {
            RecordKind.SERVICE -> "servicerecords"
            RecordKind.REPAIR -> "repairrecords"
            RecordKind.UPGRADE -> "upgraderecords"
            RecordKind.FUEL -> "gasrecords"
        }
        val path = if (vehicleId == null) "/api/vehicle/$resource/all"
            else "/api/vehicle/$resource?vehicleId=$vehicleId"
        return if (kind == RecordKind.FUEL) {
            "$path${if (vehicleId == null) "?" else "&"}useMPG=true&useUKMPG=false"
        } else path
    }

    fun mileageLabel(reading: Long?, useHours: Boolean): String = when {
        reading == null -> "Reading unavailable"
        reading <= 0 -> "No reading recorded"
        useHours -> "${"%,d".format(Locale.US, reading)} hr"
        else -> "${"%,d".format(Locale.US, reading)} mi"
    }

    /** OD-49: the reading shown under a selected vehicle's name in the page headers. Nothing for All vehicles,
     * or when LubeLogger's summary for the vehicle did not load (the page then has no reading to show, rather than
     * a guessed one). */
    fun contextReading(vehicle: Vehicle?, summary: VehicleSummary?): String? =
        if (vehicle == null || summary == null) null else mileageLabel(summary.lastOdometer, vehicle.useHours)

    fun urgencyLabel(urgency: String): String = when (urgency.trim().lowercase(Locale.US)) {
        "pastdue" -> "Past due"
        "veryurgent" -> "Very urgent"
        "urgent" -> "Urgent"
        "noturgent" -> "Not urgent"
        else -> "Status unknown"
    }

    /** OD-37: fuel figures with at most two decimals ("29.4 mpg", "13.25 gal"). */
    private fun fuelFigure(value: Double): String =
        java.math.BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    fun mpgLabel(mpg: Double?): String? =
        mpg?.takeIf { it.isFinite() && it > 0 }?.let { "${fuelFigure(it)} mpg" }

    fun gallonsLabel(gallons: Double?): String? =
        gallons?.takeIf { it.isFinite() && it > 0 }?.let { "${fuelFigure(it)} gal" }

    /** Local display aliases. Not part of LubeLogger v1.7.3's vehicle model. */
    fun normalizeNickname(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")
            .filterNot { it.isISOControl() }.take(48).trim()

    /** Hash preference names so hostnames never appear in alias preference keys. */
    fun nicknamePreferenceKey(server: String, vehicleId: VehicleId): String {
        require(vehicleId.isValid)
        val normalized = allowedServerUrl(server) ?: throw IllegalArgumentException("Invalid server")
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$normalized|$vehicleId".toByteArray(Charsets.UTF_8))
        return "vehicle_alias_" + digest.joinToString("") { "%02x".format(it) }
    }

    /** Hide choices are per LubeLogger server and vehicle, not server-side sold flags. */
    fun visibilityPreferenceKey(server: String, vehicleId: VehicleId): String =
        nicknamePreferenceKey(server, vehicleId).replaceFirst("vehicle_alias_", "vehicle_hidden_")

    /** Pure data filter so all-vehicle screens and pickers use the same visible set. */
    fun visibleGarage(data: GarageData, hiddenIds: Set<VehicleId>): GarageData {
        if (hiddenIds.isEmpty()) return data
        val included = data.vehicles.filterNot { it.id in hiddenIds }
        val ids = included.mapTo(mutableSetOf()) { it.id }
        return data.copy(
            vehicles = included,
            summaries = data.summaries.filterKeys { it in ids },
            reminders = data.reminders.filter { it.vehicleId in ids }
        )
    }

    fun vehicleTitle(vehicle: Vehicle, nickname: String?): String =
        nickname?.let(::normalizeNickname)?.takeIf { it.isNotEmpty() } ?: vehicle.displayName

    fun vehicleSubtitle(vehicle: Vehicle, nickname: String?): String? =
        vehicle.displayName.takeIf { !nickname.isNullOrBlank() && vehicleTitle(vehicle, nickname) != it }

    /** Gold identity in All-vehicles reminders: never hide the year/make/model behind an alias. */
    fun vehicleContextLabel(vehicle: Vehicle, nickname: String?): String =
        vehicleSubtitle(vehicle, nickname)?.let { "${vehicleTitle(vehicle, nickname)} · $it" }
            ?: vehicle.displayName

    /** LubeLogger v1.7.3 reminder metric is Date, Odometer, or Both. Never
     * infer an active metric from stale or zero-valued unused columns. */
    enum class ReminderMetric { DATE, ODOMETER, BOTH, UNKNOWN }

    fun reminderMetric(metric: String): ReminderMetric = when (metric.trim().lowercase(Locale.US)) {
        "date" -> ReminderMetric.DATE
        "odometer", "mileage", "distance" -> ReminderMetric.ODOMETER
        "both", "dateandodometer", "either", "whichevercomesfirst" -> ReminderMetric.BOTH
        else -> ReminderMetric.UNKNOWN
    }

    data class ReminderPresentation(val targets: List<String>, val progress: List<String>,
                                    val metricKnown: Boolean)

    fun reminderPresentation(reminder: MaintenanceReminder, useHours: Boolean): ReminderPresentation {
        val metric = reminderMetric(reminder.metric)
        val dateActive = metric == ReminderMetric.DATE || metric == ReminderMetric.BOTH
        val readingActive = metric == ReminderMetric.ODOMETER || metric == ReminderMetric.BOTH
        val targets = buildList {
            if (dateActive) dueDateLabel(reminder.dueDate)?.let { add("Due: $it") }
            if (readingActive) reminder.dueOdometer?.takeIf { it > 0 }?.let {
                add("Due at ${mileageLabel(it, useHours)}")
            }
        }
        val progress = buildList {
            if (dateActive) daysUntilLabel(reminder.dueDays)?.let { add(it) }
            if (readingActive) readingUntilLabel(reminder.dueDistance, useHours)?.let { add(it) }
        }
        return ReminderPresentation(targets, progress, metric != ReminderMetric.UNKNOWN)
    }

    /** Reminder types have different manual follow-up actions. */
    fun reminderFollowUp(reminder: MaintenanceReminder): String = when (reminder.isRecurring) {
        true -> "Recording a service won't automatically advance this reminder. Update it in LubeLogger."
        false -> "Recording a service won't automatically complete this reminder. Mark it done in LubeLogger."
        null -> "Recording a service doesn't automatically complete or advance reminders. Manage this reminder in LubeLogger."
    }

    fun dueDateLabel(date: String): String? = parseDate(date)
        ?.format(DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US))

    /** OD-37: a record's date as Reminders show theirs ("Oct 20, 2026"); text LubeLink cannot read is shown as sent. */
    fun recordDateLabel(date: String): String = dueDateLabel(date) ?: date

    fun daysUntilLabel(days: Int?): String? = when {
        days == null -> null
        days < 0 -> "${-days} ${if (days == -1) "day" else "days"} beyond target"
        days == 0 -> "Due today"
        days == 1 -> "Due tomorrow"
        else -> "Due in $days days"
    }

    fun readingUntilLabel(distance: Long?, useHours: Boolean): String? = when {
        distance == null -> null
        distance < 0 -> "${mileageLabel(-distance, useHours)} beyond target"
        distance == 0L -> "Due at current reading"
        else -> "${mileageLabel(distance, useHours)} remaining"
    }

    fun currencyLabel(cost: String): String? = cost.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it >= 0 }
        ?.let { "$" + "%,.2f".format(Locale.US, it) }

    fun urgencyOrder(urgency: String): Int = when (urgency.trim().lowercase(Locale.US)) {
        "pastdue" -> 0
        "veryurgent" -> 1
        "urgent" -> 2
        else -> 3
    }

    /** OD-49: a vehicle's reminders that are due (past due, very urgent or urgent), most urgent first. Reminders
     * that did not load are an empty list (GarageData.missing), so they show no panel rather than a count. */
    fun dueReminders(reminders: List<MaintenanceReminder>, vehicleId: VehicleId): List<MaintenanceReminder> =
        orderedReminders(reminders.filter { it.vehicleId == vehicleId && urgencyOrder(it.urgency) <= 2 })

    fun dueCountLabel(count: Int): String = if (count == 1) "1 reminder due" else "$count reminders due"

    fun orderedReminders(reminders: List<MaintenanceReminder>): List<MaintenanceReminder> =
        reminders.sortedWith(compareBy<MaintenanceReminder> { urgencyOrder(it.urgency) }
            .thenBy { parseDate(it.dueDate) ?: LocalDate.MAX }
            .thenBy { it.description.lowercase(Locale.US) })

    fun allowedServerUrl(value: String): String? {
        val uri = runCatching { java.net.URI(value.trim()) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase(Locale.US) != "https") return null
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        if (uri.rawPath !in listOf("", "/")) return null
        if (uri.port !in -1..65535 || uri.port == 0) return null
        val hostname = uri.host.lowercase(Locale.US)
        val authorityHost = if (hostname.contains(':') && !hostname.startsWith('[')) "[$hostname]" else hostname
        return "https://$authorityHost${if (uri.port > 0) ":${uri.port}" else ""}"
    }

    /** Only server-controlled attachment directories; forbid query, fragments and traversal. */
    fun allowedAttachmentPath(value: String): Boolean {
        if (value.contains("..") || value.contains('\\') || value.contains('?') || value.contains('#')) return false
        return Regex("^/(?:documents|images)/[A-Za-z0-9_.%() +,-]{1,180}$").matches(value) &&
            // Reject encoded separators/traversal, including double-encoding.
            !Regex("%(?:2[fF]|5[cC]|2[eE]|25)").containsMatchIn(value)
    }

    /** Never permit a server-supplied path to become an arbitrary authenticated URL. */
    fun allowedImagePath(value: String): Boolean =
        Regex("^/(?:images|defaults)/[A-Za-z0-9_.-]+$").matches(value) && !value.contains("..")
}


/** Station source and fallback logic never infer status from user-visible text. */
enum class StationSearchProvider { GEOAPIFY, OVERPASS }

object StationSearchPolicy {
    fun fallbackNotice(httpStatus: Int?, unreadable: Boolean = false): String = when {
        unreadable -> "Geoapify sent data LubeLink could not read. Using Overpass for this search."
        else -> fallbackNotice(httpStatus)
    }

    fun fallbackNotice(httpStatus: Int?): String = when (httpStatus) {
        401, 403 -> "Geoapify key rejected. Using Overpass; check the key in Settings."
        429 -> "Geoapify limit reached. Using Overpass for this search."
        else -> "Geoapify unavailable. Using Overpass for this search."
    }
}

/** History filtering is typed; visible names are presentation, never record identity. */
enum class HistoryFilter(val label: String, val kind: RecordKind?) {
    ALL("All", null), SERVICE("Service", RecordKind.SERVICE),
    REPAIR("Repair", RecordKind.REPAIR), UPGRADE("Upgrade", RecordKind.UPGRADE),
    FUEL("Fuel", RecordKind.FUEL)
}

/** Single navigation context for Garage, History, Calendar, Reminders, and creation.
 * The active vehicle is `null` for "All vehicles". */
object NavigationRules {
    fun includes(activeVehicleId: VehicleId?, recordVehicleId: VehicleId): Boolean =
        activeVehicleId == null || activeVehicleId == recordVehicleId
    fun needsVehicleChoice(activeVehicleId: VehicleId?): Boolean = activeVehicleId == null
    fun initialKind(historyFilter: HistoryFilter): RecordKind =
        historyFilter.kind ?: RecordKind.SERVICE
}

/** A dated event on the read-only mobile calendar; only one source record per event. */
data class CalendarEntry(
    val day: LocalDate,
    val vehicleId: VehicleId,
    val record: ServiceRecord? = null,
    val reminder: MaintenanceReminder? = null
) {
    init { require((record == null) != (reminder == null)) }
}

object CalendarRules {
    /** Sunday-first grid, padded to complete weeks. Blank cells have no date. */
    fun monthCells(month: java.time.YearMonth): List<LocalDate?> {
        val leading = month.atDay(1).dayOfWeek.value % 7
        val total = leading + month.lengthOfMonth()
        val cells = ((total + 6) / 7) * 7
        return List(cells) { index ->
            val day = index - leading + 1
            if (day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }
    }

    /** Invalid or mileage-only reminders deliberately aren't assigned fictional dates. */
    fun datedEntries(records: List<ServiceRecord>, reminders: List<MaintenanceReminder>,
                     vehicleId: VehicleId? = null): List<CalendarEntry> {
        val history = records.asSequence().filter { vehicleId == null || it.vehicleId == vehicleId }
            .mapNotNull { record ->
                MaintenanceRules.parseDate(record.date)?.let { CalendarEntry(it, record.vehicleId, record = record) }
            }
        val due = reminders.asSequence().filter { vehicleId == null || it.vehicleId == vehicleId }
            .mapNotNull { reminder ->
                if (MaintenanceRules.reminderMetric(reminder.metric) in setOf(
                    MaintenanceRules.ReminderMetric.DATE, MaintenanceRules.ReminderMetric.BOTH))
                    MaintenanceRules.parseDate(reminder.dueDate)?.let { CalendarEntry(it, reminder.vehicleId, reminder = reminder) }
                else null
            }
        return (history + due).toList().sortedWith(compareBy<CalendarEntry> { it.day }
            .thenByDescending { it.reminder != null }
            .thenBy { it.vehicleId.value }
            .thenBy { it.record?.id?.value ?: it.reminder?.id?.value ?: 0 })
    }

    fun entriesForDay(entries: List<CalendarEntry>, day: LocalDate) = entries.filter { it.day == day }
    fun entriesForMonth(entries: List<CalendarEntry>, month: java.time.YearMonth) =
        entries.filter { java.time.YearMonth.from(it.day) == month }
}
