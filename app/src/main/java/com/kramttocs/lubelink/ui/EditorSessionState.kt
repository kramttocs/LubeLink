package com.kramttocs.lubelink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kramttocs.lubelink.core.FormSessionRules
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordRef
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.VehicleId

/**
 * Which record form or delete confirmation is open. Each opened form gets a new [session]; callbacks
 * from a closed form are rejected with [closeIfCurrent].
 *
 * The open form survives process death (OD-01): open flag, session, vehicle, record type
 * and, for an Edit, the exact [RecordRef] are saved, so an Edit can never come back as an Add. The
 * dialog keeps its own typed fields and baseline. Reminder and copy sources are not saved; a form
 * restored from one reopens as a plain Add with the values already entered. Delete is not restored.
 */
internal class EditorSessionState(
    openState: MutableState<Boolean>,
    sessionState: MutableIntState,
    targetState: MutableState<RecordRef?>,
    vehicleState: MutableState<VehicleId?>,
    kindState: MutableState<RecordKind>
) {
    var isOpen by openState
        private set
    var session by sessionState
        private set
    /** The record an Edit targets; null for Add and Copy. */
    var target by targetState
        private set
    /** The vehicle the form starts on; null asks the user to choose (All vehicles). */
    var vehicleId by vehicleState
        private set
    var initialKind by kindState
        private set
    var reminder by mutableStateOf<MaintenanceReminder?>(null)
        private set
    var copySource by mutableStateOf<ServiceRecord?>(null)
        private set
    var deleteTarget by mutableStateOf<ServiceRecord?>(null)

    val isShowing: Boolean get() = isOpen || target != null

    private fun open(vehicle: VehicleId?, kind: RecordKind, edit: RecordRef? = null,
                     fromReminder: MaintenanceReminder? = null, copy: ServiceRecord? = null) {
        target = edit
        reminder = fromReminder
        copySource = copy
        vehicleId = vehicle
        initialKind = kind
        session++
        isOpen = true
    }

    fun beginAdd(vehicle: VehicleId?, kind: RecordKind) = open(vehicle, kind)
    fun beginEdit(record: ServiceRecord) = open(record.vehicleId, record.kind, edit = record.ref)
    /** Copy only opens a prefilled Add form; nothing is written until Save. */
    fun beginCopy(record: ServiceRecord) = open(record.vehicleId, record.kind, copy = record)
    /** A reminder seeds an ordinary Service form; it is never sent as a reminder completion. */
    fun beginFromReminder(reminder: MaintenanceReminder) = open(reminder.vehicleId, RecordKind.SERVICE, fromReminder = reminder)

    fun close() {
        isOpen = false
        target = null
        reminder = null
        copySource = null
    }

    /** Close only if [openedSession] is still the current, open form. */
    fun closeIfCurrent(openedSession: Int): Boolean {
        if (!FormSessionRules.accepts(session, openedSession, isOpen)) return false
        close()
        return true
    }

    /** A vehicle was hidden: drop any form or confirmation that belongs to it. */
    fun closeForVehicle(hidden: VehicleId) {
        if (vehicleId == hidden) close()
        if (deleteTarget?.vehicleId == hidden) deleteTarget = null
    }
}

@Composable
internal fun rememberEditorSession(): EditorSessionState {
    val open = rememberSaveable { mutableStateOf(false) }
    val session = rememberSaveable { mutableIntStateOf(0) }
    val target = rememberSaveable(stateSaver = OptionalRecordRefSaver) { mutableStateOf<RecordRef?>(null) }
    val vehicle = rememberSaveable(stateSaver = OptionalVehicleIdSaver) { mutableStateOf<VehicleId?>(null) }
    val kind = rememberSaveable { mutableStateOf(RecordKind.SERVICE) }
    return remember { EditorSessionState(open, session, target, vehicle, kind) }
}
