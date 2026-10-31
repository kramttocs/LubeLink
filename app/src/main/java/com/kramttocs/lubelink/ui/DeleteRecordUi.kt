package com.kramttocs.lubelink.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.LubeLoggerManagerApi
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.RecordDeleteGateway
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import kotlinx.coroutines.CancellationException

/** Explicit confirmation: GET snapshot, user confirms, second GET before one DELETE. */
@Composable
internal fun DeleteRecordDialog(
    vehicle: Vehicle,
    record: ServiceRecord,
    vehicleLabel: String,
    baseUrl: String,
    managerKey: String,
    onDismiss: () -> Unit,
    manager: RecordDeleteGateway = remember(baseUrl, managerKey) { LubeLoggerManagerApi(baseUrl, managerKey) }
) {
    val context = LocalContext.current
    var loading by remember(record.id, record.kind, record.vehicleId, managerKey) { mutableStateOf(true) }
    var error by remember(record.id, record.kind, record.vehicleId, managerKey) { mutableStateOf<String?>(null) }
    var snapshot by remember(record.id, record.kind, record.vehicleId, managerKey) {
        mutableStateOf<LubeLoggerManagerApi.DeleteSnapshot?>(null)
    }
    LaunchedEffect(record.id, record.kind, record.vehicleId, managerKey) {
        loading = true
        error = null
        try {
            snapshot = manager.prepare(vehicle, record)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            error = safeMessage(e)
            snapshot = null
        } finally { loading = false }
    }
    val locked = WriteSafety.unresolved(context) != null || WriteSafety.state !is WriteSafety.State.Idle
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { LubeDialogTitle("Delete ${record.kind.label.lowercase()} record?",
            vehicleLabel = vehicleLabel) },
        text = {
            DialogBody(spacing = 9.dp) {
                Text("${MaintenanceRules.lineForDisplay(record.description.ifBlank { record.kind.label })} · ${MaintenanceRules.recordDateLabel(record.date)}")
                Text(MaintenanceRules.mileageLabel(record.odometer, vehicle.useHours))
                if (record.cost.isNotBlank()) MaintenanceRules.currencyLabel(record.cost)?.let { Text("Cost: $it") }
                Text("This permanently removes this record from LubeLogger. It can't be undone in LubeLink.")
                Text("LubeLogger keeps any separate odometer entry created with this record and may return used supplies to inventory.",
                    style = MaterialTheme.typography.bodySmall)
                if (loading) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Checking record…")
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (locked) Text("Resolve the pending record operation before deleting.",
                    color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            Button(
                enabled = !loading && !locked && snapshot != null && error == null,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError),
                onClick = {
                    val selected = snapshot ?: return@Button
                    if (WriteSafety.start(context.applicationContext,
                            "Delete ${record.kind.label} for $vehicleLabel on ${MaintenanceRules.recordDateLabel(record.date)}",
                            com.kramttocs.lubelink.core.RecordWriteIntent(
                                com.kramttocs.lubelink.core.WriteAction.DELETE, record.kind)) {
                                manager.delete(selected)
                            }) {
                        onDismiss()
                    } else error = "Resolve the previous record operation before deleting."
                }
            ) { Text("Delete record") }
        }
    )
}
