package com.kramttocs.lubelink.core

import androidx.core.content.edit
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Device-local, opt-in favorites scoped to the connected server + API key identity.
 * No selection, record save, or station-name edit automatically creates a favorite.
 * The legacy auto-saved list is retained but is NOT treated as favorites.
 */
class FuelStationStore(context: Context, server: String, apiKey: String) {
    private val prefs = StoredFormats.prefs(context, StoredFormat.FUEL_FAVORITES)
    private val scope = java.security.MessageDigest.getInstance("SHA-256")
        .digest("${server.trim().trimEnd('/')}\n$apiKey".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }.take(24)
    private val key = "favorites_$scope"

    fun favorites(): List<FuelStation> = runCatching {
        val array = JSONArray(prefs.getString(key, "[]") ?: "[]")
        (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val id = item.optString("favoriteId")
            if (id.isBlank()) return@mapNotNull null
            FuelStation(item.optString("name"), item.optString("address"),
                osmId = item.optString("osmId").takeIf { it.isNotBlank() },
                favoriteId = id,
                latitude = item.optDouble("lat").takeIf { it.isFinite() && it in -90.0..90.0 },
                longitude = item.optDouble("lon").takeIf { it.isFinite() && it in -180.0..180.0 }
            ).normalized().takeIf { it.name.isNotBlank() }
        }.take(60)
    }.getOrDefault(emptyList())

    fun findFavorite(station: FuelStation): FuelStation? =
        FuelFavoriteRules.findFavorite(favorites(), station)

    /** For a saved OSM favorite, use the locally edited label only on future selections. */
    fun resolve(station: FuelStation): FuelStation =
        findFavorite(station)?.copy(distanceMeters = station.distanceMeters) ?: station

    fun saveFavorite(station: FuelStation): Boolean {
        val cleaned = station.normalized()
        if (cleaned.name.isBlank()) return false
        val previous = favorites()
        val old = FuelFavoriteRules.findFavorite(previous, cleaned)
        if (old == null && previous.size >= 60) return false
        val item = cleaned.copy(favoriteId = old?.favoriteId ?: cleaned.favoriteId
            ?: java.util.UUID.randomUUID().toString())
        write(listOf(item) + previous.filterNot { it.favoriteId == item.favoriteId })
        return true
    }

    fun renameFavorite(favoriteId: String, name: String, address: String): Boolean {
        val trimmed = name.trim().take(100)
        if (trimmed.isBlank()) return false
        val previous = favorites()
        if (previous.none { it.favoriteId == favoriteId }) return false
        write(previous.map { if (it.favoriteId == favoriteId) it.copy(name = trimmed,
            address = address.trim().take(240)) else it })
        return true
    }

    fun removeFavorite(favoriteId: String) {
        write(favorites().filterNot { it.favoriteId == favoriteId })
    }

    private fun write(stations: List<FuelStation>) {
        val array = JSONArray()
        stations.forEach { item ->
            array.put(JSONObject().put("favoriteId", item.favoriteId)
                .put("name", item.name).put("address", item.address)
                .put("osmId", item.osmId ?: "")
                .put("lat", item.latitude ?: "")
                .put("lon", item.longitude ?: ""))
        }
        prefs.edit { putString(key, array.toString()) }
    }
}

/** A typed provider failure; messages are display-only and must never contain the key. */
internal class GeoapifyHttpException(val statusCode: Int) : java.io.IOException("Geoapify HTTP $statusCode")

/**
 * The location and station lookups behind the station picker (OD-42): the picker calls this interface,
 * never [FuelStationLookup], so a test can give it a lookup that fails, never returns or returns late.
 */
interface StationLookups {
    fun hasLocationPermission(context: Context): Boolean
    fun recentLocation(context: Context): Location?
    suspend fun freshLocation(context: Context): Location?
    suspend fun currentLocation(context: Context): Location
    suspend fun addressAt(context: Context, position: Location): String
    suspend fun suggestedStationAddress(key: String, station: FuelStation): String
    suspend fun nearbyWithProvider(position: Location, forceRefresh: Boolean, geoapifyKey: String?,
                                   bounds: StationBounds?): FuelStationLookup.SearchResult
}

/** The lookups against the phone's location services, Overpass, Geoapify and the platform geocoder. */
object DeviceStationLookups : StationLookups {
    override fun hasLocationPermission(context: Context) = FuelStationLookup.hasLocationPermission(context)
    override fun recentLocation(context: Context) = FuelStationLookup.recentLocation(context)
    override suspend fun freshLocation(context: Context) = FuelStationLookup.freshLocation(context)
    override suspend fun currentLocation(context: Context) = FuelStationLookup.currentLocation(context)
    override suspend fun addressAt(context: Context, position: Location) = FuelStationLookup.addressAt(context, position)
    override suspend fun suggestedStationAddress(key: String, station: FuelStation) =
        FuelStationLookup.suggestedStationAddress(key, station)
    override suspend fun nearbyWithProvider(position: Location, forceRefresh: Boolean, geoapifyKey: String?,
                                            bounds: StationBounds?) =
        FuelStationLookup.nearbyWithProvider(position, forceRefresh, geoapifyKey, bounds)
}

