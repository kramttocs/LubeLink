package com.kramttocs.lubelink.core

import java.io.ByteArrayOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/** What an Edit did: saved and confirmed by its read-back, or nothing to save (no request was sent). */
enum class EditOutcome { SAVED, NOTHING_TO_SAVE }

/** What the record editor needs from LubeLogger. Production is [LubeLoggerEditorApi]; UI tests use a
 * fake so Save, discard and stale-callback behaviour can be exercised at the real dialog. */
interface RecordEditorGateway {
    suspend fun addRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId, draft: EditorDraft): RecordId
    suspend fun loadEditableRecord(vehicle: Vehicle, record: RecordRef): LubeLoggerEditorApi.EditableSnapshot
    suspend fun updateRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId,
                             snapshot: LubeLoggerEditorApi.EditableSnapshot, draft: EditorDraft): EditOutcome
}

/** Editor API for selected vehicles. No DELETE method or Manager key is accepted. */
class LubeLoggerEditorApi(baseUrl: String, private val editorKey: String) : RecordEditorGateway {
    private val base = MaintenanceRules.allowedServerUrl(baseUrl)
        ?: throw IllegalArgumentException("A valid HTTPS server is required")

    /** Returns only when the server answers /api/whoami as LubeLogger does, with the key's user name. */
    suspend fun verifyConnection(): Unit = withContext(Dispatchers.IO) {
        ResponseFields("the account check").identity(request("GET", "/api/whoami", null), "username")
    }

    /** GETs that run before POST/PUT cannot have created a record.
     * Connection, TLS, parsing and permission failures here are DEFINITE no-write
     * outcomes. A failure during/after the write remains uncertain.
     */
    internal inline fun <T> beforeWrite(block: () -> T): T = try { block() }
        catch (e: EditorApiException) { throw e }
        catch (e: IllegalArgumentException) { throw e }
        catch (e: UnreadableResponseException) { throw EditorApiException("${e.message} Nothing was sent.") }
        catch (_: Exception) {
            throw EditorApiException("Could not verify LubeLogger before saving. Nothing was sent.")
        }

