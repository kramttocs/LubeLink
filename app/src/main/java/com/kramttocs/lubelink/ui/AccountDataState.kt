package com.kramttocs.lubelink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.kramttocs.lubelink.core.FieldReadiness
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.GasStationFieldCheck
import com.kramttocs.lubelink.core.LubeLoggerReader
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordsLoad
import com.kramttocs.lubelink.core.ReportSnapshot
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.VehicleHistorySource
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.core.VehiclePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything the app has read from LubeLogger with one credential: Garage, all-vehicle (Calendar)
 * history, description suggestions, Fuel field readiness and the report cache, plus the local
 * nicknames and hidden flags applied to that Garage.
 *
 * One instance belongs to one [reader], i.e. one server and key. [rememberAccountData] makes a new,
 * empty instance when the reader changes, so nothing loaded with a previous credential can stay on
 * screen after a key change, even if the new key's first load fails.
 */
internal class AccountDataState(private val reader: LubeLoggerReader, private val prefs: VehiclePreferences) {
    var garage by mutableStateOf<GarageData?>(null)
        private set
    /** Set only when the first Garage load fails; a failed refresh keeps the loaded Garage. */
    var loadError by mutableStateOf<String?>(null)
        private set
    var nicknames by mutableStateOf<Map<VehicleId, String>>(emptyMap())
        private set
    var hiddenVehicleIds by mutableStateOf<Set<VehicleId>>(emptySet())
        private set

    /** One visible Garage for every tab, picker and context label. Hiding never touches the server. */
    val visibleGarage: GarageData? by derivedStateOf { garage?.let { MaintenanceRules.visibleGarage(it, hiddenVehicleIds) } }
    val visibleVehicleIds: Set<VehicleId> by derivedStateOf {
        visibleGarage?.vehicles?.mapTo(mutableSetOf()) { it.id }.orEmpty()
    }

    var calendarRecords by mutableStateOf<List<ServiceRecord>>(emptyList())
        private set
    var calendarError by mutableStateOf<String?>(null)
        private set
    var calendarLoading by mutableStateOf(false)
        private set
    /** Record types the all-vehicle history is missing this time; [calendarRecords] hold the others (OD-31). */
    var calendarMissing by mutableStateOf<Set<RecordKind>>(emptySet())
        private set
    private var calendarLoadedForFuel: Boolean? = null
    private var calendarLoadedGeneration = -1
    val visibleCalendarRecords: List<ServiceRecord> by derivedStateOf {
        calendarRecords.filter { it.vehicleId in visibleVehicleIds }
    }

    var descriptionRecords by mutableStateOf<List<ServiceRecord>>(emptyList())
        private set
    var descriptionsLoading by mutableStateOf(false)
        private set
    private var descriptionGeneration = -1
    private var descriptionRequests = 0

    var gasFields by mutableStateOf(GasStationFieldCheck(FieldReadiness.UNKNOWN))
        private set
    var gasFieldError by mutableStateOf<String?>(null)
        private set
    var gasFieldChecking by mutableStateOf(false)
        private set

    /** Temporary in-memory report cache; record changes invalidate it by generation. */
    var reportSnapshot by mutableStateOf<ReportSnapshot?>(null)

    // Ordering: each Garage or Calendar request takes the next number, and only the most
    // recently started request may publish. A slower, older response (e.g. a pull-to-refresh that
    // began before a save's reload) is dropped instead of replacing newer data.
    private var garageRequests = 0
    private var calendarRequests = 0

    /** Loads the Garage. On failure a first load sets [loadError]; a reload keeps the Garage it has
     * and returns the failure for the caller to report. */
    suspend fun loadGarage(): Result<GarageData> {
        val request = ++garageRequests
        loadError = null
        return try {
            Result.success(reader.loadGarage().also { if (request == garageRequests) applyGarage(it) })
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (garage == null && request == garageRequests) loadError = safeMessage(e)
            Result.failure(e)
        }
    }

    /** History is read with this account's credential; [VehicleHistoryState] is keyed to it. */
    val historySource: VehicleHistorySource get() = reader

    /** Pull-to-refresh: the new Garage, or the failure for the caller to report. */
    suspend fun refreshGarage(): GarageData {
        val request = ++garageRequests
        return reader.loadGarage().also { if (request == garageRequests) applyGarage(it) }
    }

