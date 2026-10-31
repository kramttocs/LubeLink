package com.kramttocs.lubelink.ui

import androidx.compose.runtime.saveable.Saver
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordRef
import com.kramttocs.lubelink.core.VehicleId

/** Saved state must be Bundle-compatible, which a boxed value class is not. Saves the bare number;
 * 0 means "none" (null), e.g. All vehicles. */
internal val OptionalVehicleIdSaver: Saver<VehicleId?, Int> = Saver(
    save = { it?.value ?: 0 },
    restore = { saved -> VehicleId(saved).takeIf { it.isValid } }
)

/** An open Edit form's target, saved as its three parts so the form can be restored after process
 * death on exactly the same record (OD-01). */
internal val OptionalRecordRefSaver: Saver<RecordRef?, List<Any>> = Saver(
    save = { ref -> ref?.let { listOf(it.vehicleId.value, it.kind.name, it.id.value) } },
    restore = { (vehicle, kind, id) ->
        RecordRef(VehicleId(vehicle as Int), RecordKind.valueOf(kind as String), RecordId(id as Int))
    }
)
