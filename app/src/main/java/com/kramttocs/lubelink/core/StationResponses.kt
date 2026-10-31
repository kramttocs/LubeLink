package com.kramttocs.lubelink.core

import org.json.JSONArray
import org.json.JSONObject

/** A station provider's answer that LubeLink could not read at all: shown as an error, never as "no stations". */
class StationDataException(message: String) : IllegalStateException(message)

/**
 * Reads Geoapify and Overpass answers (docs/API-CONTRACT.md). A field that is not supplied is optional and falls
 * back (no name: the brand, the operator, then "Gas station"; no street: no address). A field that is supplied in
 * the wrong form, or coordinates that are missing or out of range, make that one station unreadable: it is left out
 * and counted, so the screen can say so. When no station at all can be read, the answer is an error.
 */
internal object StationResponses {
    data class Parsed(val stations: List<FuelStation>, val skipped: Int)

    private class Unreadable : Exception()

    /** Optional text: absent or JSON null is "", text is itself; anything else is unreadable. */
    private fun JSONObject.optionalText(key: String): String = when {
        !has(key) || isNull(key) -> ""
        else -> (opt(key) as? String) ?: throw Unreadable()
    }

    /** An optional object: absent or JSON null is null; anything but an object is unreadable. */
    private fun JSONObject.optionalObject(key: String): JSONObject? = when {
        !has(key) || isNull(key) -> null
        else -> opt(key) as? JSONObject ?: throw Unreadable()
    }

    private fun coordinate(value: Any?, range: ClosedFloatingPointRange<Double>): Double =
        ((value as? Number)?.toDouble() ?: throw Unreadable()).takeIf { it.isFinite() && it in range }
            ?: throw Unreadable()

    private fun latitude(value: Any?) = coordinate(value, -90.0..90.0)
    private fun longitude(value: Any?) = coordinate(value, -180.0..180.0)

    private fun osmId(type: String, id: Any?): String? {
        val number = (id as? Number)?.toLong()?.toString() ?: (id as? String) ?: return null
        if (!number.matches(Regex("[0-9]{1,19}"))) return null
        return when (type.lowercase(java.util.Locale.US)) {
            "n", "node" -> "node/$number"
            "w", "way" -> "way/$number"
            "r", "relation" -> "relation/$number"
            else -> null
        }
    }

    private fun entries(array: JSONArray, read: (JSONObject) -> FuelStation, provider: String): Parsed {
        var skipped = 0
        val stations = (0 until array.length()).mapNotNull { index ->
            try {
                read(array.opt(index) as? JSONObject ?: throw Unreadable())
            } catch (_: Unreadable) {
                skipped++
                null
            }
        }
        if (stations.isEmpty() && skipped > 0)
            throw StationDataException("$provider sent station data LubeLink could not read.")
        return Parsed(stations, skipped)
    }

    private fun root(text: String, key: String, provider: String): JSONArray =
        runCatching { JSONObject(text).getJSONArray(key) }.getOrNull()
            ?: throw StationDataException("$provider sent an answer LubeLink could not read.")

    /** A Geoapify Places answer whose "features" list can be read, whatever it holds (the key test). */
    fun checkGeoapify(text: String) { root(text, "features", "Geoapify") }

