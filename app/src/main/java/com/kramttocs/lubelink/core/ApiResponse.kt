package com.kramttocs.lubelink.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * A LubeLogger response that lacks a field LubeLink relies on, or holds it in a form LubeLink cannot read.
 * LubeLink shows this instead of guessing: a missing value is never shown as 0, blank or "no"
 * (docs/API-CONTRACT.md lists the fields each call must carry).
 */
class UnreadableResponseException(val source: String, val field: String) : Exception(
    "LubeLink could not read $source from LubeLogger (\"$field\" is missing or not valid). " +
        "This version of LubeLink is tested with LubeLogger $TESTED_LUBELOGGER_VERSION.")

/** The LubeLogger releases the API contract was captured from (tests/contract). */
const val TESTED_LUBELOGGER_VERSION = "1.7.3 and 1.7.4"

/**
 * Reads the fields of one kind of LubeLogger response. Every reader fails with
 * [UnreadableResponseException] when the field is absent or of the wrong type. JSON null is
 * accepted only where the contract says LubeLogger sends it, through the "...OrNull" and [text]
 * readers.
 */
internal class ResponseFields(private val source: String) {
    fun fail(field: String): Nothing = throw UnreadableResponseException(source, field)
    /** A list that names the same item twice is unreadable (LL-05): counting or showing it twice would be wrong. */
    fun <T> unique(items: List<T>, field: String = "id", key: (T) -> Any): List<T> =
        if (items.map(key).toSet().size == items.size) items else fail(field)

    /** Every entry of a list response; an entry that is not an object fails the whole response. */
    fun objects(array: JSONArray): List<JSONObject> =
        (0 until array.length()).map { array.optJSONObject(it) ?: fail("list entry ${it + 1}") }

    /**
     * Record [id] from a record list for [vehicleId], or null when the list, read in full, does not hold it. Every
     * entry must be an object with this vehicle's ID and a numeric record ID, so a malformed or foreign answer fails
     * instead of being taken to mean the record is gone. Two entries with this ID also fail.
     */
    fun recordWithId(array: JSONArray, vehicleId: VehicleId, id: RecordId): JSONObject? {
        val matches = objects(array).filter { entry ->
            if (int(entry, "vehicleId") != vehicleId.value) fail("vehicleId")
            int(entry, "id") == id.value
        }
        if (matches.size > 1) fail("id (more than one record has it)")
        return matches.singleOrNull()
    }

    fun obj(o: JSONObject, key: String): JSONObject = o.optJSONObject(key) ?: fail(key)
    fun array(o: JSONObject, key: String): JSONArray = o.optJSONArray(key) ?: fail(key)

    fun int(o: JSONObject, key: String): Int = intOrNull(o, key) ?: fail(key)
    fun intOrNull(o: JSONObject, key: String): Int? = numberOrNull(o, key)?.let { it.toIntOrNull() ?: fail(key) }
    fun longOrNull(o: JSONObject, key: String): Long? = numberOrNull(o, key)?.let { it.toLongOrNull() ?: fail(key) }
    fun doubleOrNull(o: JSONObject, key: String): Double? =
        numberOrNull(o, key)?.let { it.toDoubleOrNull()?.takeIf(Double::isFinite) ?: fail(key) }

    /** A decimal kept as the text LubeLogger sent (for example a cost), so it is shown unrounded. */
    fun decimalText(o: JSONObject, key: String): String =
        numberOrNull(o, key)?.also { if (it.toBigDecimalOrNull() == null) fail(key) } ?: fail(key)

    /** Text that must be present. LubeLogger sends JSON null for an empty optional text, read as "". */
    fun text(o: JSONObject, key: String): String = when {
        !o.has(key) -> fail(key)
        o.isNull(key) -> ""
        else -> (o.opt(key) as? String) ?: fail(key)
    }

    /** An account's user name: text that is not blank. Unlike [text], JSON null or blank is not accepted, since
     * an account check that names nobody has not identified anyone. */
    fun identity(o: JSONObject, key: String): String =
        ((if (o.isNull(key)) null else o.opt(key)) as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: fail(key)

    fun boolean(o: JSONObject, key: String): Boolean = when (val value = if (o.isNull(key)) null else o.opt(key)) {
        is Boolean -> value
        is String -> when {
            value.equals("true", ignoreCase = true) -> true
            value.equals("false", ignoreCase = true) -> false
            else -> fail(key)
        }
        else -> fail(key)
    }

    /** A number that must be present, and may be JSON null where the contract allows it. LubeLogger sends
     * numbers; digits sent as a string are read the same way. */
    private fun numberOrNull(o: JSONObject, key: String): String? = when {
        !o.has(key) -> fail(key)
        o.isNull(key) -> null
        else -> when (val value = o.opt(key)) {
            is Number -> value.toString()
            is String -> value.trim().ifEmpty { fail(key) }
            else -> fail(key)
        }
    }
}

/** One plain-language message for each kind of HTTP refusal: a key LubeLogger did not accept, a key without
 * permission, a request the server does not know, and a fault on the server. [key] names the key used. */
internal fun httpFailureMessage(status: Int, key: String): String = when (status) {
    401 -> "LubeLogger did not accept the $key (HTTP 401). Check the key in LubeLogger, or disconnect and connect again."
    403 -> "The $key does not have permission for this in LubeLogger (HTTP 403)."
    404 -> "LubeLogger did not recognise this request (HTTP 404). Check the server address. " +
        "This version of LubeLink is tested with LubeLogger $TESTED_LUBELOGGER_VERSION."
    in 500..599 -> "LubeLogger had a problem on the server (HTTP $status). Try again later."
    else -> "LubeLogger refused the request (HTTP $status)."
}

/** The server answered with something other than JSON: usually a wrong address, or a sign-in page in front of
 * LubeLogger. */
internal const val NOT_LUBELOGGER_MESSAGE = "The server did not answer the way LubeLogger does. Check the server " +
    "address, and that no sign-in page sits in front of LubeLogger."

/**
 * Every external call's timeouts, in milliseconds, in one place (every external call has one). LubeLogger:
 * docs/API-CONTRACT.md; station providers: Overpass answers slower than Geoapify.
 */
internal object Timeouts {
    const val LUBELOGGER_CONNECT = 10_000
    const val LUBELOGGER_READ = 20_000
    const val ATTACHMENT_READ = 30_000
    const val PROVIDER_CONNECT = 7_000
    const val OVERPASS_READ = 17_000
    const val GEOAPIFY_READ = 12_000
}
