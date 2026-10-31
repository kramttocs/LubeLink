package com.kramttocs.lubelink.quality

import android.content.Context
import android.location.Location
import com.kramttocs.lubelink.core.FuelStation
import com.kramttocs.lubelink.core.FuelStationLookup
import com.kramttocs.lubelink.core.StationBounds
import com.kramttocs.lubelink.core.StationLookups
import com.kramttocs.lubelink.core.StationSearchProvider
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.StoredFormats
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.SocketException
import java.security.KeyStore
import java.time.LocalDate
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory
import org.json.JSONArray
import org.json.JSONObject

/**
 * A realistic, entirely synthetic garage for the screen tour and screenshots: four vehicles of different kinds,
 * a year of service, repair, upgrade and fuel records with notes and stations, and reminders at every urgency.
 * Dates are relative to today so Calendar and Reminders look current. Every value here is made up; the user-data
 * check keeps them out of the app's source (tests/sample-values.txt).
 *
 * Answers have the shape of the LubeLogger 1.7.4 capture (tests/contract), so the app's real parsers read them.
 */
internal object ScreenshotGarage {
    /**
     * Clears every LubeLink setting, so a whole-app test starts as a fresh install would. On the emulator, settings
     * outlive a test: start-up opens the last vehicle by default, so a tour that follows another would begin on it.
     */
    fun startFresh(context: Context) {
        withoutReminders = emptySet()
        extraRecords = emptyList()
        StoredFormats.prefs(context, StoredFormat.SETTINGS).edit().clear()
            .putInt(StoredFormats.VERSION_KEY, StoredFormat.SETTINGS.version).commit()
    }

    /** Station lookups that answer at once: one position, and two synthetic stations with coordinates and no street
     * address (two more left out), so the Nearby page can be drawn without a device location or a provider. */
    val stationLookups: StationLookups = object : StationLookups {
        override fun hasLocationPermission(context: Context) = true
        override fun recentLocation(context: Context): Location? = null
        override suspend fun freshLocation(context: Context): Location? = null
        override suspend fun currentLocation(context: Context) =
            Location("synthetic").apply { latitude = 0.5; longitude = 0.5; time = 1 }
        override suspend fun addressAt(context: Context, position: Location) = "1 Synthetic Way, Example City"
        override suspend fun suggestedStationAddress(key: String, station: FuelStation) = "3 Synthetic Avenue, Example City"
        override suspend fun nearbyWithProvider(position: Location, forceRefresh: Boolean, geoapifyKey: String?,
                                                bounds: StationBounds?) = FuelStationLookup.SearchResult(listOf(
            FuelStation("Synthetic Fuel Stop With A Long Name", latitude = 0.51, longitude = 0.5, distanceMeters = 800),
            FuelStation("Synthetic Corner Garage", latitude = 0.52, longitude = 0.5, distanceMeters = 1600)),
            StationSearchProvider.GEOAPIFY, skipped = 2)
    }

    /** Nearby opens on its List view for the account at [baseUrl] with the screenshot key (the picker's per-account
     * preference), so no map is drawn: MapLibre's native library is not available on the JVM. */
    fun preferNearbyList(context: Context, baseUrl: String) {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${baseUrl.trim()}\n$EDITOR_KEY".toByteArray(Charsets.UTF_8))
        StoredFormats.prefs(context, StoredFormat.STATION_VIEW).edit()
            .putString("nearby_view_" + digest.take(12).joinToString("") { "%02x".format(it) }, "List").commit()
    }

    const val EDITOR_KEY = "synthetic-screenshots-editor"
    const val MANAGER_KEY = "synthetic-screenshots-manager"
    private val today: LocalDate = LocalDate.now()
    private fun daysAgo(days: Long) = today.minusDays(days).toString()

    data class Car(val id: Int, val year: Int, val make: String, val model: String, val electric: Boolean = false,
                   val hours: Boolean = false, val odometer: Int)
    val cars = listOf(
        Car(1, 2019, "Honda", "CR-V", odometer = 48_210),
        Car(2, 2016, "Ford", "F-150", odometer = 102_730),
        Car(3, 2022, "Hyundai", "Ioniq 5", electric = true, odometer = 18_940),
        Car(4, 2012, "Kubota", "L3301", hours = true, odometer = 1_284))

