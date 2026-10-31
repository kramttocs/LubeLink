package com.kramttocs.lubelink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordsLoad
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.VehicleHistorySource
import com.kramttocs.lubelink.core.VehicleId
import kotlinx.coroutines.CancellationException

/**
 * The selected vehicle's History. Every load (normal or pull-to-refresh) calls [begin] first and
 * publishes with [publish]; only the most recently begun load may publish, so a slow older response
 * can never replace the History a newer load (e.g. after a save) has shown.
 */
internal class VehicleHistoryState {
    var records by mutableStateOf<List<ServiceRecord>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    /** Record types that did not load this time; [records] hold the others (OD-31). */
    var missing by mutableStateOf<Set<RecordKind>>(emptySet())
        private set
    /** Whether the most recently begun load is still running (OD-37: Recent history says "Loading…"). */
    var loading by mutableStateOf(false)
        private set
    private var latest = 0

    /** Start a load; returns its ticket. Clears what is shown, as a new load always did. */
    fun begin(clear: Boolean = true): Int {
        if (clear) { records = emptyList(); error = null; missing = emptySet() }
        loading = true
        return ++latest
    }

    /** Publish a load's result; ignored unless it is still the most recent load. */
    fun publish(ticket: Int, loaded: RecordsLoad) {
        if (ticket == latest) { records = loaded.records; missing = loaded.missing; error = null; loading = false }
    }

    fun fail(ticket: Int, message: String) {
        if (ticket == latest) { error = message; loading = false }
    }

    /** A load that ended without a result (nothing to load, or abandoned) is no longer loading. */
    fun settle(ticket: Int) {
        if (ticket == latest) loading = false
    }
}

/**
 * Loads History for [vehicleId] once the Garage is known ([garageReady]). Readiness is an effect key:
 * after process death the selected vehicle is restored before the Garage reloads, and the load must
 * run when it arrives rather than leave an empty History. A change of [source] (new
 * credential), vehicle, fuel filter or [generation] restarts the load and cancels the previous one.
 */
@Composable
internal fun rememberVehicleHistory(source: VehicleHistorySource, vehicleId: VehicleId?, includeFuel: Boolean,
                                    garageReady: Boolean, generation: Int): VehicleHistoryState {
    // One holder per source (credential): a refresh still running for a previous key can only ever
    // reach that key's holder, never the one on screen.
    val state = remember(source) { VehicleHistoryState() }
    LaunchedEffect(source, vehicleId, includeFuel, garageReady, generation) {
        val ticket = state.begin()
        if (vehicleId != null && garageReady) {
            try { state.publish(ticket, source.loadHistory(vehicleId, includeFuel)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.fail(ticket, safeMessage(e)) }
        } else state.settle(ticket)
    }
    return state
}
