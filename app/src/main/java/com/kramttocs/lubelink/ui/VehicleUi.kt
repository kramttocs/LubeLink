package com.kramttocs.lubelink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Info
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.GarageSection
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleDetailsRules
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun VehicleScreen(vehicle: Vehicle, data: GarageData, api: LubeLoggerApi,
                          history: List<ServiceRecord>, historyLoading: Boolean, historyIncomplete: Boolean,
                          refreshGeneration: Int,
                          nickname: String?,
                          onHistory: () -> Unit,
                          onReports: () -> Unit,
                          onAdd: (() -> Unit)?,
                          onLogReminder: ((MaintenanceReminder) -> Unit)?,
                          onEdit: ((ServiceRecord) -> Unit)?,
                          onCopy: ((ServiceRecord) -> Unit)?,
                          onDelete: ((ServiceRecord) -> Unit)?) {
    val summary = data.summaries[vehicle.id]
    val reminders = MaintenanceRules.orderedReminders(data.reminders.filter { it.vehicleId == vehicle.id })
    var detail by remember(vehicle.id) { mutableStateOf<ServiceRecord?>(null) }
    var reminderDetail by remember(vehicle.id) { mutableStateOf<MaintenanceReminder?>(null) }
    var showDetails by remember(vehicle.id) { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = ScreenEndPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Spacer(Modifier.height(8.dp))
            VehiclePicture(vehicle, api, refreshGeneration, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(MaintenanceRules.vehicleTitle(vehicle, nickname),
                        style = MaterialTheme.typography.headlineSmall)
                    MaintenanceRules.vehicleSubtitle(vehicle, nickname)?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                IconButton(onClick = { showDetails = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "Vehicle details", tint = Gold)
                }
                IconButton(onClick = onReports) {
                    Icon(Icons.Outlined.BarChart, contentDescription = "Vehicle reports", tint = Gold)
                }
            }
            Text(MaintenanceRules.mileageLabel(summary?.lastOdometer, vehicle.useHours), color = Gold)
        }
        item {
            // Reminders already have a dedicated bottom tab; avoid a duplicate navigation link.
            Text("Maintenance", style = MaterialTheme.typography.titleLarge)
            if (GarageSection.REMINDERS in data.missing) Text(REMINDERS_NOT_LOADED)
            else if (reminders.isEmpty()) Text("No reminders configured for this vehicle")
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                reminders.take(2).forEach { reminder ->
                    ReminderRow(reminder, "", vehicle.useHours, onClick = { reminderDetail = reminder })
                }
            }
        }
        if (onAdd != null) item {
            OutlinedButton(onClick = onAdd, modifier = Modifier.fillMaxWidth()) {
                Text("Add record")
            }
        }
        item { SectionTitle("Recent history", "All history", onHistory) }
        // OD-37: say what is happening rather than showing nothing. A failed or partial load is explained by its
        // own notice, so it is never called "No records yet".
        if (history.isEmpty()) item {
            when {
                historyLoading -> Text("Loading…")
                !historyIncomplete -> Text("No records yet")
            }
        }
        items(history.take(5), key = { "${it.kind}-${it.id}" }) { record ->
            RecordRow(record, vehicle.useHours) { detail = record }
        }
    }
    detail?.let { record ->
        RecordDetailsDialog(record, vehicle.useHours, api,
            onDismiss = { detail = null },
            onEdit = onEdit?.let { edit -> ({ detail = null; edit(record) }) },
            onCopy = onCopy?.let { copy -> ({ detail = null; copy(record) }) },
            onDelete = onDelete?.let { remove -> ({ detail = null; remove(record) }) })
    }
    if (showDetails) VehicleDetailsDialog(vehicle, MaintenanceRules.vehicleTitle(vehicle, nickname)) { showDetails = false }
    reminderDetail?.let { reminder ->
        ReminderDetailsDialog(reminder, vehicle.useHours,
            onLogService = onLogReminder?.let { start -> ({ reminderDetail = null; start(reminder) }) }
        ) { reminderDetail = null }
    }
}

/** OD-49: the read-only details LubeLogger sends for [vehicle]; they are edited in LubeLogger. */
@Composable
internal fun VehicleDetailsDialog(vehicle: Vehicle, vehicleLabel: String, dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { LubeDialogTitle("Vehicle details", vehicleLabel = vehicleLabel) },
        text = {
            DialogBody(spacing = 8.dp) { VehicleDetailsBody(vehicle) }
        },
        confirmButton = { TextButton(onClick = dismiss) { Text("Close") } }
    )
}

/** The details dialog's rows. Label beside value at normal text; label above value once the text is enlarged, so a
 * label is never broken in the middle of a word in a phone-width dialog. */
@Composable
internal fun VehicleDetailsBody(vehicle: Vehicle) {
    val details = vehicle.details
    if (details == null) { Text("LubeLink could not read this vehicle's details from LubeLogger."); return }
    val stacked = LocalDensity.current.fontScale > 1f
    VehicleDetailsRules.rows(vehicle, details).forEach { (label, value) ->
        if (stacked) Column {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value)
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.8f))
            Text(value, modifier = Modifier.weight(1.5f))
        }
    }
    Text("From LubeLogger; edit them there.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Visible local-only editor; never invokes a LubeLogger write endpoint. */
@Composable
internal fun NicknameDialog(vehicle: Vehicle, current: String, save: (String) -> Unit, dismiss: () -> Unit) {
    var draft by remember(vehicle.id, current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { LubeDialogTitle("Vehicle nickname", vehicleLabel = vehicle.displayName) },
        text = {
            DialogBody(spacing = 10.dp) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { if (it.length <= 48) draft = it },
                    label = { Text("Nickname (max 48)") },
                    keyboardOptions = TextKeyboard,
                    placeholder = { Text("e.g. Work truck, Family car") },
                    supportingText = { Text("Only shown in LubeLink on this device. Not saved to LubeLogger.") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = { save(draft); dismiss() }) { Text("Save") } }
    )
}

@Composable
private fun SectionTitle(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        TextButton(onClick = onClick) { Text(action) }
    }
}
