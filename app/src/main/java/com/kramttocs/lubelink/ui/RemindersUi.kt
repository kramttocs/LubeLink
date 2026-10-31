package com.kramttocs.lubelink.ui

import androidx.compose.foundation.border
import com.kramttocs.lubelink.core.ReminderSuggestions
import com.kramttocs.lubelink.core.ReminderId
import com.kramttocs.lubelink.core.VehicleId
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.GarageSection
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.NavigationRules
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

@Composable
internal fun RemindersScreen(data: GarageData, vehicleId: VehicleId?, serverUrl: String,
                            nicknames: Map<VehicleId, String>, onOpenVehicle: (VehicleId) -> Unit,
                            onLogReminder: ((MaintenanceReminder) -> Unit)?,
                            highlightReminderId: ReminderId? = null, onHighlighted: () -> Unit = {},
                            suggest: Boolean = true) {
    val uriHandler = LocalUriHandler.current
    val vehiclesById = data.vehicles.associateBy { it.id }
    var reminderDetail by remember { mutableStateOf<MaintenanceReminder?>(null) }
    LaunchedEffect(highlightReminderId, data.reminders) {
        if (highlightReminderId != null) {
            data.reminders.firstOrNull { it.id == highlightReminderId &&
                (vehicleId == null || it.vehicleId == vehicleId) }?.let {
                reminderDetail = it
                onHighlighted()
            }
        }
    }
    val reminders = MaintenanceRules.orderedReminders(data.reminders.filter { NavigationRules.includes(vehicleId, it.vehicleId) })
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        ContextHeader("Reminders", vehicleId?.let { id ->
            vehiclesById[id]?.let { MaintenanceRules.vehicleTitle(it, nicknames[id]) }
        } ?: "All vehicles",
            MaintenanceRules.contextReading(vehicleId?.let { vehiclesById[it] }, vehicleId?.let { data.summaries[it] }))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = ScreenEndPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Reminders are managed in LubeLogger.",
                    style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { uriHandler.openUri(serverUrl) }) {
                    Text("Manage reminders in LubeLogger ↗")
                }
            }
            // OD-52, OD-58: a vehicle with no reminders gets common ones to set up in LubeLogger, unless the
            // suggestions are turned off in Settings (OD-59).
            val selected = vehicleId?.let { vehiclesById[it] }
            // Reminders that did not load are never shown as none (OD-24); the notice above the screen offers Retry.
            if (GarageSection.REMINDERS in data.missing) item { Text(REMINDERS_NOT_LOADED) }
            else if (selected != null && ReminderSuggestions.shows(selected, data.reminders, data.missing)) item {
                if (suggest) SuggestedReminders(ReminderSuggestions.forVehicle(selected)) { uriHandler.openUri(serverUrl) }
                else Text("No reminders for this vehicle yet.")
            } else if (reminders.isEmpty()) item { Text("No reminders in this view") }
            items(reminders, key = { "${it.vehicleId}-${it.id}" }) { reminder ->
                val vehicle = vehiclesById[reminder.vehicleId]
                // In All vehicles, distinguish similarly named reminders by showing both
                // the local nickname (if one exists) and the authoritative year/make/model.
                // Inside a selected vehicle no repeated identification is necessary.
                val owningVehicleLabel = if (vehicleId == null && vehicle != null) {
                    MaintenanceRules.vehicleContextLabel(vehicle, nicknames[vehicle.id])
                } else ""
                ReminderRow(reminder, owningVehicleLabel,
                    vehicle?.useHours ?: false, onClick = { reminderDetail = reminder })
            }
        }
    }
    reminderDetail?.let { reminder ->
        ReminderDetailsDialog(reminder, vehiclesById[reminder.vehicleId]?.useHours ?: false,
            vehicleLabel = if (vehicleId == null) vehiclesById[reminder.vehicleId]?.let {
                MaintenanceRules.vehicleContextLabel(it, nicknames[it.id])
            } else null,
            onOpenVehicle = if (vehicleId == null) ({
                reminderDetail = null
                onOpenVehicle(reminder.vehicleId)
            }) else null,
            onLogService = onLogReminder?.let { start -> ({ reminderDetail = null; start(reminder) }) }
        ) { reminderDetail = null }
    }
}

@Composable
private fun SuggestedReminders(suggestions: List<String>, onSetUp: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("No reminders for this vehicle yet.", fontWeight = FontWeight.SemiBold)
        Column(Modifier.fillMaxWidth().border(1.dp, Gold.copy(alpha = .4f), panelShape).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Common reminders to set up", fontWeight = FontWeight.SemiBold)
            Text(suggestions.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onSetUp) { Text("Set up in LubeLogger ↗") }
            Text("LubeLink doesn't create reminders; this opens LubeLogger.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ReminderStatus(urgency: String) {
    val rank = MaintenanceRules.urgencyOrder(urgency)
    val foreground = when (rank) {
        0, 1 -> Color(0xFFFFB4AB)
        2 -> Gold
        else -> Color(0xFFA7D8B3)
    }
    val background = when (rank) {
        0, 1 -> Color(0xFF51292B)
        2 -> Color(0xFF493B1B)
        else -> Color(0xFF263C32)
    }
    Box(Modifier.padding(start = 8.dp).background(background, statusPillShape)
        .padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(MaintenanceRules.urgencyLabel(urgency), color = foreground,
            style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
internal fun ReminderRow(reminder: MaintenanceReminder, vehicleName: String, useHours: Boolean,
                        onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(MaintenanceRules.lineForDisplay(reminder.description), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                ReminderStatus(reminder.urgency)
            }
            if (vehicleName.isNotBlank()) Text(vehicleName, color = Gold,
                style = MaterialTheme.typography.bodySmall, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            val presentation = MaintenanceRules.reminderPresentation(reminder, useHours)
            presentation.targets.forEach { Text(it) }
            presentation.progress.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (!presentation.metricKnown) Text("Reminder schedule unavailable",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ReminderDetailsDialog(reminder: MaintenanceReminder, useHours: Boolean,
                                  vehicleLabel: String? = null,
                                  onOpenVehicle: (() -> Unit)? = null,
                                  onLogService: (() -> Unit)? = null,
                                  dismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = dismiss,
        title = { LubeDialogTitle(MaintenanceRules.lineForDisplay(reminder.description),
            vehicleLabel = vehicleLabel, onOpenVehicle = onOpenVehicle) },
        text = {
            Column(Modifier.heightIn(max = 410.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReminderStatus(reminder.urgency)
                val presentation = MaintenanceRules.reminderPresentation(reminder, useHours)
                presentation.targets.forEach { Text(it) }
                presentation.progress.forEach { Text(it, color = Gold) }
                if (!presentation.metricKnown) Text("Reminder schedule unavailable")
                reminder.notes.takeIf { it.isNotBlank() }?.let {
                    HorizontalDivider()
                    Text(MaintenanceRules.notesForDisplay(it))
                }
                Text(MaintenanceRules.reminderFollowUp(reminder),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Close") } },
        confirmButton = {
            if (onLogService != null) Button(onClick = onLogService) { Text("Add service record") }
        }
    )
}