    data class Rec(val kind: String, val id: Int, val car: Int, val daysAgo: Long, val odometer: Int,
                   val description: String, val cost: Double, val notes: String = "", val tags: String = "",
                   val gallons: Double = 0.0, val economy: Double = 0.0, val station: String = "",
                   val address: String = "")
    val records = listOf(
        Rec("servicerecords", 1, 1, 6, 48_150, "Oil and filter change", 68.40,
            "Full synthetic 0W-20, new OEM filter. Topped up washer fluid.", "oil"),
        Rec("servicerecords", 2, 1, 120, 44_900, "Tire rotation and balance", 39.99, "Rotated front to back."),
        Rec("servicerecords", 3, 2, 21, 102_500, "Transmission fluid service", 189.00,
            "Drain and fill, about 5 quarts. Next due at 150,000.", "fluids"),
        Rec("servicerecords", 4, 4, 40, 1_270, "Engine oil and hydraulic filter", 112.35, "Every 200 hours."),
        Rec("servicerecords", 5, 3, 60, 17_800, "Cabin air filter", 24.99),
        Rec("repairrecords", 6, 2, 14, 102_610, "Front brake pads and rotors", 412.87,
            "Pads were at 2 mm. The rotors were too thin to resurface, so both were replaced.", "brakes"),
        Rec("repairrecords", 7, 1, 200, 41_200, "Replace rear wiper motor", 145.00),
        Rec("upgraderecords", 8, 1, 75, 46_000, "All-weather floor mats", 129.95),
        Rec("upgraderecords", 9, 2, 160, 98_400, "Tonneau cover", 649.00, "Tri-fold, hard shell."),
        Rec("gasrecords", 10, 1, 3, 48_190, "", 52.10, gallons = 13.2, economy = 29.4,
            station = "Corner Fuel & Wash", address = "410 Maple Ave, Riverton"),
        Rec("gasrecords", 11, 1, 11, 47_802, "", 49.75, gallons = 12.6, economy = 28.7,
            station = "Hilltop Gas", address = "22 Ridge Rd, Riverton"),
        Rec("gasrecords", 12, 2, 5, 102_690, "", 88.34, gallons = 24.1, economy = 17.2,
            station = "Hilltop Gas", address = "22 Ridge Rd, Riverton"),
        Rec("gasrecords", 13, 4, 30, 1_262, "", 41.20, gallons = 9.8))

    data class Reminder(val id: Int, val car: Int, val description: String, val urgency: String, val metric: String,
                        val dueInDays: Long?, val dueOdometer: Int?, val notes: String = "")
    /**
     * Vehicles whose reminders the server leaves out, as if none were set up in LubeLogger: the screen tour sets the
     * Kubota's, to draw the suggested reminders (OD-52). [startFresh] clears it, so other tests see every one.
     */
    var withoutReminders: Set<Int> = emptySet()

    /** Records the server answers besides [records], for a test that needs one more (a reading that drops, OD-63).
     * [startFresh] clears it. */
    var extraRecords: List<Rec> = emptyList()
    /** Every record the server answers: [records] and [extraRecords]. */
    fun served(): List<Rec> = records + extraRecords
    private fun servedReminders() = reminders.filter { it.car !in withoutReminders }

    val reminders = listOf(
        Reminder(1, 1, "Tire rotation", "Urgent", "Date", dueInDays = 8, dueOdometer = null, notes = "Every 7,500 miles."),
        Reminder(2, 2, "State inspection", "PastDue", "Date", dueInDays = -4, dueOdometer = null),
        Reminder(3, 2, "Coolant flush", "VeryUrgent", "Odometer", dueInDays = null, dueOdometer = 103_000),
        Reminder(4, 3, "Cabin air filter", "NotUrgent", "Date", dueInDays = 300, dueOdometer = null),
        Reminder(5, 4, "Grease fittings", "Urgent", "Odometer", dueInDays = null, dueOdometer = 1_300))

    private fun vehicleJson(c: Car) = JSONObject().put("id", c.id).put("imageLocation", "/images/vehicle-${c.id}.jpg")
        .put("mapLocation", JSONObject.NULL).put("year", c.year).put("make", c.make).put("model", c.model)
        // The vehicle details dialog: the CR-V has a purchase and tags, the F-150 runs on diesel, the rest
        // have none set (LubeLogger's null dates and 0 prices).
        .put("licensePlate", "SYN-${100 + c.id}")
        .put("purchaseDate", if (c.id == 1) "2019-03-14" else JSONObject.NULL).put("soldDate", JSONObject.NULL)
        .put("purchasePrice", if (c.id == 1) 27450 else 0).put("soldPrice", 0).put("isElectric", c.electric)
        .put("isDiesel", c.id == 2).put("useHours", c.hours).put("odometerOptional", false).put("extraFields", JSONArray())
        .put("tags", if (c.id == 1) JSONArray().put("family").put("commuter") else JSONArray())
        .put("hasOdometerAdjustment", false).put("odometerMultiplier", 1).put("odometerDifference", 0)
        .put("dashboardMetrics", JSONArray()).put("vehicleIdentifier", "LicensePlate")

