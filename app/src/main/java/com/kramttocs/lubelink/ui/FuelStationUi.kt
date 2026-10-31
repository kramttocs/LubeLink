package com.kramttocs.lubelink.ui

import androidx.core.content.edit
import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.List
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Search
import android.location.Location
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import com.kramttocs.lubelink.core.StationSearchProvider
import com.kramttocs.lubelink.core.StationBounds
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.StoredFormats
import com.kramttocs.lubelink.core.StationAddressPolicy
import androidx.compose.runtime.DisposableEffect
import java.util.Locale
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** UI state identity is typed; strings are exclusively labels or legacy preference values. */
internal enum class StationPickerPage(val label: String) {
    FAVORITES("Saved"), NEARBY("Nearby"), MANUAL("Manual")
}
/** The picker's pages. A chip that does not fit moves to the next line, so large text never cuts a label short. */
@Composable
internal fun StationPageChips(page: StationPickerPage, onSelect: (StationPickerPage) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        StationPickerPage.entries.forEach { option ->
            FilterChip(selected = page == option, onClick = { onSelect(option) },
                label = { Text(option.label, maxLines = 1, softWrap = false) })
        }
    }
}

internal enum class StationNearbyView(val persistedName: String) {
    MAP("Map"), LIST("List");
    companion object {
        fun fromPreference(value: String?): StationNearbyView =
            entries.firstOrNull { it.persistedName == value } ?: MAP
        /** The saved choice under [key]; a value of the wrong type (a damaged file) reads as the map. */
        fun read(prefs: android.content.SharedPreferences, key: String): StationNearbyView =
            fromPreference(runCatching { prefs.getString(key, MAP.persistedName) }.getOrNull())
    }
}
private enum class StationPermissionTask { NEARBY, ADDRESS }

/** The selected map center is a normal Location for the existing lookup method. */
private fun StationMapViewport.asLocation(): Location = Location("station-map-center").also {
    it.latitude = latitude; it.longitude = longitude
}