    private fun applyGarage(result: GarageData) {
        garage = result
        nicknames = result.vehicles.mapNotNull { vehicle ->
            prefs.nicknameFor(vehicle.id).takeIf { it.isNotBlank() }?.let { vehicle.id to it }
        }.toMap()
        hiddenVehicleIds = result.vehicles.filter { prefs.isVehicleHidden(it.id) }.mapTo(mutableSetOf()) { it.id }
    }

    fun setNickname(vehicleId: VehicleId, normalized: String) {
        nicknames = nicknames.toMutableMap().apply {
            if (normalized.isBlank()) remove(vehicleId) else put(vehicleId, normalized)
        }
    }

    fun setHidden(vehicleId: VehicleId, hidden: Boolean) {
        hiddenVehicleIds = hiddenVehicleIds.toMutableSet().apply { if (hidden) add(vehicleId) else remove(vehicleId) }
    }

    /** Make the next Calendar/all-vehicle History visit fetch again. */
    fun invalidateCalendar() { calendarLoadedGeneration = -1 }

    /** One GET per record type across vehicles, only when stale for this fuel filter and generation. */
    suspend fun loadCalendarIfStale(includeFuel: Boolean, generation: Int) {
        if (garage == null || (calendarLoadedForFuel == includeFuel && calendarLoadedGeneration == generation)) return
        val request = ++calendarRequests
        calendarLoading = true
        calendarError = null
        try {
            val loaded = reader.loadCalendarHistory(includeFuel)
            if (request == calendarRequests) setCalendar(loaded, includeFuel, generation)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) {
            if (request == calendarRequests) calendarError = safeMessage(e)
        } finally {
            // Only the newest request owns the indicator.
            if (request == calendarRequests) calendarLoading = false
        }
    }

    /** Pull-to-refresh of the all-vehicle history; throws so the caller can report the failure.
     * An abandoned refresh (the Fuel filter changed) takes no request number, so it can never make the
     * current filter's load stale. */
    suspend fun refreshCalendar(includeFuel: Boolean, generation: Int, stillWanted: () -> Boolean) {
        if (!stillWanted()) return
        val request = ++calendarRequests
        try {
            val refreshed = reader.loadCalendarHistory(includeFuel)
            if (request == calendarRequests && stillWanted()) setCalendar(refreshed, includeFuel, generation)
        } finally {
            if (request == calendarRequests) calendarLoading = false
        }
    }

    private fun setCalendar(load: RecordsLoad, includeFuel: Boolean, generation: Int) {
        calendarRecords = load.records
        calendarMissing = load.missing
        calendarLoadedForFuel = includeFuel
        // An incomplete load is shown but not kept as current: the next visit or Retry reads again.
        calendarLoadedGeneration = if (load.missing.isEmpty()) generation else -1
    }

    /** Reusable descriptions for the editor; optional, so failures are ignored. */
    suspend fun loadDescriptionsIfStale(generation: Int) {
        if (garage == null || descriptionGeneration == generation) return
        // A new generation always loads, even while an older (perhaps cancelled) load is finishing;
        // only the newest load publishes and owns the indicator.
        val request = ++descriptionRequests
        descriptionsLoading = true
        try {
            val loaded = reader.loadCalendarHistory(includeFuel = false)
            if (request == descriptionRequests) {
                // Suggestions from the types that loaded; they are optional, so a missing type only means fewer.
                descriptionRecords = loaded.records
                descriptionGeneration = if (loaded.missing.isEmpty()) generation else -1
            }
        } catch (e: CancellationException) { throw e
        } catch (_: Exception) {
            // Suggestions are optional; free-text entry still works.
        } finally { if (request == descriptionRequests) descriptionsLoading = false }
    }

    /** Read-only check of the two LubeLink Fuel extra fields. A failed check is never "missing". */
    fun checkGasFields(scope: CoroutineScope) {
        if (gasFieldChecking) return
        val prior = gasFields
        gasFieldChecking = true
        gasFieldError = null
        scope.launch {
            try {
                gasFields = reader.checkGasStationFields()
            } catch (e: CancellationException) { throw e
            } catch (_: Exception) {
                gasFields = if (prior.readiness == FieldReadiness.READY || prior.readiness == FieldReadiness.MISSING) prior
                    else GasStationFieldCheck(FieldReadiness.UNKNOWN)
                gasFieldError = "Could not verify fields. Check the connection and try again."
            } finally {
                gasFieldChecking = false
            }
        }
    }
}

@Composable
internal fun rememberAccountData(reader: LubeLoggerReader, prefs: VehiclePreferences): AccountDataState =
    remember(reader) { AccountDataState(reader, prefs) }
