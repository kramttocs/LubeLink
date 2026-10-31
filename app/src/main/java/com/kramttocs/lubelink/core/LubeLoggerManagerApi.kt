package com.kramttocs.lubelink.core

import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** What the Delete dialog needs from LubeLogger. Production is [LubeLoggerManagerApi]; UI tests use a fake so
 * confirmation, repeated taps and a change of record can be exercised at the real dialog. */
interface RecordDeleteGateway {
    suspend fun prepare(vehicle: Vehicle, displayed: ServiceRecord): LubeLoggerManagerApi.DeleteSnapshot
    suspend fun delete(snapshot: LubeLoggerManagerApi.DeleteSnapshot)
}

/** Separate Manager credential used ONLY for deleting one verified record. */
class LubeLoggerManagerApi(baseUrl: String, private val managerKey: String) : RecordDeleteGateway {
    private val base = MaintenanceRules.allowedServerUrl(baseUrl)
        ?: throw IllegalArgumentException("A valid HTTPS server is required")

    /** Authentication/account check; a GET cannot prove the Manager role. */
    suspend fun verifySameAccount(editorKey: String): Boolean = withContext(Dispatchers.IO) {
        require(editorKey.isNotBlank() && managerKey.isNotBlank())
        // Each key is checked on its own, so a rejection names the key LubeLogger actually refused.
        val editor = whoamiOrExplain(editorKey,
            "LubeLogger rejected your Editor key, so the Manager key could not be checked. " +
                "Disconnect and connect again with a working Editor key.")
        val manager = whoamiOrExplain(managerKey, "LubeLogger rejected this Manager key. Check the key and try again.")
        val a = accountName(editor)
        val b = accountName(manager)
        if (a.isBlank() || b.isBlank()) throw ManagerException("LubeLogger did not identify both key owners.")
        if (!a.equals(b, ignoreCase = true)) throw ManagerException("Manager and Editor keys must belong to the same user.")
        true
    }

    /** The user name under any spelling LubeLogger uses; only text counts, so a number or object never matches. */
    private fun accountName(o: JSONObject): String = listOf("username", "userName", "Username", "UserName")
        .firstNotNullOfOrNull { (o.opt(it) as? String)?.trim()?.takeIf(String::isNotBlank) }.orEmpty()

    private fun whoami(key: String, reportStatus: Boolean = false): JSONObject =
        JSONObject(request("GET", "/api/whoami", key, reportStatus).toString(Charsets.UTF_8))

    /** [whoami], with an authentication rejection (401/403) reported as [rejected]; other failures as they are. */
    private fun whoamiOrExplain(key: String, rejected: String): JSONObject = try { whoami(key, reportStatus = true) }
        catch (e: ManagerException) {
            if (e.httpStatus == 401 || e.httpStatus == 403) throw ManagerException(rejected, e.httpStatus)
            throw e
        }

    data class DeleteSnapshot(
        val vehicle: Vehicle,
        val ref: RecordRef,
        val signature: String
    ) {
        val kind: RecordKind get() = ref.kind
    }

    /** Read-only preparation for the confirmation dialog: nothing destructive occurs here. */
    override suspend fun prepare(vehicle: Vehicle, displayed: ServiceRecord): DeleteSnapshot = withContext(Dispatchers.IO) {
        require(vehicle.id.isValid && displayed.id.isValid && displayed.vehicleId == vehicle.id)
        verifyVehicleIdentity(vehicle)
        val current = recordById(displayed.ref)
            ?: throw ManagerException("Record no longer exists. Refresh History or Calendar.")
        if (!matchesDisplayed(current, displayed))
            throw ManagerException("Record changed since it was displayed. Refresh before deleting.")
        DeleteSnapshot(vehicle, displayed.ref, signature(current, displayed.kind))
    }