    override suspend fun addRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId, draft: EditorDraft): RecordId = withContext(Dispatchers.IO) {
        require(EditorRules.canWrite(vehicle, confirmedVehicleId)) { "Selected vehicle ID mismatch" }
        if (EditorRules.unsupportedChargingWrite(vehicle, draft.kind))
            throw EditorApiException("Add charging records for electric vehicles in LubeLogger. Nothing was sent.")
        // Reject invalid drafts locally before making even a read-only identity request.
        EditorRules.validate(draft)?.let { throw IllegalArgumentException(it) }
        // Recheck the selected vehicle identity against the server before every write.
        beforeWrite { verifyVehicleIdentity(vehicle) }
        val path = EditorRules.endpoint(vehicle, draft.kind, confirmedVehicleId)
        val json = JSONObject()
            .put("Date", draft.date)
            .put("Odometer", draft.odometer)
            .put("Cost", decimalJson(draft.cost))
            .put("Notes", draft.notes.trim())
            .put("Tags", draft.tags.trim())
        if (draft.kind == RecordKind.FUEL) {
            json.put("FuelConsumed", decimalJson(draft.gallons))
                .put("IsFillToFull", draft.filledTank.toString())
                .put("MissedFuelUp", draft.missedFill.toString())
                .put("ExtraFields", mergeStationFields(JSONArray(), draft))
        } else json.put("Description", draft.description.trim())
        val reply = try { request("POST", path, json) }
        catch (e: WriteUncertainException) { throw e }
        catch (e: EditorApiException) { throw e } // Explicit 4xx: definite rejection.
        catch (e: Exception) {
            // The response can be lost AFTER the server commits. Do not repeat this POST.
            throw WriteUncertainException("Create result uncertain. Check LubeLogger before retrying.")
        }
        if (!reply.optBoolean("success", false)) throw WriteUncertainException("Create not confirmed by LubeLogger. Check for a saved record before retrying.")
        // Read strictly: a missing, fractional or non-numeric ID is not confirmed (optInt would turn 15.5 into 15).
        val id = try { reply.optJSONObject("additionalData")?.let { ResponseFields("the create reply").intOrNull(it, "recordId") } }
            catch (_: UnreadableResponseException) { null }
            ?.let(::RecordId)
        if (id == null || !id.isValid) throw WriteUncertainException("LubeLogger accepted the new ${draft.kind.label} record but did not confirm which record it saved. Check LubeLogger before retrying.")
        // Verify what actually landed; if verification fails, DO NOT automatically repeat a POST.
        val verify = try {
            JSONArray(requestBytes("GET",
                "/api/vehicle/${EditorRules.resource(draft.kind)}?vehicleId=${vehicle.id}&id=$id${if (draft.kind == RecordKind.FUEL) "&useMPG=true&useUKMPG=false" else ""}", null)
                .toString(Charsets.UTF_8))
        } catch (e: Exception) {
            throw WriteUncertainException("The new ${draft.kind.label} record may be saved, but LubeLink could not read it back. Check LubeLogger before retrying.")
        }
        // A read-back that cannot be read (a malformed or foreign entry, two records with this ID, a missing field)
        // leaves the outcome uncertain; it is never matched against blanks.
        val unreadable = "The new ${draft.kind.label} record may be saved, but LubeLink could not read it back. Check LubeLogger before retrying."
        val created = try { ResponseFields("the new ${draft.kind.label} record").recordWithId(verify, vehicle.id, id) }
            catch (_: UnreadableResponseException) { throw WriteUncertainException(unreadable) }
            ?: throw WriteUncertainException("LubeLogger confirmed the new ${draft.kind.label} record, but LubeLink could not find it afterwards. Check LubeLogger before retrying.")
        val valuesMatch = try { readBackMatches(created, draft) }
            catch (_: UnreadableResponseException) { throw WriteUncertainException(unreadable) }
        if (!valuesMatch)
            throw WriteUncertainException("The new ${draft.kind.label} record was saved, but its values in LubeLogger differ from what you entered. Check it in LubeLogger before retrying.")
        // The custom-field list is required: a missing or non-list one is unreadable, never "no station".
        val stationSaved = draft.kind != RecordKind.FUEL || try {
            val extras = savedRecord.array(created, "extraFields")
            stationValue(extras, STATION_FIELD) == draft.stationName.trim() &&
                stationValue(extras, ADDRESS_FIELD) == draft.stationAddress.trim()
        } catch (_: UnreadableResponseException) { throw WriteUncertainException(unreadable) }
        if (!stationSaved)
            throw WriteUncertainException("The new Fuel record was saved, but the gas station details did not save correctly. Check the record in LubeLogger.")
        id
    }

    /** Complete server snapshot used for edit prefill and optimistic concurrency checks. */
    data class EditableSnapshot(
        val ref: RecordRef,
        val originalJson: String,
        val draft: EditorDraft
    ) {
        val kind: RecordKind get() = ref.kind
    }

    /** Fetch all fields through the Editor key, not the simplified viewer timeline model. */
    override suspend fun loadEditableRecord(vehicle: Vehicle, record: RecordRef): EditableSnapshot = withContext(Dispatchers.IO) {
        require(EditorRules.canWrite(vehicle, record.vehicleId)) { "Selected vehicle ID mismatch" }
        require(record.id.isValid)
        if (EditorRules.unsupportedChargingWrite(vehicle, record.kind))
            throw EditorApiException("Edit charging records for electric vehicles in LubeLogger. Nothing was sent.")
        verifyVehicleIdentity(vehicle)
        snapshotFrom(record, recordById(record))
    }

    /** No DELETE. Sends complete Files/ExtraFields unchanged. */
    override suspend fun updateRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId, snapshot: EditableSnapshot,
                                      draft: EditorDraft): EditOutcome = withContext(Dispatchers.IO) {
        require(EditorRules.canWrite(vehicle, confirmedVehicleId)) { "Selected vehicle ID mismatch" }
        require(snapshot.ref.vehicleId == confirmedVehicleId && snapshot.ref.id.isValid && snapshot.kind == draft.kind)
        if (EditorRules.unsupportedChargingWrite(vehicle, snapshot.kind))
            throw EditorApiException("Edit charging records for electric vehicles in LubeLogger. Nothing was sent.")
        EditorRules.validate(draft, snapshot.draft)?.let { throw IllegalArgumentException(it) }
        // No-op edits never reach LubeLogger. A no-op neither updates timestamps
        // nor creates an unnecessary uncertain-write outcome on a lost reply.
        if (!EditorRules.hasPersistedEditChanges(snapshot.draft, draft)) return@withContext EditOutcome.NOTHING_TO_SAVE
        val current = beforeWrite {
            verifyVehicleIdentity(vehicle)
            val fresh = recordById(snapshot.ref)
            requirePreservedCollections(fresh)
            if (editableSignature(fresh, snapshot.kind) != editableSignature(JSONObject(snapshot.originalJson), snapshot.kind)) {
                throw EditorApiException("Record changed in LubeLogger since you opened it. Close and reopen Edit before saving.")
            }
            fresh
        }
        // Server-side v1.7.3 PUT replaces these collections. Never omit or empty them.
        val files = JSONArray(current.getJSONArray("files").toString())
        val extras = JSONArray(current.getJSONArray("extraFields").toString())
        val expectedExtras = if (snapshot.kind == RecordKind.FUEL &&
            (draft.stationName != snapshot.draft.stationName || draft.stationAddress != snapshot.draft.stationAddress))
            mergeStationFields(extras, draft) else extras
        fun preserved(now: String, original: String): String = if (now == original) original else now.trim()
        val savedCost = preserved(draft.cost, snapshot.draft.cost)
        val savedNotes = preserved(draft.notes, snapshot.draft.notes)
        val savedTags = preserved(draft.tags, snapshot.draft.tags)
        val savedDescription = preserved(draft.description, snapshot.draft.description)
        val payload = JSONObject()
            .put("id", snapshot.ref.id.toString())
            .put("vehicleId", vehicle.id.toString())
            .put("date", draft.date)
            .put("odometer", draft.odometer)
            .put("cost", decimalJson(savedCost))
            .put("notes", savedNotes)
            .put("tags", savedTags)
            .put("files", files)
            .put("extraFields", expectedExtras)
        if (snapshot.kind == RecordKind.FUEL) {
            payload.put("fuelConsumed", decimalJson(draft.gallons))
                .put("isFillToFull", draft.filledTank.toString())
                .put("missedFuelUp", draft.missedFill.toString())
                // The edit form does not yet expose EV state-of-charge values.
                .put("startingSoc", current.fieldText("startingSoc"))
                .put("endingSoc", current.fieldText("endingSoc"))
        } else payload.put("description", savedDescription)
        val reply = try { request("PUT", "/api/vehicle/${EditorRules.resource(snapshot.kind)}/update", payload) }
        catch (e: WriteUncertainException) { throw e }
        catch (e: EditorApiException) { throw e }
        catch (e: Exception) {
            throw WriteUncertainException("Edit result uncertain. Check this record in LubeLogger before attempting another edit.")
        }
        if (!reply.optBoolean("success", false))
            throw WriteUncertainException("LubeLogger did not confirm the edit. Check the record in the web interface.")
        val after = try { recordById(snapshot.ref) }
        catch (e: Exception) { throw WriteUncertainException("Edit acknowledged but read-back failed. Verify in LubeLogger.") }
        val matches = try {
            MaintenanceRules.parseDate(after.savedText("date"))?.toString() == draft.date &&
                after.savedReading() == draft.odometer.trim() &&
                after.savedText("notes") == savedNotes &&
                after.savedText("tags") == savedTags &&
                decimalValuesMatch(after.savedDecimal("cost"), savedCost) &&
                (snapshot.kind == RecordKind.FUEL || after.savedText("description") == savedDescription) &&
                (snapshot.kind != RecordKind.FUEL ||
                    (decimalValuesMatch(after.savedDecimal("fuelConsumed"), draft.gallons.trim()) &&
                    after.savedFlag("isFillToFull") == draft.filledTank &&
                    after.savedFlag("missedFuelUp") == draft.missedFill)) &&
                after.optJSONArray("files")?.toString() == files.toString() &&
                stationFieldsAndPreservedExtrasMatch(savedRecord.array(after, "extraFields"), expectedExtras)
        } catch (_: UnreadableResponseException) {
            throw WriteUncertainException("Edit acknowledged, but LubeLink could not read the saved record back. Verify in LubeLogger.")
        }
        if (!matches) throw WriteUncertainException("Edit acknowledged, but read-back differs. Inspect the record and attachments in LubeLogger.")
        EditOutcome.SAVED
    }

    /** Fail closed on mismatched values, but never retry a possibly committed write. */
    private fun readBackMatches(actual: JSONObject, draft: EditorDraft): Boolean =
        MaintenanceRules.parseDate(actual.savedText("date"))?.toString() == draft.date &&
        actual.savedReading() == draft.odometer &&
        decimalValuesMatch(actual.savedDecimal("cost"), draft.cost.trim()) &&
        actual.savedText("notes") == draft.notes.trim() &&
        actual.savedText("tags") == draft.tags.trim() &&
        (draft.kind == RecordKind.FUEL || actual.savedText("description") == draft.description.trim()) &&
        (draft.kind != RecordKind.FUEL ||
            (decimalValuesMatch(actual.savedDecimal("fuelConsumed"), draft.gallons.trim()) &&
                actual.savedFlag("isFillToFull") == draft.filledTank &&
                actual.savedFlag("missedFuelUp") == draft.missedFill))

    companion object {
        private const val STATION_FIELD = RecordFieldRules.GAS_STATION
        private const val ADDRESS_FIELD = RecordFieldRules.STATION_ADDRESS

        /** A fixed-size (64 hex characters) fingerprint of a snapshot's editable fields: what an Edit
         * form saves across process death instead of the record itself (OD-01). Two
         * snapshots with equal fingerprints have the same editable server state. */
        fun baselineFingerprint(snapshot: EditableSnapshot): String =
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(editableSignature(JSONObject(snapshot.originalJson), snapshot.kind).toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }

        /** Every field an Edit shows or sends back must be in the server's copy: a missing one would otherwise
         * open as blank or "no" and be written back over the real value. */
        private fun snapshotFrom(record: RecordRef, raw: JSONObject): EditableSnapshot {
            val kind = record.kind
            requirePreservedCollections(raw)
            val fields = ResponseFields("this record")
            if (kind == RecordKind.FUEL) {
                // The edit form does not show these electric-charge values, but Save sends them back unchanged.
                fields.decimalText(raw, "startingSoc")
                fields.decimalText(raw, "endingSoc")
            }
            val date = MaintenanceRules.parseDate(fields.text(raw, "date"))?.toString()
                ?: throw EditorApiException("Unrecognized record date. Editing blocked.")
            val fuel = kind == RecordKind.FUEL
            return EditableSnapshot(
                record, raw.toString(),
                EditorDraft(kind, date, fields.decimalText(raw, "odometer"),
                    if (fuel) "" else fields.text(raw, "description"),
                    fields.decimalText(raw, "cost"), fields.text(raw, "notes"), fields.text(raw, "tags"),
                    if (fuel) fields.decimalText(raw, "fuelConsumed") else "",
                    fuel && fields.boolean(raw, "isFillToFull"),
                    fuel && fields.boolean(raw, "missedFuelUp"),
                    stationValue(fields.array(raw, "extraFields"), STATION_FIELD),
                    stationValue(fields.array(raw, "extraFields"), ADDRESS_FIELD))
            )
        }

        /** Ignore only server-calculated fuel numbers in the optimistic edit snapshot. Shared with Delete. Each value
         * is length-prefixed and marked absent, JSON null or text, so no value can pass for a different split between
         * fields (LL-06). A form saved with the older format fails closed: its fingerprint no longer matches. */
        internal fun editableSignature(raw: JSONObject, kind: RecordKind): String {
            val fields = listOf("id", "vehicleId", "date", "odometer", "cost", "notes", "tags", "files", "extraFields") +
                if (kind == RecordKind.FUEL)
                    listOf("fuelConsumed", "isFillToFull", "missedFuelUp", "startingSoc", "endingSoc")
                else listOf("description")
            return fields.joinToString("") { name ->
                when {
                    !raw.has(name) -> "$name-;"
                    raw.isNull(name) -> "$name~;"
                    else -> raw.opt(name).toString().let { "$name=${it.length}:$it;" }
                }
            }
        }

        private fun stationValue(fields: JSONArray, fieldName: String): String =
            extraFieldValue(fields, fieldName)

        private fun JSONObject.fieldText(key: String): String = if (isNull(key)) "" else opt(key)?.toString().orEmpty()

        /** The record read back after a write, read with the same typed readers as the viewer: a field
         * that is missing, or holds another type than the contract's (a number where text was sent, text where a
         * number belongs), makes the write uncertain instead of matching by its printed form. */
        private val savedRecord = ResponseFields("the saved record")

        /** Text; JSON null reads as "". */
        private fun JSONObject.savedText(key: String): String = savedRecord.text(this, key)

        /** The odometer or hours: a whole number, or JSON null (read as ""). */
        private fun JSONObject.savedReading(): String = savedRecord.longOrNull(this, "odometer")?.toString() ?: ""

        /** A decimal as sent (cost, gallons); JSON null reads as "". */
        private fun JSONObject.savedDecimal(key: String): String =
            if (has(key) && isNull(key)) "" else savedRecord.decimalText(this, key)

        private fun JSONObject.savedFlag(key: String): Boolean = savedRecord.boolean(this, key)

        private fun requirePreservedCollections(raw: JSONObject) {
            val files = raw.optJSONArray("files")
            val extras = raw.optJSONArray("extraFields")
            if (files == null || extras == null)
                throw EditorApiException("Files or extra fields missing from API response. Editing blocked to prevent data loss.")
            // Every entry is sent back unchanged, so each must be one LubeLink can carry: never dropped or guessed.
            val fields = ResponseFields("the record's attachments and extra fields")
            fields.objects(files)
            fields.objects(extras)
        }
    }

    /** Do not overwrite any fields created by LubeLogger or other integrations. */
    private fun mergeStationFields(source: JSONArray, draft: EditorDraft): JSONArray {
        val merged = JSONArray()
        for (index in 0 until source.length()) {
            // Entries were checked to be objects before any write (requirePreservedCollections); none is skipped.
            val field = source.optJSONObject(index)
                ?: throw UnreadableResponseException("the record's extra fields", "entry ${index + 1}")
            if (field.optString("name", field.optString("Name")) !in setOf(STATION_FIELD, ADDRESS_FIELD))
                merged.put(JSONObject(field.toString()))
        }
        listOf(STATION_FIELD to draft.stationName.trim(), ADDRESS_FIELD to draft.stationAddress.trim())
            .filter { it.second.isNotBlank() }.forEach { (name, value) ->
                merged.put(JSONObject().put("name", name).put("value", value)
                    .put("isRequired", false).put("fieldType", 0))
            }
        return merged
    }

    /** Compare values without relying on serializer property ordering/casing. Every read-back entry must be an object
     * whose name is text and whose value is text or null: a number where text was sent is unreadable, not a
     * match. */
    private fun stationFieldsAndPreservedExtrasMatch(actual: JSONArray, expected: JSONArray): Boolean {
        if (actual.length() != expected.length()) return false
        fun normalized(array: JSONArray): List<String> = extraFieldEntries(array, "the saved record's custom fields")
            .map { (name, value) -> "$name\u0000$value" }.sorted()
        return normalized(actual) == normalized(expected)
    }

    /** LubeLogger 1.7.4 parses decimal *strings* in the server's culture (a de-DE server stores
     * "45.50" as 4550) but reads JSON numbers the same way in every culture. Verified against
     * en-US and de-DE servers. Blank stays blank; a value that is not a plain decimal is
     * sent unchanged so validation, not serialization, decides. */
    private fun decimalJson(text: String): Any = text.trim().let { it.toBigDecimalOrNull() ?: it }

    /** Decimal quantity value equality (e.g. 0, 0.0 and 0.00) for read-back. */
    private fun decimalValuesMatch(actual: String, requested: String): Boolean = runCatching {
        java.math.BigDecimal(actual).compareTo(java.math.BigDecimal(requested)) == 0
    }.getOrDefault(false)

    /** Prevent a stale selection from editing a different vehicle. */
    private fun verifyVehicleIdentity(vehicle: Vehicle) {
        val fields = ResponseFields("the vehicle list")
        val vehicles = fields.objects(JSONArray(requestBytes("GET", "/api/vehicles", null).toString(Charsets.UTF_8)))
        val matches = vehicles.filter { fields.int(it, "id") == vehicle.id.value }
        if (matches.size != 1 || fields.int(matches.single(), "year") != vehicle.year ||
            fields.text(matches.single(), "make") != vehicle.make ||
            fields.text(matches.single(), "model") != vehicle.model ||
            // What a write means depends on these (charging is refused for electric vehicles; the reading is miles
            // or hours), so a change since Garage loaded stops the write too (LL-04).
            fields.boolean(matches.single(), "isElectric") != vehicle.isElectric ||
            fields.boolean(matches.single(), "useHours") != vehicle.useHours) {
            throw EditorApiException("Vehicle information changed. Refresh Garage before saving.")
        }
    }

    private fun recordById(record: RecordRef): JSONObject {
        val (vehicleId, kind, id) = record
        require(vehicleId.isValid && id.isValid)
        val path = "/api/vehicle/${EditorRules.resource(kind)}?vehicleId=$vehicleId&id=$id" +
            if (kind == RecordKind.FUEL) "&useMPG=true&useUKMPG=false" else ""
        return ResponseFields("the ${kind.label} record")
            .recordWithId(JSONArray(requestBytes("GET", path, null).toString(Charsets.UTF_8)), vehicleId, id)
            ?: throw EditorApiException("Record missing in LubeLogger. Editing blocked.")
    }

    private fun request(method: String, path: String, body: JSONObject?): JSONObject =
        JSONObject(requestBytes(method, path, body).toString(Charsets.UTF_8))

    private fun requestBytes(method: String, path: String, body: JSONObject?): ByteArray {
        require(method == "GET" || method == "POST" || method == "PUT")
        val allowedGet = path == "/api/whoami" || path == "/api/vehicles" ||
            path.matches(Regex("^/api/vehicle/(?:servicerecords|repairrecords|upgraderecords|gasrecords)\\?vehicleId=[1-9][0-9]*&id=[1-9][0-9]*(?:&useMPG=true&useUKMPG=false)?$"))
        val allowedPost = path.matches(Regex("^/api/vehicle/(?:servicerecords|repairrecords|upgraderecords|gasrecords)/add\\?vehicleId=[1-9][0-9]*$"))
        val allowedPut = path.matches(Regex("^/api/vehicle/(?:servicerecords|repairrecords|upgraderecords|gasrecords)/update$"))
        require(when (method) { "GET" -> allowedGet; "POST" -> allowedPost; else -> allowedPut }) { "Request path not permitted" }
        require(editorKey.isNotBlank())
        val connection = URL(base + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = Timeouts.LUBELOGGER_CONNECT
            connection.readTimeout = Timeouts.LUBELOGGER_READ
            connection.setRequestProperty("x-api-key", editorKey)
            connection.setRequestProperty("culture-invariant", "true")
            connection.setRequestProperty("Accept", "application/json")
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // A buffered body may be automatically replayed when a reused HTTPS
                // connection breaks after LubeLogger commits a write. Streaming makes
                // the body non-replayable by HttpURLConnection; surface any lost reply
                // to WriteSafety as an uncertain result instead of sending it twice.
                val bodyBytes = body.toString().toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bodyBytes.size)
                connection.outputStream.use { it.write(bodyBytes) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                if (method != "GET" && (code >= 500 || code == 408 || code == 429))
                    throw WriteUncertainException("HTTP $code during write; check LubeLogger before retrying.")
                throw EditorApiException(httpFailureMessage(code, "Editor key"), code)
            }
            if (!connection.contentType.orEmpty().lowercase().contains("json")) {
                if (method != "GET") throw WriteUncertainException("Unexpected write response; inspect LubeLogger before retrying.")
                throw EditorApiException(NOT_LUBELOGGER_MESSAGE)
            }
            val data = ByteArrayOutputStream()
            connection.inputStream.use { source ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = source.read(buffer)
                    if (count == -1) break
                    if (data.size() + count > 512_000) {
                        if (method != "GET") throw WriteUncertainException("Unexpectedly large write response; verify the server record.")
                        throw EditorApiException("Response too large")
                    }
                    data.write(buffer, 0, count)
                }
            }
            return data.toByteArray()
        } finally { connection.disconnect() }
    }

    open class EditorApiException(message: String, open val httpStatus: Int? = null) : Exception(message)
    class WriteUncertainException(message: String) : EditorApiException(message)
}