    internal fun reminderJson(r: Reminder) = JSONObject().put("vehicleId", r.car).put("id", r.id)
        .put("description", r.description).put("urgency", r.urgency).put("metric", r.metric).put("userMetric", r.metric)
        .put("notes", r.notes).put("dueDate", r.dueInDays?.let { today.plusDays(it).toString() } ?: "")
        .put("dueOdometer", r.dueOdometer ?: 0).put("dueDays", r.dueInDays ?: 0)
        .put("dueDistance", r.dueOdometer?.let { it - cars.first { c -> c.id == r.car }.odometer } ?: 0).put("tags", "")

    private fun recordJson(r: Rec): JSONObject {
        val o = JSONObject().put("vehicleId", r.car).put("id", r.id).put("date", daysAgo(r.daysAgo))
            .put("odometer", r.odometer).put("cost", r.cost).put("notes", r.notes).put("tags", r.tags)
            .put("files", JSONArray())
        if (r.kind == "gasrecords") {
            val extras = JSONArray()
            if (r.station.isNotBlank()) extras.put(JSONObject().put("name", "LubeLink Gas Station").put("value", r.station)
                .put("isRequired", false).put("fieldType", 0))
            if (r.address.isNotBlank()) extras.put(JSONObject().put("name", "LubeLink Station Address").put("value", r.address)
                .put("isRequired", false).put("fieldType", 0))
            o.put("fuelConsumed", r.gallons).put("fuelEconomy", r.economy).put("isFillToFull", true)
                .put("missedFuelUp", false).put("startingSoc", 0).put("endingSoc", 0).put("extraFields", extras)
        } else o.put("description", r.description).put("extraFields", JSONArray())
        return o
    }

    private fun summaryJson(c: Car): JSONObject {
        val mine = servedReminders().filter { it.car == c.id }
        val next = mine.minByOrNull { it.dueInDays ?: 0 }
        return JSONObject().put("vehicleData", vehicleJson(c))
            .put("veryUrgentReminderCount", mine.count { it.urgency == "VeryUrgent" })
            .put("urgentReminderCount", mine.count { it.urgency == "Urgent" })
            .put("notUrgentReminderCount", mine.count { it.urgency == "NotUrgent" })
            .put("pastDueReminderCount", mine.count { it.urgency == "PastDue" })
            .put("nextReminder", next?.let(::reminderJson) ?: JSONObject.NULL)
            .put("serviceRecordCount", served().count { it.car == c.id && it.kind == "servicerecords" })
            .put("lastReportedOdometer", c.odometer)
    }