    /** Preflight failures are definite no-delete; any ambiguous DELETE/read-back is uncertain. */
    override suspend fun delete(snapshot: DeleteSnapshot): Unit = withContext(Dispatchers.IO) {
        require(snapshot.vehicle.id.isValid && snapshot.ref.id.isValid && snapshot.ref.vehicleId == snapshot.vehicle.id)
        // All network failures in preflight are definite NO-DELETE outcomes.
        val fresh = try {
            verifyVehicleIdentity(snapshot.vehicle)
            recordById(snapshot.ref)
                ?: throw ManagerException("Record already missing. Refresh History or Calendar.")
        } catch (e: ManagerException) { throw e
        } catch (e: UnreadableResponseException) { throw ManagerException("${e.message} No delete sent.")
        } catch (_: Exception) {
            throw ManagerException("Unable to verify record before deletion. No delete sent.")
        }
        if (signature(fresh, snapshot.kind) != snapshot.signature)
            throw ManagerException("Record changed since confirmation opened. Deletion cancelled; refresh first.")

        val path = "/api/vehicle/${EditorRules.resource(snapshot.kind)}/delete?id=${snapshot.ref.id}"
        try {
            val reply = try { request("DELETE", path, managerKey) }
            catch (e: ManagerException) {
                if (e.httpStatus != 400 && e.httpStatus != 404) throw e
                // A transport may have replayed DELETE after the first request
                // committed. LubeLogger then returns 400/404 for the now-missing
                // record. Do not resend it: verify its actual state by GET.
                val remaining = try { recordById(snapshot.ref) }
                    catch (_: Exception) {
                        throw LubeLoggerEditorApi.WriteUncertainException(
                            "Delete returned HTTP ${e.httpStatus}, but verification failed. Check LubeLogger.")
                    }
                if (remaining != null) throw ManagerException(
                    "LubeLogger did not delete the record. No change was made.")
                return@withContext // The record is confirmed absent.
            }
            // A response can be 204/empty or JSON; an explicit rejection is not an acknowledgement.
            if (reply.isNotEmpty()) {
                val parsed = runCatching { JSONObject(String(reply, Charsets.UTF_8)) }.getOrNull()
                if (parsed?.has("success") == true && !parsed.optBoolean("success")) {
                    // Never repeat DELETE. Check the record before classifying a rejected reply.
                    val stillThere = try { recordById(snapshot.ref) }
                        catch (_: Exception) {
                            throw LubeLoggerEditorApi.WriteUncertainException(
                                "Deletion not acknowledged and verification failed. Check LubeLogger.")
                        }
                    if (stillThere != null)
                        throw ManagerException("LubeLogger did not delete this record. No change was made.")
                }
            }
        } catch (e: ManagerException) { throw e } // Explicit HTTP 4xx: server rejected operation.
        catch (e: LubeLoggerEditorApi.WriteUncertainException) { throw e }
        catch (_: Exception) {
            throw LubeLoggerEditorApi.WriteUncertainException("Delete response lost. Check LubeLogger before any new write.")
        }
        // Never repeat DELETE. Confirm absence on a separate GET before declaring success.
        val after = try { recordById(snapshot.ref) }
            catch (_: Exception) {
                throw LubeLoggerEditorApi.WriteUncertainException("Delete may have succeeded but verification failed. Check LubeLogger.")
            }
        if (after != null) throw LubeLoggerEditorApi.WriteUncertainException(
            "Delete was acknowledged but record still appears in LubeLogger. Check the web interface.")
    }

    private fun verifyVehicleIdentity(vehicle: Vehicle) {
        val fields = ResponseFields("the vehicle list")
        val matches = fields.objects(JSONArray(request("GET", "/api/vehicles", managerKey).toString(Charsets.UTF_8)))
            .filter { fields.int(it, "id") == vehicle.id.value }
        if (matches.size != 1 || fields.int(matches.single(), "year") != vehicle.year ||
            fields.text(matches.single(), "make") != vehicle.make ||
            fields.text(matches.single(), "model") != vehicle.model)
            throw ManagerException("Vehicle identity differs or Manager key lacks access. No delete sent.")
    }

    private fun recordById(record: RecordRef): JSONObject? {
        val (vehicleId, kind, id) = record
        val path = "/api/vehicle/${EditorRules.resource(kind)}?vehicleId=$vehicleId&id=$id" +
            if (kind == RecordKind.FUEL) "&useMPG=true&useUKMPG=false" else ""
        // Only a well-formed list that lacks the record means it is gone; anything else fails (Delete then reports an
        // uncertain outcome and keeps the write lock).
        return ResponseFields("the ${kind.label} record check")
            .recordWithId(JSONArray(request("GET", path, managerKey).toString(Charsets.UTF_8)), vehicleId, id)
    }