    /** Geoapify Places (`/v2/places`): a FeatureCollection with "features". */
    fun geoapify(text: String): Parsed = entries(root(text, "features", "Geoapify"), { feature ->
        val properties = feature.optionalObject("properties") ?: throw Unreadable()
        val coordinates = feature.optionalObject("geometry")?.let { geometry ->
            if (!geometry.has("coordinates") || geometry.isNull("coordinates")) null
            else geometry.opt("coordinates") as? JSONArray ?: throw Unreadable()
        }
        val lat = latitude(if (properties.has("lat")) properties.opt("lat") else coordinates?.opt(1))
        val lon = longitude(if (properties.has("lon")) properties.opt("lon") else coordinates?.opt(0))
        val raw = properties.optionalObject("datasource")?.optionalObject("raw")
        val name = listOf(properties.optionalText("name"), raw?.optionalText("brand").orEmpty(),
            raw?.optionalText("operator").orEmpty()).firstOrNull { it.isNotBlank() } ?: "Gas station"
        val street = listOf(properties.optionalText("housenumber"), properties.optionalText("street"))
            .filter { it.isNotBlank() }.joinToString(" ")
        val cityStateZip = listOf(properties.optionalText("city"),
            listOf(properties.optionalText("state_code").ifBlank { properties.optionalText("state") },
                properties.optionalText("postcode")).filter { it.isNotBlank() }.joinToString(" "))
            .filter { it.isNotBlank() }.joinToString(", ")
        val address = listOf(street, cityStateZip).filter { it.isNotBlank() }.joinToString(", ")
            .ifBlank {
                properties.optionalText("formatted").removePrefix("$name, ")
                    .ifBlank { properties.optionalText("address_line2") }
            }
        FuelStation(name, address, osmId = raw?.let { osmId(it.optionalText("osm_type"), it.opt("osm_id")) },
            latitude = lat, longitude = lon).normalized()
    }, "Geoapify")

    /** Overpass (`out center`): "elements" of nodes, ways and relations with "tags". */
    fun overpass(text: String): Parsed = entries(root(text, "elements", "The map-data server"), { entry ->
        val tags = entry.optionalObject("tags") ?: throw Unreadable()
        val center = entry.optionalObject("center")
        val lat = latitude(if (entry.has("lat")) entry.opt("lat") else center?.opt("lat"))
        val lon = longitude(if (entry.has("lon")) entry.opt("lon") else center?.opt("lon"))
        val name = listOf("name", "brand", "operator").map { tags.optionalText(it) }
            .firstOrNull { it.isNotBlank() } ?: "Gas station"
        val street = listOf(tags.optionalText("addr:housenumber"), tags.optionalText("addr:street"))
            .filter { it.isNotBlank() }.joinToString(" ")
        val address = listOf(street, tags.optionalText("addr:city"), tags.optionalText("addr:state"),
            tags.optionalText("addr:postcode")).filter { it.isNotBlank() }.joinToString(", ")
        FuelStation(name, address, osmId = osmId(entry.optionalText("type"), entry.opt("id")),
            latitude = lat, longitude = lon).normalized()
    }, "The map-data server")

    /** Geoapify reverse geocoding (`format=json`): the first of "results", with a street, as one address line. */
    fun suggestedAddress(text: String): String {
        val results = root(text, "results", "Geoapify")
        if (results.length() == 0) throw IllegalStateException("No nearby address found. Enter it manually.")
        val unreadable = StationDataException("Geoapify sent an address LubeLink could not read.")
        val result = results.opt(0) as? JSONObject ?: throw unreadable
        return try {
            val streetName = result.optionalText("street")
            // Do not suggest a city centroid or a vague locality as a street address.
            if (streetName.isBlank()) throw IllegalStateException("No street-level address found. Enter it manually.")
            val street = listOf(result.optionalText("housenumber"), streetName).filter { it.isNotBlank() }.joinToString(" ")
            val city = result.optionalText("city").ifBlank { result.optionalText("county") }
            val region = listOf(result.optionalText("state_code").ifBlank { result.optionalText("state") },
                result.optionalText("postcode")).filter { it.isNotBlank() }.joinToString(" ")
            listOf(street, city, region).filter { it.isNotBlank() }.joinToString(", ").take(240)
        } catch (_: Unreadable) {
            throw unreadable
        }
    }

    /** Shown under the results when some stations were left out. */
    fun skippedNotice(skipped: Int): String? = when {
        skipped <= 0 -> null
        skipped == 1 -> "1 station was left out: its map data could not be read."
        else -> "$skipped stations were left out: their map data could not be read."
    }
}