    /** The JSON answer for a GET, or null when the path is not a LubeLogger call the app makes. */
    fun answer(path: String): String? {
        val route = path.substringBefore('?')
        val query = path.substringAfter('?', "").split('&').filter { it.contains('=') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
        return when {
            route == "/api/whoami" -> JSONObject().put("username", "screenshots").put("emailAddress", "")
                .put("isAdmin", false).put("isRoot", false).toString()
            route == "/api/vehicles" -> JSONArray(cars.map(::vehicleJson)).toString()
            route == "/api/vehicle/info" -> JSONArray(cars.map(::summaryJson)).toString()
            route == "/api/vehicle/reminders/all" -> JSONArray(servedReminders().map(::reminderJson)).toString()
            route == "/api/extrafields" -> JSONArray().put(JSONObject().put("recordType", "GasRecord").put("extraFields",
                JSONArray().put(JSONObject().put("name", "LubeLink Gas Station").put("isRequired", false).put("fieldType", "Text"))
                    .put(JSONObject().put("name", "LubeLink Station Address").put("isRequired", false).put("fieldType", "Text")))).toString()
            route.startsWith("/api/vehicle/") -> {
                val parts = route.removePrefix("/api/vehicle/").split('/')
                val kind = parts[0]
                if (kind !in setOf("servicerecords", "repairrecords", "upgraderecords", "gasrecords")) return null
                val all = parts.getOrNull(1) == "all"
                JSONArray(served().filter { r -> r.kind == kind &&
                    (all || r.car.toString() == query["vehicleId"]) && (query["id"] == null || r.id.toString() == query["id"])
                }.map(::recordJson)).toString()
            }
            else -> null
        }
    }

    /** Each vehicle's picture, a 2.5:1 studio image like LubeLogger's own (test resources `garage/`, 1200 × 480 JPEG). */
    fun image(path: String): ByteArray? {
        val id = Regex("^/images/vehicle-([0-9]+)\\.jpg$").find(path)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (cars.none { it.id == id }) return null
        return checkNotNull(ScreenshotGarage::class.java.classLoader!!.getResourceAsStream("garage/vehicle-$id.jpg")) {
            "garage/vehicle-$id.jpg is missing from the test resources"
        }.use { it.readBytes() }
    }
}

/**
 * A local HTTPS server on the emulator that answers as LubeLogger would, from [ScreenshotGarage]. It uses a test-only
 * self-signed certificate for localhost (src/test/ui/resources), trusted only inside this test process. GETs only:
 * the screenshot run opens forms but never saves.
 */
/** [override] answers a GET path first, for tests that need one malformed answer (null = the garage's own answer).
 * A path [fails] says true for gets HTTP 500, for tests where one call fails. With [acceptAdds] an Add is answered as
 * LubeLogger confirms one, with record ID [ADDED_ID] (nothing is stored: [override] supplies the read-back). */
internal class ScreenshotServer(private val override: (String) -> String? = { null },
                                private val fails: (String) -> Boolean = { false },
                                private val dropDeleteReply: Boolean = false,
                                private val acceptAdds: Boolean = false) : AutoCloseable {
    /** Every DELETE that reached this server (the transport must never resend one). */
    val deletes = java.util.concurrent.atomic.AtomicInteger()
    /** Every Add (POST) that reached this server. */
    val posts = java.util.concurrent.atomic.AtomicInteger()
    private val server: SSLServerSocket
    private val threads = Executors.newCachedThreadPool()
    @Volatile private var running = true
    val baseUrl: String

    init {
        val store = KeyStore.getInstance("PKCS12")
        // A Java resource, so the same file is found on the JVM and in the emulator's test APK.
        checkNotNull(ScreenshotServer::class.java.classLoader!!.getResourceAsStream("screenshot-localhost.p12")) {
            "screenshot-localhost.p12 is missing from the test resources"
        }.use { store.load(it, "changeit".toCharArray()) }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(store, "changeit".toCharArray()) }
        val trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        val tls = SSLContext.getInstance("TLS").apply { init(keys.keyManagers, trust.trustManagers, null) }
        HttpsURLConnection.setDefaultSSLSocketFactory(tls.socketFactory)
        server = tls.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("127.0.0.1")) as SSLServerSocket
        baseUrl = "https://localhost:${server.localPort}"
        threads.execute {
            while (running) {
                val socket = try { server.accept() } catch (_: SocketException) { break }
                threads.execute { runCatching { socket.use { handle(BufferedInputStream(it.getInputStream()), it.getOutputStream()) } } }
            }
        }
    }

    private fun handle(input: BufferedInputStream, output: OutputStream) {
        while (true) {
            val start = line(input) ?: return
            if (start.isBlank()) continue
            val (method, path) = start.split(' ').let { it[0] to it[1] }
            var key = ""
            var length = 0
            while (true) {
                val header = line(input) ?: return
                if (header.isEmpty()) break
                if (header.startsWith("x-api-key:", ignoreCase = true)) key = header.substringAfter(':').trim()
                if (header.startsWith("content-length:", ignoreCase = true)) length = header.substringAfter(':').trim().toInt()
            }
            repeat(length) { if (input.read() < 0) return }
            if (method == "DELETE") {
                deletes.incrementAndGet()
                if (dropDeleteReply) return // the server has the DELETE; its reply is lost with the connection
                respond(output, 200, "application/json", "{\"success\":true}".toByteArray())
                continue
            }
            val authorised = key == ScreenshotGarage.EDITOR_KEY || key == ScreenshotGarage.MANAGER_KEY
            if (method == "POST" && acceptAdds && authorised && path.matches(Regex("^/api/vehicle/[a-z]+/add\\?vehicleId=[0-9]+$"))) {
                posts.incrementAndGet()
                respond(output, 200, "application/json",
                    "{\"success\":true,\"message\":\"Added\",\"additionalData\":{\"recordId\":$ADDED_ID}}".toByteArray())
                continue
            }
            val image = if (method == "GET" && authorised) ScreenshotGarage.image(path) else null
            val json = if (method == "GET" && authorised && image == null) override(path) ?: ScreenshotGarage.answer(path) else null
            when {
                authorised && fails(path) -> respond(output, 500, "text/html", "<html>synthetic server fault</html>".toByteArray())
                image != null -> respond(output, 200, "image/jpeg", image)
                json != null -> respond(output, 200, "application/json; charset=utf-8", json.toByteArray())
                !authorised -> respond(output, 401, "text/html", "<html>Unauthorized</html>".toByteArray())
                else -> respond(output, 404, "application/json", "{}".toByteArray())
            }
        }
    }

    private fun line(input: BufferedInputStream): String? {
        val out = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) return if (out.size() == 0) null else out.toString("UTF-8")
            if (c == '\n'.code) return out.toString("UTF-8").trimEnd('\r')
            out.write(c)
        }
    }

    private fun respond(output: OutputStream, status: Int, type: String, body: ByteArray) {
        output.write(("HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\nContent-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\nConnection: keep-alive\r\n\r\n").toByteArray())
        output.write(body)
        output.flush()
    }

    companion object { const val ADDED_ID = 900 }

    override fun close() {
        running = false
        runCatching { server.close() }
        threads.shutdownNow()
    }
}
