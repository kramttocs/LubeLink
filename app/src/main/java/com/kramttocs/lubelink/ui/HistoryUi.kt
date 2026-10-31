package com.kramttocs.lubelink.ui

import com.kramttocs.lubelink.core.VehicleId
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.core.content.FileProvider
import android.content.Intent
import android.graphics.BitmapFactory
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.webkit.MimeTypeMap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.AttachmentReader
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.HistoryMonth
import com.kramttocs.lubelink.core.HistoryMonthRules
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.HistoryFilter
import com.kramttocs.lubelink.core.NavigationRules
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import java.io.File
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun HistoryScreen(data: GarageData, selectedId: VehicleId?,
                          records: List<ServiceRecord>, error: String?, loading: Boolean,
                          includeFuel: Boolean, nicknames: Map<VehicleId, String>,
                          api: LubeLoggerApi, typeFilter: HistoryFilter, onFilterChanged: (HistoryFilter) -> Unit,
                          newestFirst: Boolean,
                          onAdd: ((RecordKind) -> Unit)?, onEdit: ((ServiceRecord) -> Unit)?,
                          onCopy: ((ServiceRecord) -> Unit)?,
                          onDelete: ((ServiceRecord) -> Unit)?,
                          onOpenVehicle: (VehicleId) -> Unit, retry: () -> Unit,
                          onOpenReminders: () -> Unit = {}, missingKinds: Set<RecordKind> = emptySet()) {
    var detail by remember { mutableStateOf<ServiceRecord?>(null) }
    val vehicles = remember(data) { data.vehicles.associateBy { it.id } }
    val filtered = remember(records, typeFilter, newestFirst) {
        val matching = records.filter { typeFilter.kind == null || it.kind == typeFilter.kind }
        val sorted = MaintenanceRules.orderedHistory(matching)
        if (newestFirst) sorted else sorted.asReversed()
    }
    // OD-52: a header above each month, with its total and record count.
    val months = remember(filtered, missingKinds, typeFilter) {
        HistoryMonthRules.months(filtered, HistoryMonthRules.missingShown(missingKinds, typeFilter.kind))
    }
    val contextName = selectedId?.let { id ->
        vehicles[id]?.let { MaintenanceRules.vehicleTitle(it, nicknames[id]) }
    } ?: "All vehicles"
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ContextHeader("History", contextName,
            MaintenanceRules.contextReading(selectedId?.let { vehicles[it] }, selectedId?.let { data.summaries[it] }))
        // OD-49: for one vehicle, a single panel when reminders are due, linking to Reminders.
        val due = selectedId?.let { MaintenanceRules.dueReminders(data.reminders, it) }.orEmpty()
        if (due.isNotEmpty()) DueRemindersPanel(due, onOpenReminders)
        val filters = if (includeFuel) HistoryFilter.entries else HistoryFilter.entries.filterNot { it == HistoryFilter.FUEL }
        LaunchedEffect(includeFuel) { if (typeFilter !in filters) onFilterChanged(HistoryFilter.ALL) }
        // Show every category without requiring people to discover horizontal scrolling.
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            filters.chunked(if (includeFuel) 3 else 2).forEach { group ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    group.forEach { filter ->
                        FilterChip(selected = typeFilter == filter, onClick = { onFilterChanged(filter) },
                            label = { Text(filter.label, maxLines = 1) }, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
        if (onAdd != null) OutlinedButton(
            onClick = {
                onAdd(NavigationRules.initialKind(typeFilter))
            }, modifier = Modifier.fillMaxWidth()
        ) { Text("Add record") }
        when {
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.error)
                Button(onClick = retry) { Text("Retry") }
            }
            loading -> CenterMessage("Loading history…", spinning = true)
            filtered.isEmpty() -> CenterMessage("No matching history records")
            else -> LazyColumn(contentPadding = ScreenEndPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                months.forEachIndexed { index, month ->
                    item(key = "month-$index-${month.yearMonth}") { MonthHeader(month) }
                    items(month.records, key = { "${it.vehicleId}-${it.kind}-${it.id}" }) { record ->
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            if (selectedId == null) Text(
                                vehicles[record.vehicleId]?.let {
                                    MaintenanceRules.vehicleContextLabel(it, nicknames[it.id])
                                } ?: "Vehicle", color = Gold, style = MaterialTheme.typography.labelMedium)
                            RecordRow(record, vehicles[record.vehicleId]?.useHours ?: false) {
                                detail = record
                            }
                        }
                    }
                }
            }
        }
    }
    detail?.let { record ->
        RecordDetailsDialog(record, vehicles[record.vehicleId]?.useHours ?: false, api,
            vehicleLabel = if (selectedId == null) vehicles[record.vehicleId]?.let {
                MaintenanceRules.vehicleContextLabel(it, nicknames[it.id])
            } else null,
            onOpenVehicle = if (selectedId == null) ({ detail = null; onOpenVehicle(record.vehicleId) }) else null,
            onDismiss = { detail = null },
            onEdit = onEdit?.let { edit -> ({ detail = null; edit(record) }) },
            onCopy = onCopy?.let { copy -> ({ detail = null; copy(record) }) },
            onDelete = onDelete?.let { remove -> ({ detail = null; remove(record) }) })
    }
}