/** Favorites are opt-in; selecting a nearby or manual station never saves it. */
@Composable
internal fun FuelStationPickerDialog(
    selectedName: String, selectedAddress: String, baseUrl: String, editorKey: String,
    onChoose: (com.kramttocs.lubelink.core.FuelStation) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lookups = LocalStationLookups.current
    val geoapifyKey = remember(context) {
        com.kramttocs.lubelink.core.SecureConfig(context).readGeoapifyKey()
    }
    val stationStore = remember(context, baseUrl, editorKey) {
        com.kramttocs.lubelink.core.FuelStationStore(context, baseUrl, editorKey)
    }
    val scope = rememberCoroutineScope()
    // What the user chose or typed survives rotation and process death. Nearby results are not saved, so a picker
    // restored on the Nearby page reopens on Saved rather than showing an empty search.
    var page by rememberSaveable(stateSaver = Saver<StationPickerPage, String>(
        save = { (if (it == StationPickerPage.NEARBY) StationPickerPage.FAVORITES else it).name },
        restore = { StationPickerPage.valueOf(it) })) { mutableStateOf(StationPickerPage.FAVORITES) }
    var mapOpen by remember { mutableStateOf(false) }
    val displayPrefs = remember(context) { StoredFormats.prefs(context, StoredFormat.STATION_VIEW) }
    // The view choice is cosmetic but still scoped by the connected account.
    val mapPreferenceKey = remember(baseUrl, editorKey) {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${baseUrl.trim()}\n$editorKey".toByteArray(Charsets.UTF_8))
        "nearby_view_" + bytes.take(12).joinToString("") { "%02x".format(it) }
    }
    var nearbyView by remember(mapPreferenceKey) {
        mutableStateOf(StationNearbyView.read(displayPrefs, mapPreferenceKey))
    }
    var search by rememberSaveable { mutableStateOf("") }
    var selectedNearby by remember { mutableStateOf<com.kramttocs.lubelink.core.FuelStation?>(null) }
    var mapStatus by remember { mutableStateOf<String?>(null) }
    // Camera changes only by explicit Near me, or by user gestures (which do NOT search).
    var mapViewport by remember { mutableStateOf<StationMapViewport?>(null) }
    var cameraRevision by remember { mutableIntStateOf(0) }
    var pendingArea by remember { mutableStateOf<StationMapViewport?>(null) }
    var pendingBounds by remember { mutableStateOf<StationBounds?>(null) }
    var cameraGestureRevision by remember { mutableIntStateOf(0) }
    val lookupGate = remember { com.kramttocs.lubelink.core.StationLookupGate() }
    val nearbyJob = remember { mutableStateOf<Job?>(null) }
    val gpsRefreshJob = remember { mutableStateOf<Deferred<Location?>?>(null) }
    val addressJob = remember { mutableStateOf<Job?>(null) }
    val enrichmentJob = remember { mutableStateOf<Job?>(null) }
    var enrichingStation by remember { mutableStateOf<com.kramttocs.lubelink.core.FuelStation?>(null) }
    var enrichmentError by remember { mutableStateOf<String?>(null) }
    var proposedAddress by remember { mutableStateOf<Pair<com.kramttocs.lubelink.core.FuelStation, String>?>(null) }
    var confirmedAddress by remember { mutableStateOf<Pair<com.kramttocs.lubelink.core.FuelStation, String>?>(null) }
    var nearbyLoading by remember { mutableStateOf(false) }
    var nearbyLoadingForArea by remember { mutableStateOf(false) }
    var addressLoading by remember { mutableStateOf(false) }
    var nearbyError by remember { mutableStateOf<String?>(null) }
    var resultsFromMapArea by remember { mutableStateOf(false) }
    var stationProvider by remember { mutableStateOf<StationSearchProvider?>(null) }
    var providerFallbackNotice by remember { mutableStateOf<String?>(null) }
    var addressError by remember { mutableStateOf<String?>(null) }
    fun cancelNearby() {
        lookupGate.cancelNearby()
        nearbyJob.value?.cancel()
        nearbyJob.value = null
        gpsRefreshJob.value?.cancel()
        gpsRefreshJob.value = null
        nearbyLoading = false
        nearbyLoadingForArea = false
    }
    fun cancelAddress() {
        lookupGate.cancelAddress()
        addressJob.value?.cancel()
        addressJob.value = null
        addressLoading = false
    }
    fun cancelEnrichment() {
        enrichmentJob.value?.cancel()
        enrichmentJob.value = null
        enrichingStation = null
        proposedAddress = null
        enrichmentError = null
    }
    fun selectStation(station: com.kramttocs.lubelink.core.FuelStation) {
        cancelEnrichment()
        confirmedAddress = null
        selectedNearby = station
    }
    fun openNearby() {
        cancelAddress()
        page = StationPickerPage.NEARBY; mapOpen = true; search = ""; mapStatus = null
        nearbyError = null
    }
    fun closeNearby() {
        cancelNearby()
        cancelEnrichment()
        mapOpen = false; page = StationPickerPage.FAVORITES; selectedNearby = null; mapStatus = null
        nearbyError = null
    }
    fun openManual() {
        cancelNearby()
        cancelEnrichment()
        page = StationPickerPage.MANUAL; mapOpen = false; mapStatus = null; nearbyError = null
    }
    // Leaving the editor invalidates pending permission, location, and HTTP callbacks.
    DisposableEffect(Unit) {
        onDispose {
            lookupGate.cancelNearby(); lookupGate.cancelAddress()
            nearbyJob.value?.cancel(); gpsRefreshJob.value?.cancel()
            addressJob.value?.cancel(); enrichmentJob.value?.cancel()
        }
    }
    // The record form's station can be far longer than the Manual fields take (100 and 240 characters, what
    // "Use station" saves). They start with that much, and say so, so a huge station is never laid out or saved whole.
    // The record keeps its station unless the user chooses "Use station".
    val stationShortened = selectedName.length > 100 || selectedAddress.length > 240
    var manualName by rememberSaveable { mutableStateOf(selectedName.take(100)) }
    var manualAddress by rememberSaveable { mutableStateOf(selectedAddress.take(240)) }
    var saveManualFavorite by rememberSaveable { mutableStateOf(false) }
    var nearby by remember { mutableStateOf<List<com.kramttocs.lubelink.core.FuelStation>>(emptyList()) }
    var permissionTask by remember { mutableStateOf<StationPermissionTask?>(null) }
    var favorites by remember { mutableStateOf(stationStore.favorites()) }
    var editingFavorite by remember { mutableStateOf<com.kramttocs.lubelink.core.FuelStation?>(null) }
    var editName by remember { mutableStateOf("") }
    var editAddress by remember { mutableStateOf("") }
    var confirmRemove by remember { mutableStateOf(false) }
    var favoriteError by remember { mutableStateOf<String?>(null) }
    fun editFavorite(station: com.kramttocs.lubelink.core.FuelStation) {
        editingFavorite = station
        editName = station.name
        editAddress = station.address
        confirmRemove = false
        favoriteError = null
    }

    fun lookupNearby(forceRefresh: Boolean, mapCenter: StationMapViewport? = null,
                     areaBounds: StationBounds? = null, phonePosition: Location? = null) {
        if (!mapOpen) return
        cancelNearby()
        val generation = lookupGate.beginNearby()
        val startedAtGesture = cameraGestureRevision
        nearbyLoading = true
        nearbyLoadingForArea = mapCenter != null
        nearbyError = null
        providerFallbackNotice = null
        // Android can return a recent fix immediately; refresh it independently after results.
        val recentFix = if (mapCenter == null && phonePosition == null)
            lookups.recentLocation(context) else null
        // Start fresh GPS in parallel with the already-available cached-location Places lookup.
        val refreshing = if (recentFix != null) scope.async {
            withTimeoutOrNull(11_000L) {
                lookups.freshLocation(context)
            }
        } else null
        gpsRefreshJob.value = refreshing
        nearbyJob.value = scope.launch {
            try {
                val position = mapCenter?.asLocation() ?: phonePosition ?: recentFix
                    ?: lookups.currentLocation(context)
                val found = withTimeoutOrNull(38_000L) {
                    if (mapCenter == null && lookupGate.acceptsNearby(generation, mapOpen && page == StationPickerPage.NEARBY) &&
                        cameraGestureRevision == startedAtGesture) {
                        mapViewport = StationMapViewport(position.latitude, position.longitude, 15.5)
                        cameraRevision++
                        pendingArea = null
                        pendingBounds = null
                    }
                    lookups.nearbyWithProvider(position,
                        forceRefresh, geoapifyKey, bounds = areaBounds)
                } ?: throw IllegalStateException("Station search timed out. Try again or enter a station manually.")
                if (lookupGate.acceptsNearby(generation, mapOpen && page == StationPickerPage.NEARBY)) {
                    nearby = found.stations
                    stationProvider = found.provider
                    providerFallbackNotice = found.notice
                    resultsFromMapArea = mapCenter != null
                    if (mapCenter != null && cameraGestureRevision == startedAtGesture) {
                        pendingArea = null
                        pendingBounds = null
                    }
                    if (selectedNearby != null && selectedNearby !in found.stations) {
                        cancelEnrichment()
                        selectedNearby = null
                        confirmedAddress = null
                    }
                    if (found.stations.isEmpty()) nearbyError = "No stations found here. Try another area or enter one manually."
                    // If the cached phone fix was significantly displaced, repeat Near me once
                    // against a new fix. Never interrupt a user gesture, station selection, or area search.
                    if (recentFix != null && mapCenter == null && phonePosition == null) {
                        scope.launch {
                            val fresh = refreshing?.await()
                            if (gpsRefreshJob.value == refreshing) gpsRefreshJob.value = null
                            if (fresh != null && fresh.time > position.time &&
                                com.kramttocs.lubelink.core.FuelStationLookup.distanceMeters(position, fresh) > 400f &&
                                lookupGate.acceptsNearby(generation, mapOpen && page == StationPickerPage.NEARBY) &&
                                cameraGestureRevision == startedAtGesture && selectedNearby == null) {
                                lookupNearby(forceRefresh = true, phonePosition = fresh)
                            }
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (lookupGate.acceptsNearby(generation, mapOpen && page == StationPickerPage.NEARBY)) {
                    nearbyError = safeMessage(e)
                }
            } finally {
                if (lookupGate.acceptsNearby(generation, mapOpen && page == StationPickerPage.NEARBY)) {
                    nearbyLoading = false
                    nearbyLoadingForArea = false
                }
            }
        }
    }
    fun suggestAddress(station: com.kramttocs.lubelink.core.FuelStation) {
        val key = geoapifyKey?.takeIf { it.isNotBlank() } ?: return
        if (station.latitude == null || station.longitude == null) return
        cancelEnrichment()
        enrichingStation = station
        enrichmentJob.value = scope.launch {
            try {
                val suggestion = withTimeoutOrNull(20_000L) {
                    lookups.suggestedStationAddress(key, station)
                } ?: throw IllegalStateException("Address lookup timed out. Enter an address manually.")
                if (selectedNearby == station && mapOpen && page == StationPickerPage.NEARBY)
                    proposedAddress = station to suggestion
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (selectedNearby == station && mapOpen && page == StationPickerPage.NEARBY) enrichmentError = safeMessage(e)
            } finally {
                if (enrichingStation == station) enrichingStation = null
            }
        }
    }
    fun lookupAddress() {
        if (page != StationPickerPage.MANUAL || mapOpen) return
        cancelAddress()
        val generation = lookupGate.beginAddress()
        addressLoading = true
        addressError = null
        addressJob.value = scope.launch {
            try {
                val result = withTimeoutOrNull(24_000L) {
                    val position = lookups.currentLocation(context)
                    lookups.addressAt(context, position)
                } ?: throw IllegalStateException("Address lookup timed out. Try again or enter it manually.")
                if (lookupGate.acceptsAddress(generation, !mapOpen && page == StationPickerPage.MANUAL)) manualAddress = result.take(240)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (lookupGate.acceptsAddress(generation, !mapOpen && page == StationPickerPage.MANUAL)) addressError = safeMessage(e)
            } finally {
                if (lookupGate.acceptsAddress(generation, !mapOpen && page == StationPickerPage.MANUAL)) addressLoading = false
            }
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val task = permissionTask
        permissionTask = null
        if (results.values.any { it }) {
            if (task == StationPermissionTask.NEARBY && mapOpen && page == StationPickerPage.NEARBY) lookupNearby(forceRefresh = true)
            if (task == StationPermissionTask.ADDRESS && !mapOpen && page == StationPickerPage.MANUAL) lookupAddress()
        } else {
            if (task == StationPermissionTask.NEARBY && mapOpen) nearbyError = "Location permission denied. Enter a station manually."
            if (task == StationPermissionTask.ADDRESS && !mapOpen && page == StationPickerPage.MANUAL) addressError = "Location permission denied. Enter the address manually."
        }
    }
    val requestLocation: (StationPermissionTask) -> Unit = { task ->
        permissionTask = task
        if (lookups.hasLocationPermission(context)) {
            permissionTask = null
            if (task == StationPermissionTask.ADDRESS) lookupAddress() else lookupNearby(forceRefresh = true)
        } else permissionLauncher.launch(if (task == StationPermissionTask.ADDRESS)
            arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
            else arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val choose: (com.kramttocs.lubelink.core.FuelStation) -> Unit = { station ->
        val normalized = station.normalized()
        if (normalized.name.isNotBlank()) {
            val local = stationStore.resolve(normalized)
            val accepted = confirmedAddress?.takeIf { it.first == station }?.second
            onChoose(if (accepted != null) local.copy(address = accepted) else local)
        }
    }

    if (!mapOpen) AlertDialog(
        onDismissRequest = onDismiss,
        title = { LubeDialogTitle("Gas station") },
        text = {
            DialogBody(Modifier.fillMaxWidth(), spacing = 8.dp) {
                StationPageChips(page) { option ->
                    when (option) {
                        StationPickerPage.NEARBY -> openNearby()
                        StationPickerPage.MANUAL -> openManual()
                        StationPickerPage.FAVORITES -> { cancelAddress(); page = option }
                    }
                }
                if (page == StationPickerPage.FAVORITES) {
                    OutlinedTextField(search, { search = it.take(100) }, modifier = Modifier.fillMaxWidth(),
                        label = { Text("Search stations") }, singleLine = true)
                    val stations = favorites.filter {
                        search.isBlank() || it.name.contains(search, true) || it.address.contains(search, true)
                    }
                    Column(Modifier.heightIn(max = 290.dp).verticalScroll(rememberScrollState())) {
                        stations.forEach { station ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { choose(station) }
                                    .padding(vertical = 10.dp)) {
                                    Text(station.name, fontWeight = FontWeight.Medium)
                                    if (station.address.isNotBlank()) {
                                        Text(station.address, style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                                IconButton(onClick = { editFavorite(station) }) {
                                    Icon(Icons.Outlined.Edit,
                                        contentDescription = "Edit favorite station ${station.name}", tint = Gold)
                                }
                            }
                            HorizontalDivider()
                        }
                        if (stations.isEmpty()) {
                            Text("No favorites yet. Tap the star beside a nearby station, or choose Save as favorite when entering one manually.",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else {
                    if (stationShortened) Text("This record's station is longer than these fields, so only its start " +
                        "is shown. The record keeps its station unless you choose Use station.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(manualName, { manualName = it.take(100) },
                        label = { Text("Station name") }, keyboardOptions = TextKeyboard, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(manualAddress, { manualAddress = it.take(240) },
                        label = { Text("Address") }, keyboardOptions = TextKeyboard, modifier = Modifier.fillMaxWidth(), enabled = !addressLoading)
                    OutlinedButton(onClick = { requestLocation(StationPermissionTask.ADDRESS) }, enabled = !addressLoading,
                        modifier = Modifier.fillMaxWidth()) {
                        if (addressLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(6.dp)); Text(if (addressLoading) "Finding address…" else "Use current address")
                    }
                    LabeledCheckbox("Save as favorite", saveManualFavorite, { saveManualFavorite = it })
                    Text("One-time fuel stops aren't saved unless you explicitly favorite them.",
                        style = MaterialTheme.typography.bodySmall)
                }
                addressError?.takeIf { page == StationPickerPage.MANUAL }?.let { Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = {
            if (page == StationPickerPage.MANUAL) Button(onClick = {
                val entered = com.kramttocs.lubelink.core.FuelStation(manualName, manualAddress).normalized()
                // Saving a favorite is optional; a full favorites list must not block fuel entry.
                val favoriteSaved = !saveManualFavorite || stationStore.saveFavorite(entered)
                if (!favoriteSaved) {
                    android.widget.Toast.makeText(context, "Favorites full; station used without saving",
                        android.widget.Toast.LENGTH_SHORT).show()
                }
                favorites = stationStore.favorites()
                choose(entered)
            }, enabled = manualName.isNotBlank() && !addressLoading) { Text("Use station") }
            else TextButton(onClick = { openManual() }) { Text("Enter manually") }
        }
    )

    // Opening Nearby is an explicit request. Reuse granted permission to find stations
    // without another tap; otherwise the Find nearby button requests permission.
    androidx.compose.runtime.LaunchedEffect(mapOpen) {
        if (mapOpen && nearby.isEmpty() &&
            lookups.hasLocationPermission(context)) {
            lookupNearby(forceRefresh = false)
        }
    }

    // Full-height Nearby pane. One set of results and one selection path for Map and List.
    if (mapOpen) Dialog(onDismissRequest = { closeNearby() },
        properties = DialogProperties(usePlatformDefaultWidth = false,
            dismissOnClickOutside = false, dismissOnBackPress = true)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Charcoal) {
            Column(Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { closeNearby() }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Back to station picker")
                    }
                    Text("Nearby gas stations", style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { openManual() }) { Text("Manual") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    StationNearbyView.entries.forEach { mode ->
                        FilterChip(selected = nearbyView == mode,
                            onClick = {
                                nearbyView = mode
                                mapStatus = null
                                // Preserve existing Map/List preferences for current installations.
                                displayPrefs.edit { putString(mapPreferenceKey, mode.persistedName) }
                            },
                            label = { Text(mode.persistedName) })
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(enabled = !nearbyLoading, onClick = { requestLocation(StationPermissionTask.NEARBY) }) {
                        if (nearbyLoading && !nearbyLoadingForArea) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text(if (nearbyLoading && !nearbyLoadingForArea) "Searching…"
                            else if (nearby.isEmpty()) "Find nearby" else "Near me")
                    }
                }
                OutlinedTextField(search, { search = it.take(100) },
                    label = { Text("Search stations") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                val filtered = nearby.filter { station ->
                    com.kramttocs.lubelink.core.FuelFavoriteRules.matchesSearch(
                        station, com.kramttocs.lubelink.core.FuelFavoriteRules.findFavorite(favorites, station), search)
                }
                // A changing query must not leave a hidden station actionable, even for one frame.
                val visibleSelection = com.kramttocs.lubelink.core.FuelFavoriteRules.visibleSelection(
                    selectedNearby, filtered)
                androidx.compose.runtime.LaunchedEffect(filtered, selectedNearby) {
                    if (selectedNearby != null && selectedNearby !in filtered) selectedNearby = null
                }
                if (nearby.isNotEmpty() && filtered.isEmpty() && !nearbyLoading) {
                    Text("No stations match your search.", style = MaterialTheme.typography.bodySmall)
                }
                if (nearby.isEmpty() && !nearbyLoading) {
                    Text("Find nearby stations to see them on the map or list.",
                        style = MaterialTheme.typography.bodySmall)
                }
                nearbyError?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error) }
                providerFallbackNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (stationProvider == StationSearchProvider.GEOAPIFY && nearby.isNotEmpty()) {
                    Text("Station data: Powered by Geoapify · © OpenStreetMap contributors",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                mapStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (nearbyView == StationNearbyView.MAP && (mapViewport != null ||
                        filtered.any { it.latitude != null && it.longitude != null })) {
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        StationMap(filtered, onSelect = { selectStation(it) },
                            viewport = mapViewport,
                            cameraRevision = cameraRevision,
                            onUserMoved = { moved, bounds ->
                                mapViewport = moved // preserve map position when toggling List and Map
                                pendingArea = moved
                                pendingBounds = bounds
                                mapStatus = if (bounds.isSearchable()) null else
                                    "Zoom in to search this area."
                                cameraGestureRevision++
                            },
                            onMapFailure = {
                                nearbyView = StationNearbyView.LIST
                                mapStatus = "Map unavailable. Showing the station list instead."
                            }, modifier = Modifier.fillMaxSize())
                        pendingArea?.let { center ->
                            Button(onClick = { lookupNearby(forceRefresh = true,
                                mapCenter = center, areaBounds = pendingBounds) },
                                enabled = !nearbyLoading && pendingBounds?.isSearchable() == true,
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp),
                                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                                    containerColor = Charcoal,
                                    contentColor = androidx.compose.ui.graphics.Color.White,
                                    disabledContainerColor = Panel,
                                    disabledContentColor = androidx.compose.ui.graphics.Color.White)) {
                                if (nearbyLoadingForArea) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                else Icon(Icons.Outlined.Search, contentDescription = null)
                                Spacer(Modifier.width(5.dp))
                                Text(if (nearbyLoadingForArea) "Searching…" else "Search this area")
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Some OSM entries lack IDs or share coordinates; index avoids duplicate LazyColumn keys.
                        itemsIndexed(filtered) { _, station ->
                            val favorite = com.kramttocs.lubelink.core.FuelFavoriteRules.findFavorite(favorites, station)
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { selectStation(station) }
                                    .padding(vertical = 10.dp)) {
                                    Text(favorite?.name ?: station.name,
                                        color = if (selectedNearby == station) Gold else MaterialTheme.colorScheme.onSurface,
                                        fontWeight = FontWeight.SemiBold)
                                    (favorite?.address?.takeIf { it.isNotBlank() } ?: station.address)
                                        .takeIf { it.isNotBlank() }?.let {
                                            Text(it, style = MaterialTheme.typography.bodySmall)
                                        }
                                    station.distanceMeters?.let {
                                        Text("%.1f mi %s".format(Locale.US, it / 1609.344,
                                            if (resultsFromMapArea) "from search center" else "away"),
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                                IconButton(onClick = { editFavorite(favorite ?: station) }) {
                                    Icon(if (favorite != null) Icons.Outlined.Edit else Icons.Outlined.StarBorder,
                                        contentDescription = if (favorite != null) "Edit favorite" else "Save as favorite", tint = Gold)
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
                visibleSelection?.let { station ->
                    val favorite = com.kramttocs.lubelink.core.FuelFavoriteRules.findFavorite(favorites, station)
                    val displayAddress = confirmedAddress?.takeIf { it.first == station }?.second
                        ?: favorite?.address?.takeIf { it.isNotBlank() } ?: station.address
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(favorite?.name ?: station.name, fontWeight = FontWeight.SemiBold)
                            if (displayAddress.isNotBlank()) Text(displayAddress,
                                style = MaterialTheme.typography.bodySmall)
                        }
                        IconButton(onClick = { editFavorite(favorite ?: station) }) {
                            Icon(if (favorite != null) Icons.Outlined.Edit else Icons.Outlined.StarBorder,
                                contentDescription = "Favorite settings", tint = Gold)
                        }
                    }
                    if (!geoapifyKey.isNullOrBlank() &&
                        station.latitude != null && station.longitude != null &&
                        StationAddressPolicy.shouldOffer(displayAddress) &&
                        confirmedAddress?.first != station) {
                        OutlinedButton(onClick = { suggestAddress(station) },
                            enabled = enrichingStation != station,
                            modifier = Modifier.fillMaxWidth()) {
                            if (enrichingStation == station)
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Outlined.LocationOn, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (enrichingStation == station) "Finding street address…"
                                else "Find missing street address")
                        }
                    }
                    enrichmentError?.let { Text(it, color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { choose(station) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Use station")
                    }
                } ?: Text("Tap a pin or station to choose it. Star saves a favorite.",
                    style = MaterialTheme.typography.bodySmall)
                Text("Maps © OpenFreeMap · Data © OpenStreetMap contributors",
                    style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    proposedAddress?.let { (station, address) ->
        AlertDialog(onDismissRequest = { proposedAddress = null },
            title = { LubeDialogTitle("Suggested station address") },
            text = {
                DialogBody(spacing = 8.dp) {
                    Text(address, fontWeight = FontWeight.SemiBold)
                    Text("This is a nearby mapped address and may belong to a neighboring property. Confirm it before using it for this fuel record. Your favorites and past records won't change.",
                        style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = {
                if (selectedNearby == station && mapOpen && page == StationPickerPage.NEARBY)
                    confirmedAddress = station to address
                proposedAddress = null
            }) { Text("Use this address") } },
            dismissButton = { TextButton(onClick = { proposedAddress = null }) { Text("Keep original") } }
        )
    }

    editingFavorite?.let { station ->
        AlertDialog(onDismissRequest = { editingFavorite = null },
            title = { LubeDialogTitle(if (station.favoriteId == null) "Save favorite station" else "Edit favorite station") },
            text = {
                DialogBody(spacing = 10.dp) {
                    OutlinedTextField(editName, { editName = it.take(100) },
                        label = { Text("Name") }, keyboardOptions = TextKeyboard, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(editAddress, { editAddress = it.take(240) },
                        label = { Text("Address") }, keyboardOptions = TextKeyboard, modifier = Modifier.fillMaxWidth())
                    Text("This changes only the local favorite and future selections. Existing fuel records are never updated.",
                        style = MaterialTheme.typography.bodySmall)
                    favoriteError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (station.favoriteId != null) {
                        if (confirmRemove) {
                            Text("Remove this station from Favorites? Past fuel records stay unchanged.")
                            TextButton(onClick = {
                                stationStore.removeFavorite(station.favoriteId)
                                favorites = stationStore.favorites()
                                editingFavorite = null
                            }) { Text("Yes, remove favorite") }
                        } else TextButton(onClick = { confirmRemove = true }) { Text("Remove favorite") }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val accepted = if (station.favoriteId != null)
                        stationStore.renameFavorite(station.favoriteId, editName, editAddress)
                    else stationStore.saveFavorite(station.copy(name = editName, address = editAddress))
                    if (accepted) {
                        favorites = stationStore.favorites()
                        editingFavorite = null
                    } else favoriteError = "Could not save favorite. Check the name or favorite limit."
                }, enabled = editName.isNotBlank()) { Text("Save favorite") }
            },
            dismissButton = { TextButton(onClick = { editingFavorite = null }) { Text("Cancel") } }
        )
    }
}
