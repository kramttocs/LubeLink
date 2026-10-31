package com.kramttocs.lubelink.quality

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.StationDataException
import com.kramttocs.lubelink.core.StationResponses
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Geoapify and Overpass readers against synthetic captures shaped like each provider's answers
 * (test resources `providers/`), and changed copies of them. A field that is not supplied falls back; one that is
 * supplied wrongly, or bad coordinates, leaves that station out and counts it; nothing readable is an error, never
 * "no stations". Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class StationResponsesTest {
    private fun capture(name: String) = checkNotNull(javaClass.classLoader!!.getResourceAsStream("providers/$name"))
        .bufferedReader().use { it.readText() }

    private fun places(change: (JSONArray) -> Unit) =
        JSONObject(capture("geoapify-places.json")).also { change(it.getJSONArray("features")) }.toString()
    private fun elements(change: (JSONArray) -> Unit) =
        JSONObject(capture("overpass.json")).also { change(it.getJSONArray("elements")) }.toString()

    private fun unreadable(what: String, read: () -> Any) {
        try { read(); fail("$what was read") } catch (_: StationDataException) {}
    }

    @Test fun capturesReadWithTheirFallbacks() {
        val geo = StationResponses.geoapify(capture("geoapify-places.json"))
        assertEquals(0, geo.skipped)
        assertEquals(listOf("Synthetic Fuel Stop", "Synthetic Operator", "Gas station"), geo.stations.map { it.name })
        assertEquals("10 Test Way, Testville, TS 00001", geo.stations[0].address)
        assertEquals(listOf("node/1000001", "way/1000002", "relation/1000003"), geo.stations.map { it.osmId })
        val osm = StationResponses.overpass(capture("overpass.json"))
        assertEquals(0, osm.skipped)
        assertEquals(listOf("Synthetic Gas", "Synthetic Brand", "Gas station"), osm.stations.map { it.name })
        assertEquals("5 Example Street, Testville, TS, 00002", osm.stations[0].address)
        assertEquals(40.013, osm.stations[1].latitude!!, 0.0)
        assertEquals("12 Test Way, Testville, TS 00001",
            StationResponses.suggestedAddress(capture("geoapify-reverse.json")))
    }

    @Test fun aStationWithBadDataIsLeftOutAndCounted() {
        val geoCases = listOf<Pair<String, (JSONArray) -> Unit>>(
            "a feature that is not an object" to { it.put(7) },
            "a name that is a number" to { it.getJSONObject(0).getJSONObject("properties").put("name", 42) },
            "a street that is an object" to { it.getJSONObject(0).getJSONObject("properties").put("street", JSONObject()) },
            "a latitude out of range" to { it.getJSONObject(0).getJSONObject("properties").put("lat", 123.0) },
            "a latitude that is text" to { it.getJSONObject(0).getJSONObject("properties").put("lat", "40.0") },
            "no coordinates at all" to {
                it.getJSONObject(0).getJSONObject("properties").apply { remove("lat"); remove("lon") }
                it.getJSONObject(0).remove("geometry")
            },
            "properties that are text" to { it.getJSONObject(0).put("properties", "x") })
        for ((label, change) in geoCases) {
            val parsed = StationResponses.geoapify(places(change))
            assertEquals("Geoapify, $label", 1, parsed.skipped)
            assertTrue("Geoapify, $label: the others are kept", parsed.stations.size >= 2)
        }
        val osmCases = listOf<Pair<String, (JSONArray) -> Unit>>(
            "an element that is not an object" to { it.put("x") },
            "a longitude out of range" to { it.getJSONObject(0).put("lon", -200.0) },
            "a centre latitude out of range" to { it.getJSONObject(1).getJSONObject("center").put("lat", -91.0) },
            "tags that are a list" to { it.getJSONObject(0).put("tags", JSONArray()) },
            "a name that is a number" to { it.getJSONObject(0).getJSONObject("tags").put("name", 5) })
        for ((label, change) in osmCases) {
            val parsed = StationResponses.overpass(elements(change))
            assertEquals("Overpass, $label", 1, parsed.skipped)
            assertTrue("Overpass, $label: the others are kept", parsed.stations.size >= 2)
        }
        assertEquals("1 station was left out: its map data could not be read.", StationResponses.skippedNotice(1))
    }

    @Test fun nothingReadableIsAnErrorNotNoStations() {
        unreadable("Geoapify with every feature unreadable") {
            StationResponses.geoapify(places { features -> for (i in 0 until features.length()) features.put(i, 7) })
        }
        unreadable("Overpass with every element out of range") {
            StationResponses.overpass(elements { all -> for (i in 0 until all.length()) all.getJSONObject(i).put("lat", 95.0).put("center", JSONObject().put("lat", 95.0).put("lon", 0.0)) })
        }
        unreadable("Geoapify without features") { StationResponses.geoapify("{\"type\":\"FeatureCollection\"}") }
        unreadable("Overpass that is not JSON") { StationResponses.overpass("<html>busy</html>") }
        // A well-formed empty answer is a real "no stations".
        assertEquals(0, StationResponses.geoapify("{\"features\":[]}").stations.size)
        assertEquals(0, StationResponses.overpass("{\"elements\":[]}").stations.size)
    }

    @Test fun anAddressSuggestionInTheWrongFormIsAnErrorNotAGuess() {
        fun reverse(change: (JSONObject) -> Unit) = JSONObject(capture("geoapify-reverse.json"))
            .also { change(it.getJSONArray("results").getJSONObject(0)) }.toString()
        unreadable("a street that is a number") { StationResponses.suggestedAddress(reverse { it.put("street", 12) }) }
        unreadable("a city that is an object") { StationResponses.suggestedAddress(reverse { it.put("city", JSONObject()) }) }
        unreadable("a result that is text") {
            StationResponses.suggestedAddress(JSONObject().put("results", JSONArray().put("x")).toString())
        }
        try {
            StationResponses.suggestedAddress(reverse { it.remove("street") })
            fail("An address without a street was suggested")
        } catch (e: IllegalStateException) {
            assertTrue(e !is StationDataException && e.message!!.startsWith("No street-level address"))
        }
    }
}