@Composable
private fun MonthHeader(month: HistoryMonth) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp).semantics(mergeDescendants = true) { heading() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(HistoryMonthRules.title(month), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(HistoryMonthRules.summary(month), style = MaterialTheme.typography.labelMedium, color = Gold)
        }
        HorizontalDivider(Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DueRemindersPanel(due: List<MaintenanceReminder>, onOpenReminders: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp).background(Panel, panelShape)
        .padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(MaintenanceRules.dueCountLabel(due.size), fontWeight = FontWeight.SemiBold)
            val first = due.first()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(MaintenanceRules.lineForDisplay(first.description), style = MaterialTheme.typography.bodySmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                ReminderStatus(first.urgency)
            }
        }
        TextButton(onClick = onOpenReminders) { Text("Reminders") }
    }
}

@Composable
internal fun ContextHeader(title: String, contextName: String, reading: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
        // OD-49: the selected vehicle's current reading as a small second line under its name.
        Column(Modifier.weight(.65f), horizontalAlignment = Alignment.End) {
            Text(contextName, color = Gold, style = MaterialTheme.typography.labelMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
            reading?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.End,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Shared details: authenticated attachments and optional navigation to owning vehicle. */
@Composable
internal fun RecordDetailsDialog(record: ServiceRecord, useHours: Boolean,
                                api: AttachmentReader,
                                vehicleLabel: String? = null,
                                onOpenVehicle: (() -> Unit)? = null,
                                onDismiss: () -> Unit, onEdit: (() -> Unit)? = null,
                                onCopy: (() -> Unit)? = null, onDelete: (() -> Unit)? = null) {
    val context = LocalContext.current
    var moreExpanded by remember(record.id, record.kind) { mutableStateOf(false) }
    // One download per open record (OD-42): another record or closing the dialog cancels it, and a stale result
    // never opens a preview or a viewer.
    val download = rememberLatestCall(record.id, record.kind)
    var attachmentError by remember(record.id, record.kind) { mutableStateOf<String?>(null) }
    var preview by remember(record.id, record.kind) {
        mutableStateOf<Pair<String, android.graphics.Bitmap>?>(null)
    }
    fun openAttachment(attachment: com.kramttocs.lubelink.core.Attachment) {
        if (download.busy) return
        attachmentError = null
        download.run({
            val ext = attachment.name.substringAfterLast('.', "").lowercase(Locale.US)
                .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) } ?: "bin"
            val allowed = setOf("pdf", "jpg", "jpeg", "png", "gif", "webp", "heic", "txt", "csv", "docx", "xlsx")
            if (ext !in allowed) throw IllegalArgumentException("This attachment type cannot be opened safely")
            // Validate metadata before any network request or 24 MB download.
            val bytes = api.downloadAttachment(attachment.location)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                ?: when (ext) { "csv" -> "text/csv"; "heic" -> "image/heic"; else -> "application/octet-stream" }
            val decoded = if (!mime.startsWith("image/")) null else withContext(Dispatchers.Default) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
                    BitmapFactory.Options().apply { inSampleSize = sample })
            }
            // Unsupported HEIC/large images may still be supported by an installed viewer, so they continue to the
            // existing safe FileProvider path instead of failing. Only documents sent to an external viewer need a
            // temporary file.
            if (decoded != null) OpenedAttachment(decoded, null, mime) else {
                val cacheFile = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "lubelink_attachments")
                    if (!dir.exists() && !dir.mkdirs()) throw IllegalStateException("Attachment cache unavailable")
                    File(dir, "${UUID.randomUUID()}.$ext").apply { writeBytes(bytes) }
                }
                OpenedAttachment(null, cacheFile, mime)
            }
        }, onSuccess = { opened ->
            if (opened.preview != null) preview = attachment.name to opened.preview
            else if (opened.file != null) {
                val uri = FileProvider.getUriForFile(context,
                    "${context.packageName}.fileprovider", opened.file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, opened.mime)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                try { context.startActivity(intent) }
                catch (_: android.content.ActivityNotFoundException) {
                    attachmentError = "No app installed to open this attachment"
                }
            }
        }, onFailure = {
            attachmentError = safeMessage(it)
        })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(MaintenanceRules.lineForDisplay(record.description.ifBlank { record.kind.label }),
                        modifier = Modifier.weight(1f))
                    if (onCopy != null || onDelete != null) {
                        androidx.compose.foundation.layout.Box {
                            IconButton(onClick = { moreExpanded = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "More record actions")
                            }
                            DropdownMenu(expanded = moreExpanded, onDismissRequest = { moreExpanded = false }) {
                                if (onCopy != null) DropdownMenuItem(
                                    text = { Text("Copy as new") },
                                    leadingIcon = { Icon(Icons.Outlined.ContentCopy, contentDescription = null) },
                                    onClick = { moreExpanded = false; onCopy() })
                                if (onDelete != null) DropdownMenuItem(
                                    text = { Text("Delete record", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error) },
                                    onClick = { moreExpanded = false; onDelete() })
                            }
                        }
                    }
                }
                LubeDialogVehicleLine(vehicleLabel, onOpenVehicle)
                HorizontalDivider(color = Gold, thickness = 1.dp)
            }
        },
        text = {
            Column(Modifier.heightIn(max = 410.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(MaintenanceRules.recordDateLabel(record.date))
                Text(MaintenanceRules.mileageLabel(record.odometer, useHours))
                if (record.kind == RecordKind.FUEL) {
                    MaintenanceRules.mpgLabel(record.fuelEconomy)?.let {
                        Text(it, color = Gold, fontWeight = FontWeight.SemiBold)
                    }
                    MaintenanceRules.gallonsLabel(record.fuelConsumed)?.let { Text(it) }
                    if (record.fuelStation.isNotBlank()) Text("Station: ${MaintenanceRules.lineForDisplay(record.fuelStation)}")
                    if (record.fuelStationAddress.isNotBlank()) Text(MaintenanceRules.lineForDisplay(record.fuelStationAddress),
                        style = MaterialTheme.typography.bodySmall)
                }
                if (record.notes.isNotBlank()) Text(MaintenanceRules.notesForDisplay(record.notes))
                if (record.kind == RecordKind.FUEL || record.cost.toDoubleOrNull()?.let { it > 0 } == true) {
                    MaintenanceRules.currencyLabel(record.cost)?.let { Text("Cost: $it") }
                }
                if (record.attachments.isNotEmpty()) {
                    HorizontalDivider()
                    Text("Attachments (${record.attachments.size})",
                        style = MaterialTheme.typography.labelMedium)
                    record.attachments.forEachIndexed { index, attachment ->
                        TextButton(onClick = { openAttachment(attachment) },
                            enabled = !download.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.AttachFile, contentDescription = null, tint = Gold)
                            Spacer(Modifier.width(5.dp))
                            Text(attachment.name.ifBlank { "Attachment ${index + 1}" },
                                maxLines = 2, modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Start)
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = "Open attachment")
                        }
                    }
                }
                if (download.busy) Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Opening attachment…", style = MaterialTheme.typography.bodySmall)
                }
                attachmentError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = if (onEdit != null) ({
            TextButton(onClick = onEdit) { Text("Edit record") }
        }) else null,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
    preview?.let { (name, bitmap) ->
        AlertDialog(onDismissRequest = { preview = null },
            title = { LubeDialogTitle(name, maxLines = 2) },
            text = {
                Image(bitmap.asImageBitmap(), contentDescription = name,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                    contentScale = ContentScale.Fit)
            },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } }
        )
    }
}

