package com.kramttocs.lubelink.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The selected vehicle's record history; production is [LubeLoggerApi], UI tests use a fake. */
fun interface VehicleHistorySource {
    suspend fun loadHistory(vehicleId: VehicleId, includeFuel: Boolean): RecordsLoad
}

/** The account data the app reads from LubeLogger; production is [LubeLoggerApi], tests use fakes. */
internal interface LubeLoggerReader : VehicleHistorySource {
    suspend fun loadGarage(): GarageData
    suspend fun loadCalendarHistory(includeFuel: Boolean): RecordsLoad
    suspend fun checkGasStationFields(): GasStationFieldCheck
}

/** Vehicle pictures for the Garage and the vehicle overview; production is [LubeLoggerApi], tests use fakes. */
internal interface VehicleImages {
    /** The picture already in memory or on disk, or null; never a network call. */
    suspend fun getCachedImage(path: String): Bitmap?
    /** The picture from the server when [refreshGeneration] asks for a fresh copy, else the cached one. */
    suspend fun getImage(path: String, refreshGeneration: Int = 0): Bitmap?
}

/** The record-details attachment download; production is [LubeLoggerApi], tests use fakes (OD-42). */
internal fun interface AttachmentReader {
    suspend fun downloadAttachment(path: String): ByteArray
}