    /** Compare the original displayed record to the server before showing the destructive confirmation. */
    private fun matchesDisplayed(raw: JSONObject, record: ServiceRecord): Boolean {
        fun equalDecimal(a: String, b: String): Boolean = runCatching {
            BigDecimal(a).compareTo(BigDecimal(b)) == 0
        }.getOrDefault(a == b)
        // Read as the History reader does: every compared field must be present, and JSON null text reads as "".
        // A missing field is an unreadable response, never a blank that happens to match.
        val fields = ResponseFields("the ${record.kind.label} record")
        val serverDate = MaintenanceRules.parseDate(fields.text(raw, "date")) ?: return false
        val displayedDate = MaintenanceRules.parseDate(record.date) ?: return false
        if (serverDate != displayedDate) return false
        if (fields.longOrNull(raw, "odometer") != record.odometer) return false
        if (!equalDecimal(fields.decimalText(raw, "cost"), record.cost)) return false
        if (fields.text(raw, "notes") != record.notes || fields.text(raw, "tags") != record.tags) return false
        if (record.kind != RecordKind.FUEL && fields.text(raw, "description") != record.description) return false
        if (record.kind == RecordKind.FUEL && record.fuelConsumed != null) {
            val consumed = fields.doubleOrNull(raw, "fuelConsumed") ?: return false
            if (!equalDecimal(consumed.toString(), record.fuelConsumed.toString())) return false
        }
        return true
    }

    private fun signature(raw: JSONObject, kind: RecordKind): String = LubeLoggerEditorApi.editableSignature(raw, kind)

    /** The HTTP status is attached to errors only for DELETE (delete reconciliation relies on it) and
     * when [reportStatus] asks for it (the key check, to tell which key was rejected). */
    private fun request(method: String, path: String, key: String, reportStatus: Boolean = false): ByteArray {
        require(key.isNotBlank())
        val allowedGet = path == "/api/whoami" || path == "/api/vehicles" ||
            path.matches(Regex("^/api/vehicle/(?:servicerecords|repairrecords|upgraderecords|gasrecords)\\?vehicleId=[1-9][0-9]*&id=[1-9][0-9]*(?:&useMPG=true&useUKMPG=false)?$"))
        val allowedDelete = path.matches(Regex("^/api/vehicle/(?:servicerecords|repairrecords|upgraderecords|gasrecords)/delete\\?id=[1-9][0-9]*$"))
        require((method == "GET" && allowedGet) || (method == "DELETE" && allowedDelete))
        val connection = URL(base + path).openConnection() as HttpsURLConnection
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = Timeouts.LUBELOGGER_CONNECT
            connection.readTimeout = Timeouts.LUBELOGGER_READ
            connection.setRequestProperty("x-api-key", key)
            connection.setRequestProperty("culture-invariant", "true")
            connection.setRequestProperty("Accept", "application/json")
            if (method == "DELETE") {
                // The HTTP stack must never send a DELETE twice. Without this, a DELETE on a reused
                // connection whose reply is lost is resent once by the platform (seen: 2 DELETEs at the server). A
                // streamed request is one the stack may not replay; its length is 0, so the request still has no body
                // (only "Content-Length: 0"), as the contract says.
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }
            val status = connection.responseCode
            if (status !in 200..299) {
                if (method == "DELETE" && (status >= 500 || status == 408 || status == 429))
                    throw LubeLoggerEditorApi.WriteUncertainException("Delete returned HTTP $status. Verify in LubeLogger before retrying.")
                throw ManagerException(httpFailureMessage(status, "Manager key"),
                    httpStatus = if (method == "DELETE" || reportStatus) status else null)
            }
            if (method == "GET" && !connection.contentType.orEmpty().lowercase().contains("json"))
                throw ManagerException("$NOT_LUBELOGGER_MESSAGE No delete was sent.")
            val stream = if (status == 204) null else connection.inputStream
            val result = ByteArrayOutputStream()
            stream?.use { source ->
                val buffer = ByteArray(4096)
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    if (result.size() + count > 512_000)
                        if (method == "DELETE") throw LubeLoggerEditorApi.WriteUncertainException("Delete response too large; verify in LubeLogger.")
                        else throw ManagerException("Response too large; no delete sent.")
                    result.write(buffer, 0, count)
                }
            }
            return result.toByteArray()
        } finally { connection.disconnect() }
    }

    class ManagerException(message: String, override val httpStatus: Int? = null):
        LubeLoggerEditorApi.EditorApiException(message, httpStatus)
}
