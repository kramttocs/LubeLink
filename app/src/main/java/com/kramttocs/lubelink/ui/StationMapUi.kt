package com.kramttocs.lubelink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kramttocs.lubelink.core.FuelStation
import com.kramttocs.lubelink.core.StationBounds
import org.maplibre.android.MapLibre
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView

internal data class StationMapViewport(val latitude: Double, val longitude: Double, val zoom: Double)

/** One read-only map for the same gas-station search results used by the Nearby list.
 * OpenFreeMap provides map imagery. Map taps never send requests to LubeLogger. */
@Composable
internal fun StationMap(stations: List<FuelStation>, onSelect: (FuelStation) -> Unit,
                        viewport: StationMapViewport?, cameraRevision: Int,
                        onUserMoved: (StationMapViewport, StationBounds) -> Unit,
                        onMapFailure: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val mapView = remember(context, owner) {
        MapLibre.getInstance(context)
        MapView(context).apply { onCreate(null) }
    }
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var ready by remember(mapView) { mutableStateOf(false) }
    val byMarkerId = remember(mapView) { mutableMapOf<Long, FuelStation>() }
    val alive = remember(mapView) { java.util.concurrent.atomic.AtomicBoolean(true) }
    val currentSelect by androidx.compose.runtime.rememberUpdatedState(onSelect)
    val currentFailure by androidx.compose.runtime.rememberUpdatedState(onMapFailure)
    val currentMovement by androidx.compose.runtime.rememberUpdatedState(onUserMoved)

    DisposableEffect(owner, mapView) {
        // Style failures should not strand the user on a blank map. The same Nearby list remains usable.
        val failed = MapView.OnDidFailLoadingMapListener {
            if (alive.get()) currentFailure()
        }
        mapView.addOnDidFailLoadingMapListener(failed)
        // The picker may open after the parent Activity is already STARTED/RESUMED.
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStart()
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> Unit
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            alive.set(false)
            mapView.removeOnDidFailLoadingMapListener(failed)
            owner.lifecycle.removeObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
            mapView.onDestroy()
        }
    }

    // Install one marker listener. Marker identities are refreshed when results change.
    AndroidView(factory = {
        mapView.apply {
            getMapAsync { loaded ->
                if (!alive.get()) return@getMapAsync
                map = loaded
                loaded.setOnMarkerClickListener { marker ->
                    byMarkerId[marker.id]?.let { currentSelect(it) }
                    true
                }
                loaded.setStyle("https://tiles.openfreemap.org/styles/liberty") {
                    if (alive.get()) ready = true
                }
            }
        }
    }, modifier = modifier)

    // Only actual user gestures expose Search this area. The API's own centering and
    // pin updates must never cause a request or make that action appear.
    DisposableEffect(map) {
        val mapInstance = map
        if (mapInstance == null) {
            onDispose { }
        } else {
            var userGesture = false
            val started = MapLibreMap.OnCameraMoveStartedListener { reason ->
                // Programmatic recentering must also clear any interrupted gesture flag.
                userGesture = reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE
            }
            val idle = MapLibreMap.OnCameraIdleListener {
                if (userGesture && alive.get()) {
                    userGesture = false
                    mapInstance.cameraPosition.let { camera ->
                        camera.target?.let { center ->
                            val bounds = mapInstance.projection.visibleRegion.latLngBounds
                            currentMovement(StationMapViewport(center.latitude, center.longitude, camera.zoom),
                                StationBounds(bounds.longitudeWest, bounds.latitudeSouth, bounds.longitudeEast, bounds.latitudeNorth))
                        }
                    }
                }
            }
            mapInstance.addOnCameraMoveStartedListener(started)
            mapInstance.addOnCameraIdleListener(idle)
            onDispose {
                mapInstance.removeOnCameraMoveStartedListener(started)
                mapInstance.removeOnCameraIdleListener(idle)
            }
        }
    }

    LaunchedEffect(map, ready, stations) {
        val mapInstance = map ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        mapInstance.clear()
        byMarkerId.clear()
        stations.forEach { station ->
            val lat = station.latitude ?: return@forEach
            val lon = station.longitude ?: return@forEach
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@forEach
            val marker = mapInstance.addMarker(MarkerOptions()
                .position(LatLng(lat, lon)).title(station.name))
            byMarkerId[marker.id] = station
        }
    }

    // Camera moves ONLY on initial mounting or a deliberate phone-location request.
    // Searching at the map center updates pins without snapping to the first station.
    LaunchedEffect(map, ready, cameraRevision) {
        val mapInstance = map ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val center = viewport ?: stations.firstOrNull { it.latitude != null && it.longitude != null }
            ?.let { StationMapViewport(it.latitude!!, it.longitude!!, 15.5) }
        center?.let {
            mapInstance.cameraPosition = CameraPosition.Builder()
                .target(LatLng(it.latitude, it.longitude)).zoom(it.zoom).build()
        }
    }
}