/** v1.7.3 READ-ONLY client. No PUT/POST/DELETE code exists here on purpose. */
internal class LubeLoggerApi(
    baseUrl: String, private val apiKey: String,
    private val diskCache: ImageDiskCache? = null
) : LubeLoggerReader, AttachmentReader, VehicleImages {
    private val base = MaintenanceRules.allowedServerUrl(baseUrl)
        ?: throw IllegalArgumentException("An HTTPS server URL is required")
    private val refreshedImageGeneration = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val imageCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun getBytes(path: String, image: Boolean = false): ByteArray {
        require(path.startsWith("/"))
        require(if (image) MaintenanceRules.allowedImagePath(path) else path.startsWith("/api/"))
        val connection = URL(base + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = Timeouts.LUBELOGGER_CONNECT
            connection.readTimeout = Timeouts.LUBELOGGER_READ
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("culture-invariant", "true")
            connection.setRequestProperty("Accept", if (image) "image/*" else "application/json")
            val status = connection.responseCode
            if (status !in 200..299) {
                throw ApiException(httpFailureMessage(status, "API key"), status)
            }
            val mime = connection.contentType.orEmpty().lowercase()
            if (image && !mime.startsWith("image/")) throw ApiException("Server did not return an image")
            if (!image && !mime.contains("json")) throw ApiException(NOT_LUBELOGGER_MESSAGE)
            // Calendar/Reports may include years of records. Keep a bounded, larger cap
            // only for aggregate endpoints; individual API responses remain limited.
            val limit = when {
                image -> 8 * 1024 * 1024
                path.startsWith("/api/vehicle/") && path.substringBefore('?').endsWith("/all") -> 32 * 1024 * 1024
                else -> 5 * 1024 * 1024
            }
            connection.inputStream.use { source ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = source.read(buffer)
                    if (count == -1) break
                    if (output.size() + count > limit) throw ApiException("Response exceeded size limit")
                    output.write(buffer, 0, count)
                }
                return output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Authenticated, bounded attachment fetch. Never open raw remote links with a browser. */
    override suspend fun downloadAttachment(path: String): ByteArray = withContext(Dispatchers.IO) {
        if (!MaintenanceRules.allowedAttachmentPath(path))
            throw ApiException("LubeLink cannot open this attachment safely. Open it in LubeLogger's web page.")
        val connection = URL(base + path.replace(" ", "%20")).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = false
            connection.connectTimeout = Timeouts.LUBELOGGER_CONNECT
            connection.readTimeout = Timeouts.ATTACHMENT_READ
            connection.setRequestProperty("x-api-key", apiKey)
            connection.setRequestProperty("Accept", "*/*")
            if (connection.responseCode !in 200..299) throw ApiException(
                "Attachment unavailable (HTTP ${connection.responseCode})")
            val maxBytes = 24 * 1024 * 1024
            connection.contentLengthLong.takeIf { it > 0 }?.let {
                if (it > maxBytes) throw ApiException("Attachment exceeds 24 MB limit")
            }
            val out = ByteArrayOutputStream()
            connection.inputStream.use { stream ->
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val size = stream.read(chunk)
                    if (size < 0) break
                    if (out.size() + size > maxBytes) throw ApiException("Attachment exceeds 24 MB limit")
                    out.write(chunk, 0, size)
                }
            }
            if (out.size() == 0) throw ApiException("Attachment is empty")
            out.toByteArray()
        } finally { connection.disconnect() }
    }

    private fun objectGet(path: String): JSONObject = JSONObject(getBytes(path).toString(Charsets.UTF_8))
    private fun arrayGet(path: String): JSONArray = JSONArray(getBytes(path).toString(Charsets.UTF_8))

    /** Read-only configuration discovery. A malformed response fails rather than hiding controls. */
    override suspend fun checkGasStationFields(): GasStationFieldCheck = withContext(Dispatchers.IO) {
        val read = ResponseFields("the custom-field setup")
        val fields = mutableListOf<ConfiguredField>()
        for (group in read.objects(arrayGet("/api/extrafields"))) {
            val type = read.identity(group, "recordType")
            for (field in read.objects(read.array(group, "extraFields"))) {
                // The setup lists the type by name ("Text"); a record lists it by number. Either is accepted.
                val fieldType = when (val value = if (field.isNull("fieldType")) null else field.opt("fieldType")) {
                    is String -> value.ifBlank { read.fail("fieldType") }
                    is Int, is Long -> value.toString()
                    else -> read.fail("fieldType")
                }
                fields.add(ConfiguredField(type, read.identity(field, "name"), fieldType, read.boolean(field, "isRequired")))
            }
        }
        RecordFieldRules.gasStationStatus(fields)
    }

    /** Succeeds only when the server answers /api/whoami as LubeLogger does, with the key's user name. */
    suspend fun checkConnection(): String = withContext(Dispatchers.IO) {
        ResponseFields("the account check").identity(objectGet("/api/whoami"), "username")
    }

    override suspend fun loadGarage(): GarageData = withContext(Dispatchers.IO) {
        val vehicleFields = ResponseFields("the vehicle list")
        val vehicles = vehicleFields.objects(arrayGet("/api/vehicles")).map { item ->
            vehicleFields.run {
                Vehicle(
                    id = VehicleId(int(item, "id")),
                    year = int(item, "year"),
                    make = text(item, "make"),
                    model = text(item, "model"),
                    imageLocation = text(item, "imageLocation"),
                    useHours = boolean(item, "useHours"),
                    isElectric = boolean(item, "isElectric"),
                    details = VehicleDetailsRules.read(item)
                )
            }
        }.let { list -> vehicleFields.unique(list) { it.id } }
        // The vehicles are required. Summaries and reminders that fail are reported as missing, so one failing call
        // does not hide the whole Garage (OD-24); the vehicles are still shown.
        val missing = mutableSetOf<GarageSection>()
        val causes = mutableMapOf<GarageSection, Exception>()
        fun <T> orMissing(section: GarageSection, empty: T, load: () -> T): T = try { load() }
            catch (e: java.util.concurrent.CancellationException) { throw e } // coroutine cancellation
            catch (e: Exception) { missing += section; causes[section] = e; empty }
        val summaryFields = ResponseFields("the vehicle summaries")
        val summaries = orMissing(GarageSection.SUMMARIES, emptyMap()) { summaryFields.objects(arrayGet("/api/vehicle/info")).map { item ->
            summaryFields.run {
                VehicleSummary(
                    VehicleId(int(obj(item, "vehicleData"), "id")),
                    longOrNull(item, "lastReportedOdometer"),
                    int(item, "urgentReminderCount") + int(item, "veryUrgentReminderCount"),
                    int(item, "pastDueReminderCount"),
                    int(item, "serviceRecordCount")
                )
            }
        }.let { list -> summaryFields.unique(list, "vehicleData") { it.vehicleId } }.associateBy { it.vehicleId } }
        val reminderFields = ResponseFields("the reminders")
        val reminders = orMissing(GarageSection.REMINDERS, emptyList()) { reminderFields.objects(arrayGet("/api/vehicle/reminders/all")).map { item ->
            reminderFields.run {
                MaintenanceReminder(
                    ReminderId(int(item, "id")), VehicleId(int(item, "vehicleId")), text(item, "description"),
                    text(item, "urgency"),
                    ReminderScheduleRules.configuredMetric(text(item, "userMetric"), text(item, "metric")),
                    text(item, "dueDate"), longOrNull(item, "dueOdometer"),
                    intOrNull(item, "dueDays"), longOrNull(item, "dueDistance"),
                    text(item, "notes"),
                    // LubeLogger 1.7.4 does not send it; absent is "not known", never "not recurring".
                    isRecurring = if (item.has("isRecurring") && !item.isNull("isRecurring"))
                        boolean(item, "isRecurring") else null
                )
            }
        }.let { list -> reminderFields.unique(list) { it.id } } }
        GarageData(vehicles, summaries, reminders, missing, causes)
    }

    override suspend fun loadHistory(vehicleId: VehicleId, includeFuel: Boolean): RecordsLoad = withContext(Dispatchers.IO) {
        require(vehicleId.isValid)
        loadByKind(includeFuel) { kind -> parseRecords(arrayGet(MaintenanceRules.historyEndpoint(kind, vehicleId)), kind, vehicleId) }
    }

    /** One GET per record category, across authorized vehicles, loaded only when Calendar opens. */
    override suspend fun loadCalendarHistory(includeFuel: Boolean): RecordsLoad = withContext(Dispatchers.IO) {
        loadByKind(includeFuel) { kind -> parseRecords(arrayGet(MaintenanceRules.historyEndpoint(kind)), kind, null) }
    }

    /** One request per record type. A type that fails is reported missing and the others are kept, so one failing
     * request does not hide the rest (OD-31). When every type fails, the first failure is thrown as before, so
     * a wrong key or an unreachable server still shows its own message. */
    private fun loadByKind(includeFuel: Boolean, load: (RecordKind) -> List<ServiceRecord>): RecordsLoad {
        val types = mutableListOf(RecordKind.SERVICE, RecordKind.REPAIR, RecordKind.UPGRADE)
        if (includeFuel) types.add(RecordKind.FUEL)
        val missing = mutableSetOf<RecordKind>()
        var firstFailure: Exception? = null
        val records = types.flatMap { kind ->
            try { load(kind) }
            catch (e: java.util.concurrent.CancellationException) { throw e } // coroutine cancellation
            catch (e: Exception) { missing += kind; firstFailure = firstFailure ?: e; emptyList() }
        }
        if (missing.size == types.size) throw firstFailure!!
        return RecordsLoad(MaintenanceRules.orderedHistory(records), missing)
    }

    /** Invoked only by the Reports screen. Includes all four expense kinds even if ordinary History is configured
     * to hide Fuel. No extra reporting backend is used. A total needs every type, so a missing one fails it. */
    suspend fun loadReportDataset(): ReportDataset = loadCalendarHistory(includeFuel = true).let { load ->
        if (load.missing.isNotEmpty()) throw IncompleteRecordsException(load)
        ReportDataset(load.records)
    }

    private fun parseRecords(data: JSONArray, kind: RecordKind, expectedVehicle: VehicleId?): List<ServiceRecord> {
        val fields = ResponseFields("the ${kind.label} records")
        return fields.objects(data).map { item ->
            fields.run {
                val vehicleId = VehicleId(int(item, "vehicleId"))
                if (expectedVehicle != null && vehicleId != expectedVehicle) fail("vehicleId")
                // Every attachment is listed. One whose location is not a LubeLogger upload folder is refused
                // when tapped (downloadAttachment), with a message, rather than hidden here.
                val attachments = objects(array(item, "files")).map { f -> Attachment(text(f, "name"), text(f, "location")) }
                ServiceRecord(
                    RecordId(int(item, "id")), vehicleId, text(item, "date"),
                    if (kind == RecordKind.FUEL) "Fuel" else text(item, "description"),
                    longOrNull(item, "odometer"), decimalText(item, "cost"),
                    text(item, "notes"), text(item, "tags"), attachments, kind,
                    fuelEconomy = if (kind == RecordKind.FUEL) doubleOrNull(item, "fuelEconomy") else null,
                    fuelConsumed = if (kind == RecordKind.FUEL) doubleOrNull(item, "fuelConsumed") else null,
                    fuelStation = if (kind == RecordKind.FUEL) extraFieldValue(array(item, "extraFields"), RecordFieldRules.GAS_STATION) else "",
                    fuelStationAddress = if (kind == RecordKind.FUEL) extraFieldValue(array(item, "extraFields"), RecordFieldRules.STATION_ADDRESS) else ""
                )
            }
        }.let { list -> fields.unique(list) { it.id } } // one record type per call: the id alone identifies it
    }

    /** Decode capped-resolution artwork; the full-size original is never retained in memory. */
    private fun decodeImage(bytes: ByteArray): Bitmap? {
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, dimensions)
        if (dimensions.outWidth <= 0 || dimensions.outHeight <= 0) return null
        var sample = 1
        while (maxOf(dimensions.outWidth, dimensions.outHeight) / (sample * 2) >= 1600) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun imageKey(path: String) = "$base\n$apiKey\n$path"

    /**
     * Immediately return previously cached artwork without waiting for the server.
     * Used first by Compose so tapping Refresh never blanks vehicle images.
     */
    override suspend fun getCachedImage(path: String): Bitmap? = withContext(Dispatchers.IO) {
        if (!MaintenanceRules.allowedImagePath(path)) return@withContext null
        imageCache.get(path)?.let { return@withContext it }
        val bitmap = diskCache?.get(imageKey(path))?.let(::decodeImage)
        if (bitmap != null) imageCache.put(path, bitmap)
        bitmap
    }

    /**
     * Only the first uncached view or an explicit refresh fetches full-sized artwork.
     * Save a downsized, compressed rendition in the private disk cache to make
     * subsequent launches fast. This does not accelerate the FIRST server download.
     */
    override suspend fun getImage(path: String, refreshGeneration: Int): Bitmap? = withContext(Dispatchers.IO) {
        if (!MaintenanceRules.allowedImagePath(path)) return@withContext null
        val fallback = getCachedImage(path)
        val shouldRefresh = refreshGeneration > 0 && refreshedImageGeneration[path] != refreshGeneration
        if (fallback != null && !shouldRefresh) return@withContext fallback
        try {
            val freshBytes = getBytes(path, image = true)
            val fresh = decodeImage(freshBytes) ?: throw ApiException("Could not decode vehicle image")
            imageCache.put(path, fresh)
            // Preserve original source bytes to avoid JPEG re-encoding of text-heavy artwork.
            // ImageDiskCache independently enforces its bounded LRU size.
            diskCache?.put(imageKey(path), freshBytes)
            refreshedImageGeneration[path] = refreshGeneration
            fresh
        } catch (error: Exception) {
            refreshedImageGeneration[path] = refreshGeneration
            fallback ?: throw error
        }
    }

    class ApiException(message: String, val httpStatus: Int? = null) : Exception(message)
}

