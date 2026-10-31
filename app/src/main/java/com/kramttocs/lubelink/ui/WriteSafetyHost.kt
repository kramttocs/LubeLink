package com.kramttocs.lubelink.ui

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.WriteAction
import com.kramttocs.lubelink.core.WriteSuccessNotice

/**
 * The one completion path for every record write: a confirmed success unlocks and calls
 * [onConfirmedSuccess] with its notice; pending, failed and uncertain outcomes (including a lock
 * recovered after restart) show a dialog that only the matching resolution can dismiss.
 */
@Composable
internal fun WriteSafetyHost(appContext: Context, onConfirmedSuccess: (notice: String) -> Unit) {
    val writeState = WriteSafety.state
    // A recovered marker can clear while writeState remains Idle. Observe the
    // revision and re-read persisted state only when its identity actually changes.
    val markerRevision = WriteSafety.markerRevision
    val unconfirmedWrite = remember(writeState, markerRevision) {
        WriteSafety.unresolved(appContext)
    }
    val operationIntent = (writeState as? WriteSafety.State.Pending)?.intent
        ?: WriteSafety.unresolvedIntent(appContext)
    // All record writes share this one completion path. Only a CONFIRMED success is
    // acknowledged automatically; uncertain outcomes still require manual reconciliation.
    LaunchedEffect(writeState) {
        val success = writeState as? WriteSafety.State.Success ?: return@LaunchedEffect
        if (WriteSafety.unresolved(appContext) != null) return@LaunchedEffect
        WriteSafety.acknowledge(appContext, checkedServer = false)
        onConfirmedSuccess(WriteSuccessNotice.message(success.intent))
    }
    if ((writeState !is WriteSafety.State.Idle && writeState !is WriteSafety.State.Success) ||
        unconfirmedWrite != null) {
        var serverChecked by remember(writeState, unconfirmedWrite) { mutableStateOf(false) }
        val pending = writeState is WriteSafety.State.Pending
        val uncertain = writeState is WriteSafety.State.Uncertain ||
            (writeState is WriteSafety.State.Idle && unconfirmedWrite != null)
        val description = when (writeState) {
            is WriteSafety.State.Pending -> "Processing ${writeState.description}. Do not exit or retry."
            is WriteSafety.State.Failed -> "No change confirmed: ${writeState.message}"
            is WriteSafety.State.Uncertain -> writeState.message
            else -> "A previous operation was interrupted ($unconfirmedWrite). It may have completed."
        }
        AlertDialog(
            onDismissRequest = { /* Never dismiss pending, failed or uncertain operations outside their resolution flow. */ },
            title = { LubeDialogTitle(if (pending && operationIntent?.action == WriteAction.DELETE)
                "Deleting record…" else when {
                pending -> "Saving record…"
                uncertain -> "Verify record before continuing"
                else -> "Operation not confirmed"
            }) },
            text = {
                DialogBody(spacing = 8.dp) {
                    Text(description)
                    if (uncertain) {
                        Text("Check this record in the LubeLogger web interface before retrying or making another change. If unsure, keep this lock in place.")
                        LabeledCheckbox(if (operationIntent?.action == WriteAction.DELETE)
                                "I checked LubeLogger and reconciled this deletion"
                                else "I checked LubeLogger and reconciled this write",
                            serverChecked, { serverChecked = it })
                    }
                    if (pending) CircularProgressIndicator()
                }
            },
            confirmButton = {
                Button(enabled = !pending && (!uncertain || serverChecked), onClick = {
                    WriteSafety.acknowledge(appContext, checkedServer = uncertain && serverChecked)
                }) { Text(if (uncertain) "Unlock after verification" else "OK") }
            }
        )
    }
}