/**
 * The location is requested only after an explicit button press, and is NOT
 * stored locally or uploaded to LubeLogger. A nearby-search request sends a
 * coordinate to Geoapify (when configured) or Overpass, never the LubeLogger API key.
 */
object FuelStationLookup {
    // Process-local cache. No location coordinates or station searches are written to disk.
    // Reopen Nearby without querying public Overpass instances again for nearby positions.
    data class SearchResult(val stations: List<FuelStation>, val provider: StationSearchProvider,
                            val fallbackNotice: String? = null, val skipped: Int = 0) {
        /** What the station list says about this search: a provider fallback and any stations left out. */
        val notice: String? get() = listOfNotNull(fallbackNotice, StationResponses.skippedNotice(skipped))
            .joinToString(" ").ifBlank { null }
    }
    private data class CacheEntry(val latitude: Double, val longitude: Double,
                                  val storedAt: Long, val identity: String, val result: SearchResult)
    private val cacheLock = Any()
    private var cached: CacheEntry? = null

    private fun nearCached(position: Location, now: Long, forceRefresh: Boolean, identity: String): SearchResult? {
        val entry = synchronized(cacheLock) { cached } ?: return null
        if (entry.identity != identity) return null
        val age = now - entry.storedAt
        val distance = FloatArray(1)
        Location.distanceBetween(position.latitude, position.longitude,
            entry.latitude, entry.longitude, distance)
        if (!StationAreaCachePolicy.reuse(age, distance[0], forceRefresh)) return null
        return entry.result.copy(stations = entry.result.stations.map { station -> relativeTo(station, position) }
            .sortedBy { it.distanceMeters ?: Int.MAX_VALUE })
    }

    private fun relativeTo(station: FuelStation, position: Location): FuelStation {
        val lat = station.latitude ?: return station
        val lon = station.longitude ?: return station
        val distance = FloatArray(2)
        Location.distanceBetween(position.latitude, position.longitude, lat, lon, distance)
        val directions = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val direction = directions[((distance[1] + 22.5f + 360f) % 360f / 45f).toInt() % 8]
        return station.copy(distanceMeters = distance[0].toInt(), direction = direction)
    }

