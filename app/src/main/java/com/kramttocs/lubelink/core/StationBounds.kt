package com.kramttocs.lubelink.core

import java.util.Locale

/** Actual visible map rectangle, not an approximate circle around the map center. */
data class StationBounds(val west: Double, val south: Double, val east: Double, val north: Double) {
    // Cap excessive map-wide searches; APIs should not unexpectedly scan an entire state.
    fun isSearchable(): Boolean = west.isFinite() && east.isFinite() && south.isFinite() && north.isFinite() &&
        west in -180.0..180.0 && east in -180.0..180.0 &&
        south in -90.0..90.0 && north in -90.0..90.0 &&
        east > west && north > south && east - west <= 0.5 && north - south <= 0.35

    fun geoapifyFilter(): String {
        require(isSearchable()) { "Zoom in to search this area." }
        return "rect:${f(west)},${f(south)},${f(east)},${f(north)}"
    }

    fun overpassBBox(): String {
        require(isSearchable()) { "Zoom in to search this area." }
        return "(${f(south)},${f(west)},${f(north)},${f(east)})"
    }

    private fun f(number: Double) = String.format(Locale.US, "%.6f", number)
}

object StationAddressPolicy {
    /** City-only or empty labels merit an optional, user-confirmed street-level suggestion. */
    fun shouldOffer(address: String): Boolean = address.isBlank() ||
        !Regex("\\b[0-9]{1,6}\\s+\\S+", RegexOption.IGNORE_CASE).containsMatchIn(address)
}