@Composable
internal fun RecordRow(record: ServiceRecord, useHours: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DecorativeGlyph(when (record.kind) {
                    RecordKind.SERVICE -> "●"; RecordKind.REPAIR -> "◆"
                    RecordKind.UPGRADE -> "▲"; RecordKind.FUEL -> "◉"
                })
                Spacer(Modifier.width(8.dp))
                Text(MaintenanceRules.lineForDisplay(record.description.ifBlank { record.kind.label }),
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(record.kind.label, style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(6.dp))
            Text("${MaintenanceRules.recordDateLabel(record.date)} · ${MaintenanceRules.mileageLabel(record.odometer, useHours)}",
                style = MaterialTheme.typography.bodySmall)
            if (record.kind == RecordKind.FUEL) {
                val fuelSummary = listOfNotNull(
                    MaintenanceRules.mpgLabel(record.fuelEconomy),
                    MaintenanceRules.gallonsLabel(record.fuelConsumed),
                    MaintenanceRules.currencyLabel(record.cost)
                ).joinToString(" · ")
                if (fuelSummary.isNotBlank()) Text(fuelSummary, color = Gold,
                    style = MaterialTheme.typography.bodySmall)
            }
            if (record.notes.isNotBlank()) Text(record.notes.take(100), maxLines = 2,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A downloaded attachment ready to show: a decoded [preview], or a cached [file] for an external viewer. */
private class OpenedAttachment(val preview: android.graphics.Bitmap?, val file: File?, val mime: String)
