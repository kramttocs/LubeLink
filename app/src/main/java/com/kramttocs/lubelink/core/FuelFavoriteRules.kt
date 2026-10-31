package com.kramttocs.lubelink.core

/** Addresses are optional because many OpenStreetMap stations have no street-address tags. */
data class FuelStation(
    val name: String,
    val address: String = "",
    val distanceMeters: Int? = null,
    /** A public OpenStreetMap object identifier, not a precise device location. */
    val osmId: String? = null,
    /** Local-only favorite identity, stable when the user renames the station. */
    val favoriteId: String? = null,
    /** Ephemeral compass hint to tell adjacent unnamed stations apart. */
    val direction: String? = null,
    /** Geographic position belongs to a selected OSM place, never the vehicle. */
    val latitude: Double? = null,
    val longitude: Double? = null
) {
    fun normalized(): FuelStation = copy(name = name.trim().take(100),
        address = address.trim().take(240))
}

/** Pure identity rules that do not modify the server or any historical fuel records. */
object FuelFavoriteRules {
    /** Pure lookup reused by the picker and persistence layer, without disk reads per row. */
    fun findFavorite(saved: List<FuelStation>, candidate: FuelStation): FuelStation? =
        saved.firstOrNull {
            (candidate.favoriteId != null && it.favoriteId == candidate.favoriteId) || samePlace(it, candidate)
        }

    /** The map and list share a query, including locally renamed favorites. */
    fun matchesSearch(station: FuelStation, favorite: FuelStation?, query: String): Boolean {
        if (query.isBlank()) return true
        return listOfNotNull(station.name, station.address, favorite?.name, favorite?.address)
            .any { it.contains(query.trim(), ignoreCase = true) }
    }

    /** Never permit saving a selection that has disappeared under a search filter. */
    fun visibleSelection(selection: FuelStation?, filtered: List<FuelStation>): FuelStation? =
        selection?.takeIf { it in filtered }

    fun samePlace(saved: FuelStation, candidate: FuelStation): Boolean {
        if (!saved.osmId.isNullOrBlank() && !candidate.osmId.isNullOrBlank())
            return saved.osmId == candidate.osmId
        if (!saved.osmId.isNullOrBlank() || !candidate.osmId.isNullOrBlank()) return false
        return saved.name.equals(candidate.name, ignoreCase = true) &&
            saved.address.equals(candidate.address, ignoreCase = true)
    }
}

