package com.kramttocs.lubelink.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kramttocs.lubelink.core.LubeLoggerEditorApi
import com.kramttocs.lubelink.core.RecordWriteIntent
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.StoredFormats
import com.kramttocs.lubelink.core.WriteMarkerKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A write is never automatically retried. A persistent marker survives process death.
 *  This scope lives longer than any Activity/dialog. The app refuses further writes
 *  until every uncertain outcome has been checked by the user in the web interface.
 */
internal object WriteSafety {
    sealed class State {
        object Idle : State()
        data class Pending(val description: String, val intent: RecordWriteIntent) : State()
        data class Success(val description: String, val intent: RecordWriteIntent) : State()
        data class Failed(val message: String) : State()
        data class Uncertain(val message: String) : State()
    }
    var state by mutableStateOf<State>(State.Idle)
        private set
    /** Observable revision of the persisted write marker, independent of State.Idle.
     *  A restart can restore an unresolved marker while state is already Idle. */
    var markerRevision by mutableLongStateOf(0L)
        private set
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private const val ACTIVE = WriteMarkerKeys.ACTIVE
    private const val INTENT = WriteMarkerKeys.INTENT

    /** The interrupted write's description, or null when none is unresolved. A value of the wrong type under the
     * marker's key (a damaged file) keeps the lock: only an absent marker means no write is unresolved. */
    fun unresolved(context: Context): String? =
        runCatching { StoredFormats.prefs(context, StoredFormat.UNCONFIRMED_WRITE).getString(ACTIVE, null) }
            .getOrElse { WriteMarkerKeys.DAMAGED_DESCRIPTION }

    /** What the interrupted write was; unknown (null) when absent or unreadable, which the dialog words generally. */
    fun unresolvedIntent(context: Context): RecordWriteIntent? =
        RecordWriteIntent.fromPersistedCode(runCatching {
            StoredFormats.prefs(context, StoredFormat.UNCONFIRMED_WRITE).getString(INTENT, null)
        }.getOrNull())

    /** Execute SharedPreferences disk writes on IO; a successful awaited marker commit
     *  ALWAYS precedes the first server request. Pending state blocks duplicate taps
     *  during the commit, even before the marker reaches disk. */
    private suspend fun setMarker(context: Context, description: String, intent: RecordWriteIntent): Boolean =
        withContext(Dispatchers.IO) {
            StoredFormats.prefs(context, StoredFormat.UNCONFIRMED_WRITE).edit()
                .putString(ACTIVE, description).putString(INTENT, intent.persistedCode()).commit()
        }

    private suspend fun clearMarker(context: Context): Boolean {
        val cleared = withContext(Dispatchers.IO) {
            StoredFormats.prefs(context, StoredFormat.UNCONFIRMED_WRITE).edit()
                .remove(ACTIVE).remove(INTENT).commit()
        }
        // A recovered marker can disappear without any State change (Idle -> Idle).
        // Publishing a separate observable revision closes the reconciliation dialog.
        if (cleared) markerRevision++
        return cleared
    }

    @Synchronized
    fun start(context: Context, description: String, intent: RecordWriteIntent,
              operation: suspend () -> Unit): Boolean {
        if (state !is State.Idle || unresolved(context) != null) return false
        // Reserve the form command immediately; no operation is sent until durable.
        state = State.Pending(description, intent)
        val pendingSinceMs = SystemClock.elapsedRealtime()
        scope.launch {
            val saved = try { setMarker(context, description, intent) }
                catch (_: Exception) { false }
            if (!saved) {
                // Nothing was sent. A failed commit can still leave the marker in the in-memory
                // preferences, which would make the Failed dialog's OK unable to unlock.
                // Clearing it is safe here; if it cannot be cleared, offer the reconcile path.
                runCatching { clearMarker(context) }
                state = if (unresolved(context) == null)
                    State.Failed("Could not establish a durable write marker. No record was sent.")
                else State.Uncertain("LubeLink could not save its safety marker, so nothing was sent to " +
                    "LubeLogger. Confirm below to unlock.")
                return@launch
            }
            try {
                operation()
                // The server confirmed the write; clear the marker before success UI.
                val cleared = clearMarker(context)
                if (cleared) {
                    val elapsed = SystemClock.elapsedRealtime() - pendingSinceMs
                    val remaining = (650L - elapsed).coerceAtLeast(0L)
                    if (remaining > 0L) delay(remaining)
                    state = State.Success(description, intent)
                } else {
                    state = State.Uncertain("The server operation completed but LubeLink could not clear its safety lock. Verify the record in LubeLogger before unlocking.")
                }
            } catch (e: LubeLoggerEditorApi.WriteUncertainException) {
                state = State.Uncertain(e.message ?: "Write outcome unknown. Check LubeLogger.")
            } catch (e: IllegalArgumentException) {
                state = failedAfterMarker(context, e.message ?: "Invalid record; nothing changed")
            } catch (e: LubeLoggerEditorApi.EditorApiException) {
                state = failedAfterMarker(context, e.message ?: "Server rejected the operation")
            } catch (_: Exception) {
                state = State.Uncertain("The write outcome is unknown. Check LubeLogger before any new save.")
            }
        }
        return true
    }

    private suspend fun failedAfterMarker(context: Context, message: String): State =
        if (runCatching { clearMarker(context) }.getOrDefault(false)) State.Failed(message)
        else State.Uncertain("The operation was rejected, but its safety lock could not be cleared. Verify LubeLogger before unlocking.")

    fun acknowledge(context: Context, checkedServer: Boolean) {
        if (state is State.Pending) return
        if (unresolved(context) != null && !checkedServer) return
        if (!checkedServer) {
            state = State.Idle
        } else {
            // Do not unlock until the user's reconciliation acknowledgment is durable.
            // An interrupted/failed preference write must leave the lock in place.
            scope.launch {
                if (runCatching { clearMarker(context) }.getOrDefault(false)) state = State.Idle
                else state = State.Uncertain("Could not clear the safety lock. Check LubeLogger before trying again.")
            }
        }
    }

}
