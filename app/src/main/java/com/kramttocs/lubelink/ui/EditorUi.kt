package com.kramttocs.lubelink.ui

import androidx.compose.foundation.layout.PaddingValues
import com.kramttocs.lubelink.core.LubeLoggerEditorApi
import com.kramttocs.lubelink.core.RecordEditorGateway
import com.kramttocs.lubelink.core.RecordRef
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.core.EditOutcome
import com.kramttocs.lubelink.core.EditorDraft
import com.kramttocs.lubelink.core.CopyRecordRules
import com.kramttocs.lubelink.core.EditorRules
import com.kramttocs.lubelink.core.RecordWriteIntent
import com.kramttocs.lubelink.core.WriteAction
import com.kramttocs.lubelink.core.EditConfirmationRules
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.NavigationRules
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.ServiceDescriptionRules
import com.kramttocs.lubelink.core.ServiceDescriptionStore
import com.kramttocs.lubelink.core.Vehicle
import kotlinx.coroutines.CancellationException
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.window.DialogProperties
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Record creation/editing for a selected vehicle; no deletion or reminder mutation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EditorRecordDialog(vehicles: List<Vehicle>, summaries: Map<VehicleId, com.kramttocs.lubelink.core.VehicleSummary>,
                                nicknames: Map<VehicleId, String>,
                                initialVehicleId: VehicleId?, baseUrl: String, editorKey: String,
                                initialKind: RecordKind = RecordKind.SERVICE,
                                initialReminder: MaintenanceReminder? = null,
                                descriptionRecords: List<ServiceRecord> = emptyList(),
                                descriptionsLoading: Boolean = false,
                                gasFields: com.kramttocs.lubelink.core.GasStationFieldCheck,
                                onGasFieldsNeeded: () -> Unit,
                                onDismiss: () -> Unit, onSaved: () -> Unit,
                                existingRecord: RecordRef? = null,
                                copySource: ServiceRecord? = null,
                                editor: RecordEditorGateway = remember(baseUrl, editorKey) {
                                    LubeLoggerEditorApi(baseUrl, editorKey)
                                }) {
    require(copySource == null || existingRecord == null) { "Copy must use Add, not Edit" }
    require(copySource == null || (copySource.vehicleId == initialVehicleId && copySource.kind == initialKind)) {
        "Copy source vehicle/type mismatch"
    }
    val copiedDraft = remember(copySource, initialVehicleId, summaries) {
        copySource?.let { CopyRecordRules.prefill(it, initialVehicleId?.let { summaries[it] }?.lastOdometer) }
    }
    val scope = rememberCoroutineScope()
    val currentContext = LocalContext.current
    var chosenVehicleId by rememberSaveable(existingRecord?.id, initialVehicleId, stateSaver = OptionalVehicleIdSaver) {
        mutableStateOf(existingRecord?.vehicleId ?: initialVehicleId)
    }
    val vehicle = vehicles.firstOrNull { it.id == chosenVehicleId }
    fun label(v: Vehicle): String = MaintenanceRules.vehicleContextLabel(v, nicknames[v.id])
    // Only expose internal IDs in the entry UI when labels could be confused.
    fun entryLabel(v: Vehicle): String = label(v) +
        if (vehicles.count { label(it) == label(v) } > 1) " · ID ${v.id}" else ""
    var showVehiclePicker by remember { mutableStateOf(false) }
    // Saveable with the other fields, so a restored form never shows Fuel values under another type.
    var kind by rememberSaveable(existingRecord?.id, initialKind) {
        mutableStateOf(existingRecord?.kind ?: initialKind)
    }
    LaunchedEffect(kind) {
        // Lazy and once per Fuel editor entry. Do not fetch during ordinary Garage navigation.
        if (kind == RecordKind.FUEL && gasFields.readiness == com.kramttocs.lubelink.core.FieldReadiness.UNKNOWN)
            onGasFieldsNeeded()
    }

    val startingDate = remember { LocalDate.now().toString() }
    var original by remember(existingRecord?.id, existingRecord?.kind) {
        mutableStateOf<LubeLoggerEditorApi.EditableSnapshot?>(null)
    }
    // Long text from LubeLogger or a copied record is saved as a marker, not in full.
    fun boundedSaver(limit: Int, field: (EditorDraft) -> String) =
        FormTextSaver.bounded(limit) { (original?.draft ?: copiedDraft)?.let(field) }
    var date by rememberSaveable { mutableStateOf(copiedDraft?.date ?: startingDate) }
    var odometer by rememberSaveable(initialVehicleId, existingRecord?.id,
        stateSaver = boundedSaver(EditorRules.NUMBER_ENTRY_MAX) { it.odometer }) {
        mutableStateOf(copiedDraft?.odometer ?: initialVehicleId?.let { summaries[it] }?.lastOdometer?.takeIf { it > 0 }?.toString().orEmpty())
    }
    var description by rememberSaveable(stateSaver = boundedSaver(ServiceDescriptionRules.MAX_LENGTH) { it.description }) { mutableStateOf(copiedDraft?.description ?: initialReminder?.let { com.kramttocs.lubelink.core.ReminderRecordRules.seedDescription(it) }.orEmpty()) }
    val descriptionStore = remember(currentContext, baseUrl) { ServiceDescriptionStore(currentContext, baseUrl) }
    var favoritesRevision by remember { mutableIntStateOf(0) }
    var showDescriptionPicker by remember { mutableStateOf(false) }
    var cost by rememberSaveable(stateSaver = boundedSaver(EditorRules.NUMBER_ENTRY_MAX) { it.cost }) { mutableStateOf(copiedDraft?.cost ?: "0") }
    var fuelCost by rememberSaveable(stateSaver = boundedSaver(EditorRules.NUMBER_ENTRY_MAX) { it.cost }) { mutableStateOf(if (copiedDraft?.kind == RecordKind.FUEL) copiedDraft.cost else "") }
    var notes by rememberSaveable(stateSaver = boundedSaver(EditorRules.NOTES_MAX) { it.notes }) { mutableStateOf(copiedDraft?.notes ?: initialReminder?.let { com.kramttocs.lubelink.core.ReminderRecordRules.seedNotes(it) }.orEmpty()) }
    // Tag editing is intentionally hidden. Preserve existing server tags during edits;
    // new records use no tags until we revisit this UI.
    var tags by rememberSaveable(stateSaver = boundedSaver(EditorRules.TAGS_SAVED_MAX) { it.tags }) { mutableStateOf("") }
    var gallons by rememberSaveable(stateSaver = boundedSaver(EditorRules.NUMBER_ENTRY_MAX) { it.gallons }) { mutableStateOf(copiedDraft?.gallons ?: "") }
    var filled by rememberSaveable { mutableStateOf(copiedDraft?.filledTank ?: true) }
    var missed by rememberSaveable { mutableStateOf(copiedDraft?.missedFill ?: false) }
    var stationName by rememberSaveable(stateSaver = boundedSaver(EditorRules.STATION_NAME_MAX) { it.stationName }) {
        mutableStateOf(if (gasFields.readiness == com.kramttocs.lubelink.core.FieldReadiness.MISSING)
            "" else copiedDraft?.stationName.orEmpty())
    }
    var stationAddress by rememberSaveable(stateSaver = boundedSaver(EditorRules.STATION_ADDRESS_MAX) { it.stationAddress }) {
        mutableStateOf(if (gasFields.readiness == com.kramttocs.lubelink.core.FieldReadiness.MISSING)
            "" else copiedDraft?.stationAddress.orEmpty())
    }
    var showStationAddress by rememberSaveable { mutableStateOf(!copiedDraft?.stationAddress.isNullOrBlank()) }
    val copiedStationWasSet = copySource != null &&
        (copiedDraft?.stationName?.isNotBlank() == true || copiedDraft?.stationAddress?.isNotBlank() == true)
    var stationValuesOmitted by rememberSaveable {
        mutableStateOf(copiedStationWasSet &&
            gasFields.readiness == com.kramttocs.lubelink.core.FieldReadiness.MISSING)
    }
    var showStationPicker by rememberSaveable { mutableStateOf(false) }
    /** Replaces restored markers with [from]'s values; returns the fields whose edits were not kept. */
    fun resolveRestoredText(from: EditorDraft?): List<String> {
        val lost = mutableListOf<String>()
        fun field(name: String, value: String, of: (EditorDraft) -> String, set: (String) -> Unit) {
            FormTextSaver.resolveSavedText(value, from?.let(of))?.let { set(it.text); if (it.lost) lost += name }
        }
        field("Odometer", odometer, { it.odometer }) { odometer = it }
        field("Description", description, { it.description }) { description = it }
        field("Cost", cost, { it.cost }) { cost = it }
        field("Fuel cost", fuelCost, { it.cost }) { fuelCost = it }
        field("Notes", notes, { it.notes }) { notes = it }
        field("Tags", tags, { it.tags }) { tags = it }
        field("Gallons", gallons, { it.gallons }) { gallons = it }
        field("Station name", stationName, { it.stationName }) { stationName = it }
        field("Station address", stationAddress, { it.stationAddress }) { stationAddress = it }
        return lost
    }
    // An Add or Copy has no server record to wait for, so markers are resolved before the first frame.
    // A restored Copy is a plain Add (its source is not saved), so long copied text is reported lost.
    var notKept by remember { mutableStateOf(if (existingRecord == null) resolveRestoredText(copiedDraft) else emptyList()) }
    var confirm by remember { mutableStateOf(false) }
    val saving = WriteSafety.state is WriteSafety.State.Pending
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(gasFields.readiness, kind, existingRecord?.id) {
        if (EditorRules.shouldClearUnavailableNewFuelStation(
                kind, existingRecord != null, gasFields.readiness, stationName, stationAddress)) {
            // This can happen after an Add form opens, including a plain new Fuel
            // entry or a form changed away from Fuel and back. Keep the user aware
            // of the omission; never clear existing server record metadata.
            stationName = ""
            stationAddress = ""
            showStationAddress = false
            stationValuesOmitted = true
            error = null
        }
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var discardPrompt by remember { mutableStateOf(false) }
    var loading by remember(existingRecord?.id, existingRecord?.kind) {
        mutableStateOf(existingRecord != null)
    }
    var prefilledFor by rememberSaveable(stateSaver = OptionalRecordRefSaver) { mutableStateOf<RecordRef?>(null) }
    // Fingerprint of the server record this Edit was prefilled from. Saved with the fields so that
    // after process death the ORIGINAL state, not a fresh load, decides whether the server changed.
    // A fixed 64 characters, so saved state stays small whatever the record holds.
    var baselineFingerprint by rememberSaveable { mutableStateOf<String?>(null) }
    var serverChanged by remember(existingRecord) { mutableStateOf(false) }
    // Server Notes over the Save limit can't be changed here (a change would be refused), only kept.
    // A function, so the dialog (its own composition) reads `original` itself, never a stale copy.
    // Typing cannot exceed the limit, so longer Notes in an Edit are always the server's.
    fun notesReadOnly() = existingRecord != null &&
        (notes.length > EditorRules.NOTES_MAX || (original?.draft?.notes?.length ?: 0) > EditorRules.NOTES_MAX)
    LaunchedEffect(existingRecord?.id, existingRecord?.kind) {
        if (existingRecord != null) {
            loading = true
            error = null
            try {
                val targetVehicle = vehicle ?: throw IllegalStateException("Vehicle not found")
                val snapshot = editor.loadEditableRecord(targetVehicle, existingRecord)
                val savedFingerprint = baselineFingerprint?.takeIf { prefilledFor == existingRecord }
                original = snapshot
                kind = snapshot.kind
                if (savedFingerprint != null) {
                    // Restored after process death: the saved fields hold the user's unsaved edits.
                    // If the server record still matches the original, the fresh snapshot is that
                    // baseline; if LubeLogger changed meanwhile, fail closed rather than write stale
                    // fields over the newer record.
                    if (LubeLoggerEditorApi.baselineFingerprint(snapshot) != savedFingerprint) serverChanged = true
                    notKept = resolveRestoredText(snapshot.draft)
                    return@LaunchedEffect
                }
                prefilledFor = existingRecord
                baselineFingerprint = LubeLoggerEditorApi.baselineFingerprint(snapshot)
                date = snapshot.draft.date
                odometer = snapshot.draft.odometer
                description = snapshot.draft.description
                cost = snapshot.draft.cost
                fuelCost = snapshot.draft.cost
                notes = snapshot.draft.notes
                tags = snapshot.draft.tags
                gallons = snapshot.draft.gallons
                filled = snapshot.draft.filledTank
                missed = snapshot.draft.missedFill
                stationName = snapshot.draft.stationName
                stationAddress = snapshot.draft.stationAddress
                showStationAddress = stationAddress.isNotBlank()
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { error = safeMessage(e) }
            finally { loading = false }
        }
    }
    fun draft() = EditorDraft(kind, date, odometer, description,
        if (kind == RecordKind.FUEL) fuelCost else cost, notes, tags, gallons, filled, missed,
        if (kind == RecordKind.FUEL) stationName else "",
        if (kind == RecordKind.FUEL) stationAddress else "")
    fun discardOrClose() {
        if (saving) return
        if (loading) { onDismiss(); return } // No editable fields were available yet.
        val initial = original?.draft ?: copiedDraft ?: EditorDraft(
            initialKind, startingDate,
            initialVehicleId?.let { summaries[it] }?.lastOdometer?.takeIf { it > 0 }?.toString().orEmpty(),
            initialReminder?.let { com.kramttocs.lubelink.core.ReminderRecordRules.seedDescription(it) }.orEmpty(),
            if (initialKind == RecordKind.FUEL) "" else "0",
            initialReminder?.let { com.kramttocs.lubelink.core.ReminderRecordRules.seedNotes(it) }.orEmpty(),
            "")
        val unchanged = !loading && (existingRecord == null || original != null) &&
            chosenVehicleId == initialVehicleId &&
            (if (existingRecord != null) !EditorRules.hasPersistedEditChanges(initial, draft())
             else draft() == initial)
        if (unchanged) onDismiss() else discardPrompt = true
    }
    fun stationFieldError(): String? {
        if (kind != RecordKind.FUEL ||
            gasFields.readiness != com.kramttocs.lubelink.core.FieldReadiness.MISSING) return null
        val changed = if (existingRecord == null) stationName.isNotBlank() || stationAddress.isNotBlank()
            else original?.draft?.let { before ->
                stationName.trim() != before.stationName.trim() ||
                    stationAddress.trim() != before.stationAddress.trim()
            } == true
        return if (changed) "Gas Station fields are not configured. Check Settings → Record Fields before saving station changes."
            else null
    }
    fun submitRecord() {
        stationFieldError()?.let { error = it; confirm = false; return }
        // Capture one immutable draft and the actual numeric vehicle ID. The durable
        // write guard, rather than the dialog, owns the HTTP operation and its outcome.
        val problem = EditorRules.validate(draft(), original?.draft)
        if (problem != null) { error = problem; confirm = false; return }
        val targetVehicle = vehicle ?: run { error = "Choose a vehicle"; confirm = false; return }
        if (existingRecord != null && original == null) {
            error = "Original record has not loaded"; confirm = false; return
        }
        if (serverChanged) { confirm = false; return }
        val submittedDraft = draft()
        val submittedSnapshot = original
        if (existingRecord != null && submittedSnapshot != null &&
            !EditorRules.hasPersistedEditChanges(submittedSnapshot.draft, submittedDraft)) {
            error = "No changes to save."
            confirm = false
            return
        }
        val actionDescription = "${if (existingRecord == null) "Add" else "Edit"} ${kind.label} for ${label(targetVehicle)} on $date"
        val accepted = WriteSafety.start(currentContext.applicationContext, actionDescription,
            RecordWriteIntent(if (existingRecord == null) WriteAction.ADD else WriteAction.EDIT, kind)) {
            if (existingRecord == null) editor.addRecord(targetVehicle, targetVehicle.id, submittedDraft)
            else if (editor.updateRecord(targetVehicle, targetVehicle.id,
                    submittedSnapshot ?: throw IllegalArgumentException("Record snapshot missing"), submittedDraft) ==
                EditOutcome.NOTHING_TO_SAVE)
                // Nothing was sent: never reported as saved (the check above normally stops this earlier).
                throw IllegalArgumentException("No changes to save.")
        }
        if (!accepted) error = "Another save must be confirmed first"
        confirm = false
    }
    AlertDialog(
        onDismissRequest = { discardOrClose() },
        // Outside taps never dismiss typed data, system Back uses the same discard protection.
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = true),
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (existingRecord == null) "Add record" else "Edit record",
                        style = MaterialTheme.typography.titleLarge)
                    if (copySource != null) Text("Prefilled copy · Original record unchanged",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (vehicle != null) Text(entryLabel(vehicle), color = Gold,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                HorizontalDivider(color = Gold, thickness = 1.dp)
            }
        },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                if (existingRecord == null && NavigationRules.needsVehicleChoice(initialVehicleId)) {
                    OutlinedButton(onClick = { showVehiclePicker = true },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(vehicle?.let(::entryLabel) ?: "Choose vehicle *", maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Outlined.ChevronRight, contentDescription = null)
                    }
                }
                if (loading) CircularProgressIndicator()
                if (!loading && (existingRecord == null || original != null)) {
                if (existingRecord == null) {
                    RecordKind.entries.chunked(2).forEach { choices ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            choices.forEach { choice ->
                                FilterChip(selected = kind == choice,
                                    onClick = { kind = choice; error = null },
                                    label = { Text(choice.label) }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
                OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.DateRange, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Date: ${LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US))}")
                }
                // OD-50: every field spans the form's width, so all left and right edges line up.
                OutlinedTextField(odometer, { odometer = EditorRules.limitEntry(odometer, it, EditorRules.NUMBER_ENTRY_MAX) }, label = { Text("Odometer / hours") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true)
                if (kind == RecordKind.FUEL) {
                    // Fuel amounts belong together and don't require horizontal scrolling.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(gallons, { gallons = EditorRules.limitEntry(gallons, it, EditorRules.NUMBER_ENTRY_MAX); error = null },
                            label = { Text("US gallons") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                        OutlinedTextField(fuelCost, { fuelCost = EditorRules.limitEntry(fuelCost, it, EditorRules.NUMBER_ENTRY_MAX); error = null },
                            label = { Text("Total cost") },
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                    }
                    if (stationValuesOmitted && existingRecord == null) {
                        Text(if (copySource != null)
                            "Station not copied: Gas Station fields are not configured in LubeLogger."
                            else "Station omitted: Gas Station fields are not configured in LubeLogger.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (gasFields.readiness != com.kramttocs.lubelink.core.FieldReadiness.MISSING) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(stationName, { stationName = EditorRules.limitEntry(stationName, it, EditorRules.STATION_NAME_MAX) },
                            label = { Text("Gas station (optional)") }, keyboardOptions = TextKeyboard,
                            modifier = Modifier.weight(1f), singleLine = true)
                        IconButton(onClick = { showStationPicker = true }) {
                            Icon(Icons.Outlined.LocationOn, contentDescription = "Find or choose gas station", tint = Gold)
                        }
                    }
                    // Optional address stays collapsed on new fuel entries to keep Notes visible.
                    // Previously saved / selected addresses always remain visible and editable.
                    if (showStationAddress || stationAddress.isNotBlank()) {
                        OutlinedTextField(stationAddress, { stationAddress = EditorRules.limitEntry(stationAddress, it, EditorRules.STATION_ADDRESS_MAX) },
                            label = { Text("Station address (optional)") }, keyboardOptions = TextKeyboard,
                            modifier = Modifier.fillMaxWidth(), singleLine = true)
                    } else {
                        AddStationAddressButton { showStationAddress = true }
                    }
                    } else if (existingRecord != null && (stationName.isNotBlank() || stationAddress.isNotBlank())) {
                        // Preserve the original server fields on unrelated edits; do not expose a
                        // selector that cannot reliably save new station data.
                        Text(EditConfirmationRules.existingStationLabel(stationName, stationAddress),
                            style = MaterialTheme.typography.bodySmall, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    }
                    LabeledSwitch("Filled tank", filled, { filled = it })
                    LabeledSwitch("Missed previous fill-up", missed, { missed = it })
                } else {
                    val isFavorite = remember(description, kind, favoritesRevision) {
                        descriptionStore.isFavorite(kind, description)
                    }
                    DescriptionField(description,
                        onChange = { description = EditorRules.limitEntry(description, it, ServiceDescriptionRules.MAX_LENGTH) },
                        onChoose = { showDescriptionPicker = true }, isFavorite = isFavorite,
                        onToggleFavorite = {
                            descriptionStore.setFavorite(kind, description, !isFavorite)
                            favoritesRevision++
                        })
                    OutlinedTextField(cost, { cost = EditorRules.limitEntry(cost, it, EditorRules.NUMBER_ENTRY_MAX); error = null },
                        label = { Text("Cost (0 if untracked)") },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true)
                }
                if (notesReadOnly()) {
                    // Longer than Save allows for a change, and too tall to lay out as a text field.
                    Text("Notes", style = MaterialTheme.typography.labelMedium)
                    Text(notes.take(600), maxLines = 6, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall)
                    Text("These notes are longer than ${EditorRules.NOTES_MAX} characters, so LubeLink keeps " +
                        "them unchanged. Edit them in LubeLogger.", style = MaterialTheme.typography.labelSmall)
                } else OutlinedTextField(notes, { notes = EditorRules.limitEntry(notes, it, EditorRules.NOTES_MAX) },
                    // maxLines: the field scrolls inside instead of growing; 5,000 short lines would
                    // otherwise exceed Compose's layout size limit.
                    label = { Text("Notes") }, keyboardOptions = TextKeyboard, minLines = 2, maxLines = 12, modifier = Modifier.fillMaxWidth(),
                    supportingText = if (notes.length > EditorRules.NOTES_MAX - 500) {
                        { Text("${notes.length} / ${EditorRules.NOTES_MAX}") }
                    } else null)
                if (copySource != null && CopyRecordRules.notesTooLongToCopy(copySource)) Text(
                    "The original's notes are longer than ${EditorRules.NOTES_MAX} characters and were not copied.",
                    style = MaterialTheme.typography.labelSmall)
                if (initialReminder != null && com.kramttocs.lubelink.core.ReminderRecordRules.notesTooLongToSeed(initialReminder)) Text(
                    "The reminder's notes are longer than ${EditorRules.NOTES_MAX} characters and were not copied. " +
                        "The reminder in LubeLogger is unchanged.",
                    style = MaterialTheme.typography.labelSmall)
                if (kind == RecordKind.SERVICE && existingRecord == null) {
                    Text(initialReminder?.let(MaintenanceRules::reminderFollowUp)
                            ?: "Saving a service record doesn't automatically complete or advance reminders. Manage them in LubeLogger.",
                        style = MaterialTheme.typography.labelSmall)
                }
                }
                if (serverChanged) Text("This record was changed in LubeLogger after you started editing. " +
                    "Your unsaved changes are still shown so you can note them, but they can't be saved " +
                    "over the newer version. Close and reopen Edit to start from it.",
                    color = MaterialTheme.colorScheme.error)
                if (notKept.isNotEmpty()) Text("Android closed LubeLink while this form was open. Your changes to " +
                    "${notKept.joinToString()} were too long to keep, so ${if (notKept.size == 1) "it shows" else "they show"} " +
                    "${if (existingRecord != null) "the value in LubeLogger" else "no text"}. Check before saving.",
                    color = MaterialTheme.colorScheme.error)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = { discardOrClose() }, enabled = !saving) { Text("Cancel") } },
        confirmButton = { Button(enabled = !loading && !saving && !serverChanged && vehicle != null &&
            (existingRecord == null || original != null) && WriteSafety.state is WriteSafety.State.Idle &&
            WriteSafety.unresolved(currentContext) == null,
            onClick = {
                error = stationFieldError() ?: EditorRules.validate(draft(), original?.draft)
                if (error == null) {
                    if (existingRecord == null) submitRecord()
                    else {
                        val loaded = original
                        if (loaded != null && !EditorRules.hasPersistedEditChanges(loaded.draft, draft()))
                            error = "No changes to save."
                        else confirm = true
                    }
                }
            }) { Text(if (existingRecord == null) "Save" else "Review changes") } }
    )
    if (discardPrompt) AlertDialog(
        onDismissRequest = { discardPrompt = false },
        title = { LubeDialogTitle("Discard record changes?") },
        text = { DialogBody { Text("Anything entered in this form that has not been saved will be lost. No server record will be changed.") } },
        dismissButton = { TextButton(onClick = { discardPrompt = false }) { Text("Keep editing") } },
        confirmButton = { TextButton(onClick = { discardPrompt = false; onDismiss() }) { Text("Discard") } }
    )
    if (showDescriptionPicker && kind != RecordKind.FUEL) {
        ServiceDescriptionPickerDialog(kind, description, descriptionRecords,
            descriptionStore, descriptionsLoading,
            onChoose = { description = it; showDescriptionPicker = false },
            onFavoritesChanged = { favoritesRevision++ },
            onDismiss = { showDescriptionPicker = false })
    }
    if (showVehiclePicker) AlertDialog(
        onDismissRequest = { showVehiclePicker = false },
        title = { LubeDialogTitle("Choose vehicle") },
        text = {
            LazyColumn(Modifier.heightIn(max = 370.dp)) {
                items(vehicles, key = { it.id.value }) { candidate ->
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = {
                        chosenVehicleId = candidate.id
                        odometer = summaries[candidate.id]?.lastOdometer?.takeIf { it > 0 }?.toString().orEmpty()
                        showVehiclePicker = false
                    }) {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
                            Text(label(candidate), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(MaintenanceRules.mileageLabel(summaries[candidate.id]?.lastOdometer, candidate.useHours),
                                style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { showVehiclePicker = false }) { Text("Cancel") } }
    )
    if (showStationPicker && gasFields.readiness != com.kramttocs.lubelink.core.FieldReadiness.MISSING) {
        FuelStationPickerDialog(stationName, stationAddress, baseUrl, editorKey, onChoose = { station ->
            stationName = station.name
            stationAddress = station.address
            showStationPicker = false
        }, onDismiss = { showStationPicker = false })
    }
    if (showDatePicker) {
        val pickerState = rememberDatePickerState(
            initialSelectedDateMillis = EditorRules.dateToPickerMillis(date)
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { selected ->
                        date = EditorRules.dateFromPickerMillis(selected)
                        error = null
                    }
                    showDatePicker = false
                }, enabled = pickerState.selectedDateMillis != null) { Text("Use date") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } }
        ) { DatePicker(state = pickerState) }
    }
    if (confirm) AlertDialog(onDismissRequest = { if (!saving) confirm = false },
        title = { LubeDialogTitle("Save changes",
            vehicleLabel = vehicle?.let(::entryLabel) ?: "Selected vehicle") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val originalDraft = original?.draft
                if (originalDraft != null) {
                    val changes = EditorRules.persistedEditChanges(originalDraft, draft())
                    Text("These changes will replace values in LubeLogger:",
                        style = MaterialTheme.typography.bodySmall)
                    EditChangeReview(changes, vehicle?.useHours == true)
                    Text("Other fields and files are kept as LubeLogger had them just before saving.",
                        style = MaterialTheme.typography.labelSmall)
                }
                if (kind == RecordKind.SERVICE) {
                    Text(initialReminder?.let(MaintenanceRules::reminderFollowUp)
                            ?: "Saving a service record doesn't automatically complete or advance reminders. Manage them in LubeLogger.",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        dismissButton = { TextButton(enabled = !saving, onClick = { confirm = false }) { Text("Go back") } },
        confirmButton = { Button(enabled = !saving && WriteSafety.state is WriteSafety.State.Idle &&
                WriteSafety.unresolved(currentContext) == null, onClick = { submitRecord() }) {
            Text("Save changes")
        } }
    )
}

/** One presentation implementation for the actual confirmation and instrumented UI tests. */
@Composable
internal fun EditChangeReview(changes: List<EditorRules.PersistedChange>, useHours: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        changes.forEach { change ->
            Text(EditConfirmationRules.changeLabel(change, useHours),
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Optional per-record description picker. Select old descriptions, star local favorites, or type freely. */
@Composable
private fun ServiceDescriptionPickerDialog(
    kind: RecordKind, currentDescription: String, records: List<ServiceRecord>,
    store: ServiceDescriptionStore, loading: Boolean,
    onChoose: (String) -> Unit, onFavoritesChanged: () -> Unit, onDismiss: () -> Unit
) {
    var search by remember(kind) { mutableStateOf("") }
    var favoriteVersion by remember(kind) { mutableIntStateOf(0) }
    val favorites = remember(kind, favoriteVersion) { store.favorites(kind) }
    val suggestions = remember(kind, records, favorites) {
        ServiceDescriptionRules.suggestions(kind, records, favorites)
    }
    val results = suggestions.filter { it.contains(search.trim(), ignoreCase = true) }.take(70)
    val current = ServiceDescriptionRules.normalize(currentDescription)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose ${kind.label.lowercase()} description", style = MaterialTheme.typography.titleLarge)
                HorizontalDivider(color = Gold, thickness = 1.dp)
            }
        },
        text = {
            DialogBody(spacing = 8.dp) {
                OutlinedTextField(search, { search = it.take(ServiceDescriptionRules.MAX_LENGTH) },
                    label = { Text("Search or enter new") }, keyboardOptions = TextKeyboard, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) })
                if (loading && suggestions.isEmpty()) Text("Loading previous descriptions…",
                    style = MaterialTheme.typography.bodySmall)
                else if (results.isEmpty()) Text("No matching descriptions. You can use your own text.",
                    style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 330.dp)) {
                    items(results, key = { it.lowercase(Locale.ROOT) }) { suggestion ->
                        val starred = favorites.any { it.equals(suggestion, ignoreCase = true) }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(suggestion, modifier = Modifier.weight(1f).clickable { onChoose(suggestion) }
                                .padding(vertical = 12.dp), maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = {
                                store.setFavorite(kind, suggestion, !starred)
                                favoriteVersion++
                                onFavoritesChanged()
                            }) {
                                Icon(if (starred) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                    contentDescription = if (starred) "Remove favorite" else "Add favorite",
                                    tint = if (starred) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        HorizontalDivider()
                    }
                }
                Text("Gold stars are favorites saved on this phone. Other entries come from your LubeLogger history.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            val typed = ServiceDescriptionRules.normalize(search)
            if (typed.isNotBlank()) TextButton(onClick = { onChoose(typed) }) { Text("Use typed text") }
            else if (current.isNotEmpty()) TextButton(onClick = { onChoose(current) }) { Text("Keep current") }
        }
    )
}

/** Shows the optional station-address field. Rectangular, so a label wrapped at large text is never cut by a
 * button's rounded corners; no side padding, so it lines up with the fields above it. */
@Composable
internal fun AddStationAddressButton(onClick: () -> Unit) {
    TextButton(onClick = onClick, shape = androidx.compose.ui.graphics.RectangleShape,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) {
        Text("+ Add station address (optional)", style = MaterialTheme.typography.labelMedium)
    }
}

/** The Description field and its favourite star. Beside the star the field is narrow, so at the largest text its label
 * is shortened with "…" rather than broken mid-word. */
@Composable
internal fun DescriptionField(description: String, onChange: (String) -> Unit, onChoose: () -> Unit,
                              isFavorite: Boolean, onToggleFavorite: () -> Unit) {
    // OD-45: the favourite star sits inside the field, just left of the ▾ arrow, so the form keeps one line and
    // "Description" stays whole at the largest text. Its description says what a tap does.
    OutlinedTextField(description, onChange,
        label = { Text("Description", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        keyboardOptions = TextKeyboard, modifier = Modifier.fillMaxWidth(),
        trailingIcon = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = ServiceDescriptionRules.normalize(description).isNotEmpty(),
                    onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
                    Icon(if (isFavorite) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (isFavorite) "Remove favorite description" else "Save description as favorite",
                        tint = if (isFavorite) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = onChoose, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Choose a description", tint = Gold)
                }
            }
        }, singleLine = true)
}
