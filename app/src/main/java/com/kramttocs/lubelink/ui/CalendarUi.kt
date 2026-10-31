package com.kramttocs.lubelink.ui

import com.kramttocs.lubelink.core.VehicleId
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.GarageSection
import com.kramttocs.lubelink.core.CalendarRules
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Mobile-first calendar: date-based maintenance reminders and recorded history. */
private val DAY_LABEL = DateTimeFormatter.ofPattern("MMM d, uuuu", Locale.US)

@Composable
internal fun CalendarScreen(data: GarageData, selectedId: VehicleId?, nicknames: Map<VehicleId, String>,
                           records: List<ServiceRecord>, loading: Boolean, error: String?,
                           api: LubeLoggerApi,
                           month: YearMonth, selectedDay: LocalDate,
                           onMonthChanged: (YearMonth) -> Unit, onDayChanged: (LocalDate) -> Unit,
                           onOpenVehicle: (VehicleId) -> Unit, onEdit: ((ServiceRecord) -> Unit)?,
                           onCopy: ((ServiceRecord) -> Unit)?,
                           onDelete: ((ServiceRecord) -> Unit)?,
                           onLogReminder: ((MaintenanceReminder) -> Unit)?, retry: () -> Unit) {
    val vehicles = remember(data) { data.vehicles.associateBy { it.id } }
    var recordDialog by remember { mutableStateOf<ServiceRecord?>(null) }
    var reminderDialog by remember { mutableStateOf<MaintenanceReminder?>(null) }
    val effectiveId = selectedId
    val entries = remember(records, data.reminders, effectiveId) {
        CalendarRules.datedEntries(records, data.reminders, effectiveId)
    }
    val monthEntries = remember(entries, month) { CalendarRules.entriesForMonth(entries, month) }
    val daily = remember(entries, selectedDay) { CalendarRules.entriesForDay(entries, selectedDay) }
    val vehicleName: (VehicleId) -> String = { id ->
        vehicles[id]?.let { MaintenanceRules.vehicleContextLabel(it, nicknames[id]) } ?: "Vehicle"
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        ContextHeader("Calendar", selectedId?.let { id ->
            vehicles[id]?.let { MaintenanceRules.vehicleTitle(it, nicknames[id]) }
        } ?: "All vehicles",
            MaintenanceRules.contextReading(selectedId?.let { vehicles[it] }, selectedId?.let { data.summaries[it] }))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = ScreenEndPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("Gold dot: reminder  •  Blue dot: recorded maintenance",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { onMonthChanged(month.minusMonths(1)); onDayChanged(month.minusMonths(1).atDay(1)) }) {
                                Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous month")
                            }
                            Text(month.format(DateTimeFormatter.ofPattern("MMMM uuuu", Locale.US)),
                                modifier = Modifier.weight(1f),
                                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            TextButton(onClick = { onMonthChanged(YearMonth.now()); onDayChanged(LocalDate.now()) }) {
                                Text("Today", color = Gold)
                            }
                            IconButton(onClick = { onMonthChanged(month.plusMonths(1)); onDayChanged(month.plusMonths(1).atDay(1)) }) {
                                Icon(Icons.Outlined.ChevronRight, contentDescription = "Next month")
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            listOf("S", "M", "T", "W", "T", "F", "S").forEach { label ->
                                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                    Text(label, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        val eventsByDay = monthEntries.groupBy { it.day }
                        CalendarRules.monthCells(month).chunked(7).forEach { week ->
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                week.forEach { day ->
                                    val count = if (day == null) 0 else eventsByDay[day].orEmpty().size
                                    val hasReminder = day?.let { eventsByDay[it]?.any { e -> e.reminder != null } } == true
                                    val hasHistory = day?.let { eventsByDay[it]?.any { e -> e.record != null } } == true
                                    val active = day == selectedDay
                                    Column(Modifier.weight(1f).height(52.dp)
                                        .clip(controlShape)
                                        .background(if (active) Gold.copy(alpha = 0.14f) else Charcoal)
                                        .then(if (active) Modifier.border(1.dp, Gold, controlShape) else Modifier)
                                        .clickable(enabled = day != null) { if (day != null) onDayChanged(day) }
                                        .semantics { if (day != null) {
                                            selected = active
                                            contentDescription = "${day.format(DAY_LABEL)}, " +
                                                "$count event${if (count == 1) "" else "s"}" +
                                                (if (hasReminder) ", maintenance reminder" else "") +
                                                (if (hasHistory) ", recorded maintenance" else "")
                                        } },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center) {
                                        if (day != null) {
                                            Text(day.dayOfMonth.toString(),
                                                color = if (active) Gold else MaterialTheme.colorScheme.onSurface,
                                                style = MaterialTheme.typography.bodySmall)
                                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                                if (hasReminder) Box(Modifier.size(5.dp).background(Gold, dotShape))
                                                if (hasHistory) Box(Modifier.size(5.dp).background(Color(0xFF75A8D7), dotShape))
                                            }
                                            if (count > 2) Text("$count", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(selectedDay.format(DateTimeFormatter.ofPattern("EEE, MMM d, uuuu", Locale.US)),
                    style = MaterialTheme.typography.titleMedium)
            }
            if (loading) item { Text("Loading maintenance history…", style = MaterialTheme.typography.bodySmall) }
            if (error != null) item {
                Column {
                    Text("Some history couldn't load: $error", color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = retry) { Text("Retry") }
                    Text("Reminders remain available.", style = MaterialTheme.typography.bodySmall)
                }
            }
            val remindersMissing = GarageSection.REMINDERS in data.missing
            if (remindersMissing) item { Text(REMINDERS_NOT_LOADED, style = MaterialTheme.typography.bodyMedium) }
            if (daily.isEmpty() && !loading) item {
                Text(if (remindersMissing) "No dated maintenance on this day."
                    else "No dated maintenance or reminders on this day.",
                    style = MaterialTheme.typography.bodyMedium)
            }
            items(daily, key = { event ->
                val id = event.record?.id?.value ?: event.reminder?.id?.value ?: 0
                "${event.vehicleId}-${event.record?.kind ?: "reminder"}-$id"
            }) { event ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (selectedId == null) Text(vehicleName(event.vehicleId), color = Gold,
                        style = MaterialTheme.typography.labelMedium)
                    if (event.record != null) {
                        RecordRow(event.record, vehicles[event.vehicleId]?.useHours ?: false) {
                            recordDialog = event.record
                        }
                    } else if (event.reminder != null) {
                        ReminderRow(event.reminder, "", vehicles[event.vehicleId]?.useHours ?: false) {
                            reminderDialog = event.reminder
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("Mileage-only reminders aren't placed on a date. View them under Reminders.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    recordDialog?.let { r -> RecordDetailsDialog(r, vehicles[r.vehicleId]?.useHours ?: false,
        api, vehicleLabel = if (selectedId == null) vehicleName(r.vehicleId) else null,
        onOpenVehicle = if (selectedId == null) ({ recordDialog = null; onOpenVehicle(r.vehicleId) }) else null,
        onDismiss = { recordDialog = null },
        onEdit = onEdit?.let { edit -> ({ recordDialog = null; edit(r) }) },
        onCopy = onCopy?.let { copy -> ({ recordDialog = null; copy(r) }) },
        onDelete = onDelete?.let { remove -> ({ recordDialog = null; remove(r) }) }) }
    reminderDialog?.let { r -> ReminderDetailsDialog(r, vehicles[r.vehicleId]?.useHours ?: false,
        vehicleLabel = if (selectedId == null) vehicleName(r.vehicleId) else null,
        onOpenVehicle = if (selectedId == null) ({ reminderDialog = null; onOpenVehicle(r.vehicleId) }) else null,
        onLogService = onLogReminder?.let { start -> ({ reminderDialog = null; start(r) }) }
    ) { reminderDialog = null } }
}