    /** One primary request and one alternate instance. Cancel interrupts blocking IO where possible. */
    private suspend fun overpassElements(query: String): String {
        val paths = listOf("https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter")
        var lastFailure: Exception? = null
        for (base in paths) {
            try {
                return runInterruptible(Dispatchers.IO) { overpassOnce(base, query) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw IllegalStateException("Nearby lookup unavailable on both map-data servers. Try again or enter the station manually.", lastFailure)
    }

    /** Connection and stream are closed on success, failure, timeout or cancellation. */
    private fun overpassOnce(base: String, query: String): String {
        val endpoint = URL("$base?data=${URLEncoder.encode(query, "UTF-8")}")
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = Timeouts.PROVIDER_CONNECT
            connection.readTimeout = Timeouts.OVERPASS_READ
            connection.setRequestProperty("User-Agent", "LubeLink-Android/1.0.2 (user-initiated fuel station lookup)")
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            if (code !in 200..299) {
                if (code in 400..499 && code != 429) {
                    throw IllegalArgumentException("Station lookup rejected (HTTP $code). Try again later.")
                }
                throw java.io.IOException("Overpass returned HTTP $code")
            }
            val bytes = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Lookup canceled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 600_000) throw java.io.IOException("Station response too large")
                    bytes.write(buffer, 0, count)
                }
            }
            return bytes.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
    }
    fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** A very recent OS location fix is almost immediate and avoids waiting for GPS. */
    @Suppress("MissingPermission")
    fun recentLocation(context: Context): Location? {
        if (!hasLocationPermission(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val now = System.currentTimeMillis()
        return listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { provider ->
                if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) null
                else runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter { (now - it.time) in 0L..120_000L &&
                (!it.hasAccuracy() || it.accuracy <= 1500f) }
            .maxByOrNull { it.time }
    }

    /** A best-effort fresh fix; may return null. Called in parallel after a cached fix. */
    @Suppress("MissingPermission")
    suspend fun freshLocation(context: Context): Location? = withContext(Dispatchers.IO) {
        if (!hasLocationPermission(context)) return@withContext null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        // One new fix from each enabled provider; available on every supported version (Android 11+, OD-81).
        for (provider in providers) {
            val fix = withTimeoutOrNull(5_000) {
                suspendCancellableCoroutine<Location?> { continuation ->
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    try {
                        manager.getCurrentLocation(provider, signal, context.mainExecutor) { result ->
                            if (continuation.isActive) continuation.resume(result)
                        }
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
            if (fix != null && (System.currentTimeMillis() - fix.time) in 0L..120_000L)
                return@withContext fix
        }
        null
    }

    @Suppress("MissingPermission")
    suspend fun currentLocation(context: Context): Location = withContext(Dispatchers.IO) {
        if (!hasLocationPermission(context)) throw IllegalStateException("Location permission is required")
        recentLocation(context)?.let { return@withContext it }
        freshLocation(context)?.let { return@withContext it }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).mapNotNull { provider ->
            if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) null
            else runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.filter { (System.currentTimeMillis() - it.time) in 0L..600_000L }
            .maxByOrNull { it.time }
            ?: throw IllegalStateException("Couldn't get a recent location. Try outdoors or enter the station manually")
    }

    /** Distance to determine if a new GPS fix requires a new station search. */
    fun distanceMeters(a: Location, b: Location): Float = FloatArray(1).also { distance ->
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, distance)
    }[0]

    @Suppress("DEPRECATION")
    suspend fun addressAt(context: Context, position: Location): String = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) throw IllegalStateException("Address lookup isn't available on this device")
        val address = runCatching {
            Geocoder(context, Locale.US).getFromLocation(position.latitude, position.longitude, 1)?.firstOrNull()
        }.getOrNull() ?: throw IllegalStateException("Couldn't resolve an address here. Enter it manually")
        address.getAddressLine(0).orEmpty().ifBlank {
            listOfNotNull(address.thoroughfare, address.locality, address.adminArea)
                .filter { it.isNotBlank() }.joinToString(", ")
        }.ifBlank { throw IllegalStateException("No street address found. Enter it manually") }
    }

    /**
     * HTTPS-only, user-initiated Places request. Never put the credential in an
     * exception, log, error widget, or persisted station/favorite object.
     * This public API authenticates through apiKey query param per Geoapify docs.
     */
    private fun geoapifyOnce(key: String, position: Location, maxResults: Int = 100,
                             bounds: StationBounds? = null): String {
        val lat = "%.3f".format(Locale.US, position.latitude)
        val lon = "%.3f".format(Locale.US, position.longitude)
        val filters = "categories=service.vehicle.fuel&filter=" +
            URLEncoder.encode(bounds?.geoapifyFilter() ?: "circle:$lon,$lat,3500", "UTF-8") +
            "&bias=" + URLEncoder.encode("proximity:$lon,$lat", "UTF-8") +
            "&limit=$maxResults&apiKey=" + URLEncoder.encode(key, "UTF-8")
        val connection = URL("https://api.geoapify.com/v2/places?$filters")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = Timeouts.PROVIDER_CONNECT
            connection.readTimeout = Timeouts.GEOAPIFY_READ
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "LubeLink-Android/1.0.2")
            val code = connection.responseCode
            if (code !in 200..299) {
                // Deliberately omit URLs, body and key from all public error messages.
                throw GeoapifyHttpException(code)
            }
            val bytes = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Lookup canceled")
                    val size = input.read(buffer)
                    if (size < 0) break
                    if (bytes.size() + size > 1_000_000) throw java.io.IOException("Places response too large")
                    bytes.write(buffer, 0, size)
                }
            }
            return bytes.toString("UTF-8")
        } finally {
            connection.disconnect()
        }
    }

    /** Optional, on-demand address suggestion. Never auto-saves an approximate geocoding result. */
    suspend fun suggestedStationAddress(key: String, station: FuelStation): String = runInterruptible(Dispatchers.IO) {
        val lat = station.latitude ?: throw IllegalArgumentException("Station coordinates unavailable")
        val lon = station.longitude ?: throw IllegalArgumentException("Station coordinates unavailable")
        val params = "lat=${String.format(Locale.US, "%.7f", lat)}" +
            "&lon=${String.format(Locale.US, "%.7f", lon)}&format=json&limit=1&apiKey=" +
            URLEncoder.encode(key.trim(), "UTF-8")
        val connection = URL("https://api.geoapify.com/v1/geocode/reverse?$params")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = Timeouts.PROVIDER_CONNECT
            connection.readTimeout = Timeouts.GEOAPIFY_READ
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "LubeLink-Android/1.0.2")
            if (connection.responseCode !in 200..299)
                throw java.io.IOException("Address suggestion unavailable (HTTP ${connection.responseCode})")
            val bytes = java.io.ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Lookup canceled")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (bytes.size() + count > 80_000) throw java.io.IOException("Address result too large")
                    bytes.write(buffer, 0, count)
                }
            }
            StationResponses.suggestedAddress(bytes.toString("UTF-8"))
        } finally {
            connection.disconnect()
        }
    }

    /** Checks key validity using a fixed public US location, not the phone's GPS. */
    suspend fun testGeoapifyKey(key: String): Boolean = runInterruptible(Dispatchers.IO) {
        val point = Location("key-test").apply { latitude = 38.8977; longitude = -77.0365 }
        StationResponses.checkGeoapify(geoapifyOnce(key.trim(), point, maxResults = 1))
        true // A valid, well-formed FeatureCollection may have zero results.
    }

    private fun geoapifyPlaces(text: String, position: Location): StationResponses.Parsed =
        StationResponses.geoapify(text).let { parsed ->
            parsed.copy(stations = parsed.stations.map { relativeTo(it, position) }
                .distinctBy { it.osmId ?: "${it.name.lowercase(Locale.US)}|${it.latitude}|${it.longitude}" }
                .sortedBy { it.distanceMeters ?: Int.MAX_VALUE }.take(100))
        }

    private suspend fun overpassPlaces(position: Location, bounds: StationBounds? = null): StationResponses.Parsed {
        val latitude = "%.3f".format(Locale.US, position.latitude)
        val longitude = "%.3f".format(Locale.US, position.longitude)
        val area = bounds?.overpassBBox()
        val query = if (area == null)
            "[out:json][timeout:15];nwr(around:3500,$latitude,$longitude)[\"amenity\"=\"fuel\"];out center 60;"
        else "[out:json][timeout:15];nwr[\"amenity\"=\"fuel\"]$area;out center 100;"
        val parsed = StationResponses.overpass(overpassElements(query))
        return parsed.copy(stations = parsed.stations.map { relativeTo(it, position) }
            .distinctBy { it.osmId ?: "${it.name.lowercase(Locale.US)}|${it.address.lowercase(Locale.US)}|${it.latitude}|${it.longitude}" }
            .sortedBy { it.distanceMeters ?: Int.MAX_VALUE }.take(if (bounds == null) 30 else 100))
    }

    /** Cache is provider-scoped so adding/removing/replacing a key cannot show stale data. */
    private fun providerIdentity(key: String?): String = if (key.isNullOrBlank()) "overpass" else
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .take(12).joinToString("") { "%02x".format(it) }

    suspend fun nearbyWithProvider(position: Location, forceRefresh: Boolean = false,
                                   geoapifyKey: String? = null,
                                   bounds: StationBounds? = null): SearchResult = withContext(Dispatchers.IO) {
        // A new viewport must NEVER reuse a cached circular search from a different footprint.
        if (bounds != null) require(bounds.isSearchable()) { "Zoom in to search this area." }
        val key = geoapifyKey?.trim()?.takeIf { it.isNotBlank() }
        val identity = providerIdentity(key)
        if (bounds == null) nearCached(position, System.currentTimeMillis(), forceRefresh, identity)
            ?.let { return@withContext it }
        var fallbackNotice: String? = null
        if (key != null) {
            try {
                val places = runInterruptible(Dispatchers.IO) { geoapifyOnce(key, position, bounds = bounds) }
                val parsed = geoapifyPlaces(places, position)
                val result = SearchResult(parsed.stations, StationSearchProvider.GEOAPIFY, skipped = parsed.skipped)
                if (bounds == null) synchronized(cacheLock) { cached = CacheEntry(position.latitude, position.longitude,
                    System.currentTimeMillis(), identity, result) }
                return@withContext result
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Overpass fallback keeps the UI usable; surface a nonsecret notice
                // rather than silently hiding bad credentials or a provider outage.
                fallbackNotice = StationSearchPolicy.fallbackNotice((e as? GeoapifyHttpException)?.statusCode,
                    unreadable = e is StationDataException)
            }
        }
        val parsed = try {
            overpassPlaces(position, bounds)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (key != null) throw IllegalStateException(
                "Geoapify and Overpass both failed. Check your key or enter a station manually.", e)
            throw e
        }
        val fallback = SearchResult(parsed.stations, StationSearchProvider.OVERPASS, fallbackNotice, parsed.skipped)
        // A temporary fallback must not suppress the next attempt to recover Geoapify.
        if (key == null && bounds == null) synchronized(cacheLock) { cached = CacheEntry(position.latitude,
            position.longitude, System.currentTimeMillis(), identity, fallback) }
        fallback
    }
}
