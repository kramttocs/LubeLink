package com.kramttocs.lubelink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kramttocs.lubelink.core.KeyChecks
import com.kramttocs.lubelink.core.ServiceKeyChecks
import com.kramttocs.lubelink.core.DeviceStationLookups
import com.kramttocs.lubelink.core.StationLookups
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The key checks the Settings dialogs use; tests provide their own (OD-42). */
internal val LocalKeyChecks = staticCompositionLocalOf<KeyChecks> { ServiceKeyChecks }

/** The lookups the station picker uses; tests provide their own (OD-42). */
internal val LocalStationLookups = staticCompositionLocalOf<StationLookups> { DeviceStationLookups }

/**
 * One network call at a time for one dialog or target (OD-42). [busy] while it runs; only the latest run reports, so
 * a run made stale by a newer run, an edited key, another record or a closed dialog never saves or shows its result;
 * and the run is cancelled when the screen that started it is left.
 */
internal class LatestCall(private val scope: CoroutineScope) {
    var busy by mutableStateOf(false)
        private set
    private var generation = 0
    private var job: Job? = null

    fun <T> run(call: suspend () -> T, onSuccess: (T) -> Unit, onFailure: (Exception) -> Unit) {
        job?.cancel()
        val mine = ++generation
        busy = true
        job = scope.launch {
            try {
                val result = call()
                if (mine == generation) onSuccess(result)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { if (mine == generation) onFailure(e)
            } finally { if (mine == generation) busy = false }
        }
    }

    /** Makes any running call stale and stops it. */
    fun cancel() {
        generation++
        job?.cancel()
        job = null
        busy = false
    }
}

/** A [LatestCall] for the current [keys]: a change of key, or leaving the screen, cancels the running call. */
@Composable
internal fun rememberLatestCall(vararg keys: Any?): LatestCall {
    val scope = rememberCoroutineScope()
    val state = remember(*keys) { LatestCall(scope) }
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}
