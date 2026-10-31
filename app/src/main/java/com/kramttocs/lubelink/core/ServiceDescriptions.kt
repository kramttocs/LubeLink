package com.kramttocs.lubelink.core

import java.security.MessageDigest
import java.util.Locale

/** Only reusable text. LubeLogger continues to store each record's ordinary description. */
object ServiceDescriptionRules {
    const val MAX_LENGTH = 250
    const val MAX_FAVORITES = 60

    fun normalize(text: String): String = text.trim()
        .replace(Regex("\\s+"), " ")
        .filterNot { it.isISOControl() }
        .take(MAX_LENGTH).trim()

    fun preferenceKey(server: String, kind: RecordKind): String {
        require(kind != RecordKind.FUEL) { "Fuel records don't have service descriptions" }
        val normalizedServer = MaintenanceRules.allowedServerUrl(server)
            ?: throw IllegalArgumentException("Invalid server")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(normalizedServer.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "service_description_favorites_${hash}_${kind.name.lowercase(Locale.ROOT)}"
    }

    /** Favorites first, followed by popular descriptions from service/repair/upgrade records.
     *  Case- and whitespace-insensitive deduplication retains a useful display spelling.
     */
    fun suggestions(kind: RecordKind, records: List<ServiceRecord>, favorites: Collection<String>): List<String> {
        if (kind == RecordKind.FUEL) return emptyList()
        val counts = linkedMapOf<String, Pair<String, Int>>()
        records.asSequence().filter { it.kind == kind }.forEach { record ->
            val normalized = normalize(record.description)
            val key = normalized.lowercase(Locale.ROOT)
            if (key.isNotBlank()) {
                val previous = counts[key]
                counts[key] = (previous?.first ?: normalized) to ((previous?.second ?: 0) + 1)
            }
        }
        val fav = favorites.asSequence().map(::normalize).filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.ROOT) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it }).toList()
        val favoriteKeys = fav.mapTo(mutableSetOf()) { it.lowercase(Locale.ROOT) }
        val fromHistory = counts.entries.asSequence().filterNot { it.key in favoriteKeys }
            .sortedWith(compareByDescending<Map.Entry<String, Pair<String, Int>>> { it.value.second }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.value.first })
            .map { it.value.first }.toList()
        return fav + fromHistory
    }
}
