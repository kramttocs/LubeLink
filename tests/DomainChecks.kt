package com.kramttocs.lubelink.core

import java.math.BigDecimal
import java.time.LocalDate

/** Small, Android-independent guardrails. Uses only disposable local values. */
fun main() {
    var checks = 0
    fun checkThat(ok: Boolean, name: String) {
        require(ok) { "Failed: $name" }
        checks++
    }
    val petrol = Vehicle(VehicleId(1), 2015, "Example", "Sedan", "", false)
    val ev = petrol.copy(id = VehicleId(2), isElectric = true)
    checkThat(!petrol.isElectric && ev.isElectric, "electric flag")
    for (kind in RecordKind.entries) {
        checkThat(EditorRules.unsupportedChargingWrite(ev, kind) == (kind == RecordKind.FUEL), "EV write guard: $kind")
        checkThat(!EditorRules.unsupportedChargingWrite(petrol, kind), "petrol write unchanged: $kind")
    }
    checkThat(ReminderScheduleRules.configuredMetric("Both", "Odometer") == "Both", "Both schedule survives odometer trigger")
    checkThat(ReminderScheduleRules.configuredMetric("Both", "Date") == "Both", "Both schedule survives date trigger")
    checkThat(ReminderScheduleRules.configuredMetric("", "Date") == "Date", "legacy metric fallback")
    val due = MaintenanceReminder(ReminderId(4), VehicleId(1), "Tire rotation", "VeryUrgent", "Both", "2026-10-25", 50000, 10, 250, "")
    checkThat(due.metric == "Both", "configured schedule retained")
    checkThat(EditorRules.canWrite(petrol, petrol.id), "verified vehicle")
    checkThat(!EditorRules.canWrite(petrol, ev.id), "vehicle mismatch")
    val source = ServiceRecord(RecordId(15), VehicleId(1), "2026-10-01", "Oil change", 15000, "12.34567", "Normal", "", emptyList(), RecordKind.SERVICE)
    checkThat(CopyRecordRules.copiedCost("12.34567") == "12.346", "round legacy cost instead of zero")
    checkThat(CopyRecordRules.copiedCost("0.0004") == "0", "round small legitimate nonnegative cost")
    checkThat(CopyRecordRules.copiedCost("bad") == "", "invalid legacy cost needs review")
    checkThat(CopyRecordRules.copiedCost("-4.0001") == "", "negative cost needs review")
    val copied = CopyRecordRules.prefill(source, 16000, LocalDate.of(2026, 10, 14))
    checkThat(copied.cost == "12.346" && copied.description == "Oil change", "service copy values")
    checkThat(copied.date == "2026-10-14" && copied.odometer == "16000", "new copy date/reading")
    checkThat(source.cost == "12.34567" && source.odometer == 15000L, "copy does not mutate source")
    val fuelSource = source.copy(kind = RecordKind.FUEL, cost = "65.00", fuelStation = "Gas stop", fuelConsumed = 16.0)
    val copiedFuel = CopyRecordRules.prefill(fuelSource, 16100, LocalDate.of(2026, 10, 14))
    checkThat(copiedFuel.cost.isEmpty() && copiedFuel.gallons.isEmpty(), "fuel transaction amounts cleared")
    checkThat(copiedFuel.stationName == "Gas stop", "fuel station retained")
    checkThat(EditorRules.shouldClearUnavailableNewFuelStation(RecordKind.FUEL, false,
        FieldReadiness.MISSING, copiedFuel.stationName, copiedFuel.stationAddress),
        "copied fuel station cleared after async missing-field result")
    checkThat(EditorRules.shouldClearUnavailableNewFuelStation(RecordKind.FUEL, false,
        FieldReadiness.MISSING, "New station", ""), "plain new fuel form cleared")
    checkThat(EditorRules.shouldClearUnavailableNewFuelStation(RecordKind.FUEL, false,
        FieldReadiness.MISSING, "", "New address"), "plain new fuel address cleared")
    checkThat(!EditorRules.shouldClearUnavailableNewFuelStation(RecordKind.FUEL, false,
        FieldReadiness.UNKNOWN, "New station", ""), "unknown fields retain new station")
    checkThat(!EditorRules.shouldClearUnavailableNewFuelStation(RecordKind.FUEL, false,
        FieldReadiness.READY, "New station", ""), "ready fields retain new station")
    checkThat(!EditorRules.shouldClearUnavailableNewFuelStation(RecordKind.SERVICE, false,
        FieldReadiness.MISSING, "New station", ""), "switch away from fuel preserves until return")
    checkThat(EditorRules.shouldClearUnavailableNewFuelStation(RecordKind.FUEL, false,
        FieldReadiness.MISSING, "New station", ""), "switch back to fuel clears unavailable station")
    checkThat(!EditorRules.shouldClearUnavailableNewFuelStation(RecordKind.FUEL, true,
        FieldReadiness.MISSING, "Existing station", ""), "editing keeps saved station fields")
    checkThat(!EditorRules.shouldClearUnavailableNewFuelStation(RecordKind.FUEL, false,
        FieldReadiness.MISSING, "", ""), "already-cleared station needs no transition")
    val notesDraft = EditorDraft(RecordKind.SERVICE, "2026-10-12", "1200", "Inspection", "10.00", "null", "")
    checkThat(EditorRules.persistedEditChanges(notesDraft, notesDraft.copy(notes="changed")) ==
        listOf(EditorRules.PersistedChange(EditorRules.PersistedField.NOTES, "null", "changed")), "edit preview uses actual notes diff")
    checkThat(EditConfirmationRules.changeLabel(
        EditorRules.PersistedChange(EditorRules.PersistedField.DATE, "2026-10-01", "2026-10-02"), false) ==
        "Date: Oct 1, 2026 → Oct 2, 2026", "edit review displays formatted dates")
    checkThat(EditConfirmationRules.changeLabel(
        EditorRules.PersistedChange(EditorRules.PersistedField.READING, "1200", "1300"), true) ==
        "Hours: 1,200 → 1,300", "hour-meter review never says odometer")
    checkThat(EditConfirmationRules.changeLabel(
        EditorRules.PersistedChange(EditorRules.PersistedField.FILLED, "true", "false"), false) ==
        "Filled tank: Yes → No", "edit review displays flags in plain language")
    checkThat(EditConfirmationRules.changeLabel(
        EditorRules.PersistedChange(EditorRules.PersistedField.NOTES, "null", "changed"), false) ==
        "Notes: null → changed", "literal null remains user text in review")
    checkThat(EditorRules.persistedEditChanges(notesDraft, notesDraft.copy(cost="10.0")).isEmpty(),
        "edit preview skips decimal formatting-only change")
    // No-op edits must be recognized by persisted values, not draft identity.
    val originalEdit = EditorDraft(RecordKind.SERVICE, "2026-10-12", "1200", "Inspection",
        "10.00", "notes", "tags")
    checkThat(!EditorRules.hasPersistedEditChanges(originalEdit, originalEdit), "unchanged edit is no-op")
    checkThat(!EditorRules.hasPersistedEditChanges(originalEdit, originalEdit.copy(cost = "10.0")),
        "decimal formatting alone is not a persisted change")
    checkThat(!EditorRules.hasPersistedEditChanges(originalEdit, originalEdit.copy(notes = " notes ")),
        "whitespace-only normalization is no persisted change")
    checkThat(EditorRules.hasPersistedEditChanges(originalEdit, originalEdit.copy(description = "Replaced")),
        "description edit is a change")
    checkThat(EditorRules.hasPersistedEditChanges(originalEdit, originalEdit.copy(odometer = "1201")),
        "odometer edit is a change")
    checkThat(EditorRules.hasPersistedEditChanges(originalEdit, originalEdit.copy(date = "2026-10-13")),
        "date edit is a change")
    checkThat(EditorRules.hasPersistedEditChanges(originalEdit, originalEdit.copy(cost = "10.01")),
        "numeric cost edit is a change")
    checkThat(EditorRules.hasPersistedEditChanges(originalEdit, originalEdit.copy(notes = "different")),
        "note edit is a change")
    checkThat(EditorRules.hasPersistedEditChanges(originalEdit, originalEdit.copy(tags = "new")),
        "tags edit is a change")
    val originalFuel = originalEdit.copy(kind = RecordKind.FUEL, description = "", gallons = "12.50",
        stationName = "Previous stop", stationAddress = "123 Test Rd")
    checkThat(!EditorRules.hasPersistedEditChanges(originalFuel, originalFuel.copy(gallons = "12.5")),
        "fuel decimal formatting alone is not a change")
    checkThat(EditorRules.hasPersistedEditChanges(originalFuel, originalFuel.copy(gallons = "13")),
        "fuel quantity edit is a change")
    checkThat(EditorRules.hasPersistedEditChanges(originalFuel, originalFuel.copy(filledTank = false)),
        "fill state edit is a change")
    checkThat(EditorRules.hasPersistedEditChanges(originalFuel, originalFuel.copy(missedFill = true)),
        "missed fill edit is a change")
    checkThat(EditorRules.hasPersistedEditChanges(originalFuel, originalFuel.copy(stationName = "New stop")),
        "station name edit is a change")
    checkThat(EditorRules.hasPersistedEditChanges(originalFuel, originalFuel.copy(stationAddress = "Other road")),
        "station address edit is a change")
    checkThat(!EditorRules.hasPersistedEditChanges(originalFuel, originalFuel.copy(description = "Ignored")),
        "irrelevant Fuel description does not trigger PUT")
    checkThat(StationBounds(-93.45, 37.61, -93.40, 37.65).isSearchable(), "small bounds")
    checkThat(!StationBounds(-95.0, 37.0, -93.0, 39.0).isSearchable(), "unbounded search blocked")
    checkThat(StationBounds(-93.45, 37.61, -93.40, 37.65).overpassBBox().startsWith("(37.610000,-93.450000,"), "bbox coordinate order")
    checkThat(StationAreaCachePolicy.reuse(10_000, 40f, true), "recent nearby cache")
    checkThat(!StationAreaCachePolicy.reuse(10_000, 200f, true), "changed map center fetch")
    checkThat(NotificationRules.eligible("VeryUrgent", AlertLevel.DUE) == 1, "notification urgency")
    checkThat(NotificationRules.eligible("Urgent", AlertLevel.DUE) == null, "below the threshold: no alert")
    checkThat(NotificationRules.eligible("Urgent", AlertLevel.DUE_SOON) == 2, "the default notifies Urgent")
    checkThat(NotificationRules.eligible("VeryUrgent", AlertLevel.PAST_DUE) == null &&
        NotificationRules.eligible("PastDue", AlertLevel.PAST_DUE) == 0, "Past due only")
    checkThat(AlertLevel.of(null) == AlertLevel.DUE_SOON && AlertLevel.of("FOO") == AlertLevel.DUE_SOON &&
        AlertLevel.of("PAST_DUE") == AlertLevel.PAST_DUE, "a missing or unknown stored level is the default")
    checkThat(NotificationRules.isNewAlert(2, 1), "alert on escalation")
    checkThat(!NotificationRules.isNewAlert(1, 1), "no duplicate alert")
    checkThat(WriteSuccessNotice.message(RecordWriteIntent(WriteAction.DELETE, RecordKind.FUEL)) == "Fuel record deleted", "delete notice")
    checkThat(RecordWriteIntent.fromPersistedCode("DELETE:FUEL") ==
        RecordWriteIntent(WriteAction.DELETE, RecordKind.FUEL), "typed write intent survives restart")
    checkThat(RecordWriteIntent.fromPersistedCode("DELETE:INVALID") == null,
        "unknown persisted write intent fails closed")
    checkThat(FormSessionRules.accepts(2, 2, true), "current form accepts its callback")
    checkThat(!FormSessionRules.accepts(3, 2, true), "previous form callback rejected")
    checkThat(!FormSessionRules.accepts(2, 2, false), "closed form callback rejected")
    for (action in WriteAction.entries) for (recordKind in RecordKind.entries) {
        val intent = RecordWriteIntent(action, recordKind)
        checkThat(RecordWriteIntent.fromPersistedCode(intent.persistedCode()) == intent,
            "typed intent round-trip $intent")
        checkThat(WriteSuccessNotice.message(intent).isNotBlank(),
            "success notice from typed intent $intent")
    }
    checkThat(WriteSuccessNotice.message(RecordWriteIntent(WriteAction.EDIT, RecordKind.SERVICE)) ==
        "Service record updated", "typed edit notice")
    val rows = ReportDataset(listOf(source.copy(cost = "12.345", date = "2026-10-01"), fuelSource.copy(date="2026-10-02")))
    val exp = ReportRules.expenses(rows, listOf(petrol), setOf(VehicleId(1)), ReportScope(), ReportPeriod.ALL_TIME, LocalDate.of(2026,10,14))
    checkThat(exp.total == BigDecimal("77.35"), "report rounded totals")
    checkThat(exp.recordsInPeriod.size == 2, "report scoped records")
    checkThat(ReportRules.expenses(rows, listOf(petrol), emptySet(), ReportScope(), ReportPeriod.ALL_TIME, LocalDate.of(2026,10,14)).recordsInPeriod.isEmpty(), "hidden vehicle excluded")
    checkThat(ReportRules.fuel(rows, listOf(petrol), setOf(VehicleId(1)), ReportScope(), ReportPeriod.ALL_TIME, LocalDate.of(2026,10,14)).purchasedGallons == BigDecimal("16.0"), "fuel gallons")
    val configuredGas = listOf(
        ConfiguredField("GasRecord", "LubeLink Gas Station", "Text", false),
        ConfiguredField("GasRecord", "LubeLink Station Address", "Text", false))
    checkThat(RecordFieldRules.gasStationStatus(configuredGas).readiness == FieldReadiness.READY,
        "matching optional GasRecord Text fields enable station picker")
    checkThat(RecordFieldRules.gasStationStatus(configuredGas.take(1)).missing ==
        listOf("LubeLink Station Address"), "one missing field detected")
    checkThat(RecordFieldRules.gasStationStatus(configuredGas.map { it.copy(recordType = "ServiceRecord") }).readiness ==
        FieldReadiness.MISSING, "wrong record category isn't sufficient")
    checkThat(RecordFieldRules.gasStationStatus(configuredGas.map { it.copy(fieldType = "Number") }).readiness ==
        FieldReadiness.MISSING, "wrong field type isn't sufficient")
    checkThat(RecordFieldRules.gasStationStatus(configuredGas.map { it.copy(isRequired = true) }).readiness ==
        FieldReadiness.MISSING, "required fields don't match optional contract")
    checkThat(RecordFieldRules.gasStationStatus(emptyList()).missing.size == 2,
        "fresh LubeLogger installation has both station fields missing")
    checkThat(HistoryFilter.ALL.kind == null && HistoryFilter.ALL.label == "All", "typed All history filter")
    for (filter in HistoryFilter.entries.filter { it.kind != null }) {
        checkThat(NavigationRules.initialKind(filter) == filter.kind,
            "typed History filter uses kind ID: $filter")
    }
    checkThat(NavigationRules.initialKind(HistoryFilter.ALL) == RecordKind.SERVICE,
        "unfiltered Add defaults to Service")
    checkThat(StationSearchProvider.GEOAPIFY != StationSearchProvider.OVERPASS,
        "distinct station provider identities")
    checkThat(StationSearchPolicy.fallbackNotice(401).contains("key rejected"),
        "typed Geoapify authentication failure")
    checkThat(StationSearchPolicy.fallbackNotice(403).contains("key rejected"),
        "typed Geoapify permission failure")
    checkThat(StationSearchPolicy.fallbackNotice(429).contains("limit reached"),
        "typed Geoapify limit failure")
    checkThat(StationSearchPolicy.fallbackNotice(null).contains("unavailable"),
        "provider transport failure classification")
    checkThat(StationSearchPolicy.fallbackNotice(500).contains("unavailable"),
        "provider service failure classification")
    // Typing stops at the Save limit; long server text can be shortened but not lengthened.
    checkThat(EditorRules.limitEntry("", "a".repeat(10), 5) == "aaaaa", "paste past the limit is cut at the limit")
    checkThat(EditorRules.limitEntry("abc", "abcd", 5) == "abcd", "text within the limit is accepted as typed")
    checkThat(EditorRules.limitEntry("a".repeat(8), "a".repeat(9), 5) == "a".repeat(8), "over-long server text cannot grow")
    checkThat(EditorRules.limitEntry("a".repeat(8), "a".repeat(7), 5) == "a".repeat(7), "over-long server text can be shortened")
    checkThat(EditorRules.validate(EditorDraft(RecordKind.SERVICE, "2026-10-01", "1", "Oil", "0",
        "n".repeat(EditorRules.NOTES_MAX), "")) == null, "the Notes entry limit equals the Save limit")
    checkThat(EditorRules.validate(EditorDraft(RecordKind.SERVICE, "2026-10-01", "1", "Oil", "0",
        "n".repeat(EditorRules.NOTES_MAX + 1), "")) != null, "one past the Notes limit is refused at Save")
    val longNotes = "Synthetic long note line.\n".repeat(23_000)
    val shown = MaintenanceRules.notesForDisplay(longNotes)
    checkThat(shown.lines().size <= MaintenanceRules.DISPLAY_NOTES_MAX_LINES + 2 && shown.endsWith("is in LubeLogger."),
        "very long notes are shown up to a bound with a pointer to LubeLogger")
    checkThat(MaintenanceRules.notesForDisplay("x".repeat(9_000)).length < 5_100, "long single-line notes are bounded too")
    checkThat(MaintenanceRules.notesForDisplay("Short note\nTwo lines") == "Short note\nTwo lines", "ordinary notes unchanged")
    checkThat(CopyRecordRules.prefill(source.copy(notes = "n".repeat(EditorRules.NOTES_MAX + 1)), 16000,
        LocalDate.of(2026, 10, 14)).notes.isEmpty(), "notes a new record could not save are not copied")
    checkThat(CopyRecordRules.prefill(source.copy(notes = "n".repeat(EditorRules.NOTES_MAX)), 16000,
        LocalDate.of(2026, 10, 14)).notes.length == EditorRules.NOTES_MAX, "notes at the limit are copied")
    // Station limits apply to new or changed values; unchanged longer server values are kept.
    val importedFuel = EditorDraft(RecordKind.FUEL, "2026-10-01", "1500", "", "30.50", "", "", gallons = "8",
        stationName = "s".repeat(EditorRules.STATION_NAME_MAX + 1), stationAddress = "a".repeat(EditorRules.STATION_ADDRESS_MAX + 1))
    checkThat(EditorRules.validate(importedFuel.copy(cost = "31.00"), importedFuel) == null,
        "unchanged over-limit station values do not block an unrelated edit")
    checkThat(EditorRules.validate(importedFuel.copy(stationName = importedFuel.stationName + "x"), importedFuel) != null,
        "a changed over-limit station name is refused")
    checkThat(EditorRules.validate(importedFuel.copy(stationAddress = importedFuel.stationAddress + "x"), importedFuel) != null,
        "a changed over-limit station address is refused")
    checkThat(EditorRules.validate(importedFuel) != null, "an over-limit station on Add is refused")
    // Server text shown in a title, row or line is cut to 300 characters; shorter text is unchanged.
    checkThat(MaintenanceRules.lineForDisplay("Oil change") == "Oil change", "short text is shown unchanged")
    checkThat(MaintenanceRules.lineForDisplay("d".repeat(600_000)).length == MaintenanceRules.DISPLAY_LINE_MAX_CHARS + 1,
        "600,000 characters are shown as 300 and an ellipsis")
    checkThat(Vehicle(VehicleId(1), 2015, "Make", "m".repeat(10_000), "", false).displayName.endsWith("…"),
        "a huge vehicle name is shortened")
    // A blank description from LubeLogger is kept on an unrelated edit; Add, or clearing one, still needs one.
    val blankImported = EditorDraft(RecordKind.SERVICE, "2026-10-01", "1500", "", "30.50", "", "")
    checkThat(EditorRules.validate(blankImported.copy(cost = "31.00"), blankImported) == null,
        "an unchanged blank description does not block an unrelated edit")
    checkThat(EditorRules.validate(blankImported) != null, "Add without a description is refused")
    checkThat(EditorRules.validate(blankImported.copy(description = " "), blankImported.copy(description = "Oil")) != null,
        "clearing a description is refused")
    // Reminder Notes a new record could not save are left out.
    val longReminder = MaintenanceReminder(ReminderId(4), VehicleId(1), "Rotate tires", "Urgent", "Date", "2026-11-01",
        null, 10, null, "n".repeat(EditorRules.NOTES_MAX + 1))
    checkThat(ReminderRecordRules.seedNotes(longReminder).isEmpty() && ReminderRecordRules.notesTooLongToSeed(longReminder),
        "overlong reminder notes are not copied into a new record")
    checkThat(ReminderRecordRules.seedNotes(longReminder.copy(notes = "Use synthetic oil")) == "Use synthetic oil",
        "ordinary reminder notes are copied")
    // OD-77: totals add the exact costs, then round for display (two 0.005 costs are 0.01, not 0.02).
    fun costRow(id: Int, cost: String) = ServiceRecord(RecordId(id), VehicleId(1), "2026-10-12", "Inspection", 1200, cost,
        "", "", emptyList(), RecordKind.SERVICE)
    checkThat(ReportRules.costTotal(listOf(costRow(1, "0.005"), costRow(2, "0.005"))) == BigDecimal("0.01"), "exact costs added, then rounded")
    checkThat(ReportRules.costTotal(listOf(costRow(1, "45.50"), costRow(2, "10.25"))) == BigDecimal("55.75"), "ordinary costs total unchanged")
    checkThat(ReportRules.readCost("0.005") == BigDecimal("0.01"), "a single row still shows its cost rounded to cents")
    // Legacy US dates are read strictly: an impossible date is unreadable, never moved to a nearby day (review 2026-10-29).
    checkThat(MaintenanceRules.parseDate("2/30/2026") == null, "2/30/2026 is not a date")
    checkThat(MaintenanceRules.parseDate("4/31/26") == null, "4/31/26 is not a date")
    checkThat(MaintenanceRules.parseDate("2/28/2026") == LocalDate.of(2026, 2, 28), "a real legacy date still reads")
    checkThat(MaintenanceRules.parseDate("2/29/2024") == LocalDate.of(2024, 2, 29), "a leap day still reads")
    checkThat(MaintenanceRules.parseDate("10/5/99") == LocalDate.of(1999, 10, 5), "two-digit 99 is 1999")
    // OD-76: a reading of 0 means "not recorded": it is no point on the chart and starts no distance.
    fun odoRow(id: Int, date: String, odometer: Long) = ServiceRecord(RecordId(id), VehicleId(1), date, "Inspection", odometer, "1.00",
        "", "", emptyList(), RecordKind.SERVICE)
    val zeroThenReal = OdometerChartRules.points(listOf(odoRow(1, "2026-01-05", 0), odoRow(2, "2026-03-05", 1000), odoRow(3, "2026-05-05", 1600)))
    checkThat(zeroThenReal.map { it.reading } == listOf(1000L, 1600L), "a 0 reading is not a chart point (OD-76)")
    // OD-77 through every report path: two 0.005 costs are 0.01 everywhere, never 0.02 in some totals.
    val day = LocalDate.of(2026, 10, 14)
    val halfCents = ReportDataset(listOf(costRow(1, "0.005").copy(date = "2026-10-05"), costRow(2, "0.005").copy(date = "2026-10-06"),
        fuelSource.copy(id = RecordId(3), cost = "0.005", date = "2026-10-07"), fuelSource.copy(id = RecordId(4), cost = "0.005", date = "2026-10-08")))
    val penny = BigDecimal("0.01")
    val serviceOnly = ReportDataset(halfCents.records.filter { it.kind == RecordKind.SERVICE })
    val fuelOnly = ReportDataset(halfCents.records.filter { it.kind == RecordKind.FUEL })
    val expHalf = ReportRules.expenses(serviceOnly, listOf(petrol), setOf(VehicleId(1)), ReportScope(), ReportPeriod.ALL_TIME, day)
    checkThat(expHalf.total == penny, "Expenses total adds exact costs (got ${expHalf.total})")
    checkThat(expHalf.categories.single { it.name == RecordKind.SERVICE.label }.total == penny, "Expenses type group adds exact costs")
    checkThat(expHalf.months.single { it.records.isNotEmpty() }.total == penny, "Expenses month group adds exact costs")
    checkThat(expHalf.vehicles.single().total == penny, "Expenses vehicle group adds exact costs")
    val fuelHalf = ReportRules.fuel(fuelOnly, listOf(petrol), setOf(VehicleId(1)), ReportScope(), ReportPeriod.ALL_TIME, day)
    checkThat(fuelHalf.totalSpent == penny, "Fuel spending adds exact costs (got ${fuelHalf.totalSpent})")
    checkThat(fuelHalf.paidForPricedGallons == penny, "Fuel priced cost adds exact costs (got ${fuelHalf.paidForPricedGallons})")
    checkThat(fuelHalf.months.single().total == penny && fuelHalf.stations.single().total == penny, "Fuel month and station groups agree")
    val maintHalf = ReportRules.maintenance(serviceOnly, listOf(petrol), setOf(VehicleId(1)), ReportScope(), ReportPeriod.ALL_TIME, day)
    checkThat(maintHalf.totalSpent == penny, "Maintenance total adds exact costs")
    checkThat(ReportRules.costTotal(serviceOnly.records) == expHalf.total && expHalf.total == maintHalf.totalSpent,
        "History, Expenses and Maintenance agree on the same records")
    println("Passed $checks LubeLink offline checks")
}
