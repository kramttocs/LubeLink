package com.kramttocs.lubelink.core

import androidx.core.content.edit
import android.content.Context
import java.util.Locale

/** Device-local favorites scoped to a LubeLogger server and record kind. Never transmitted as configuration. */
class ServiceDescriptionStore(context: Context, private val server: String) {
    private val preferences = StoredFormats.prefs(context, StoredFormat.DESCRIPTION_FAVORITES)

    fun favorites(kind: RecordKind): Set<String> {
        if (kind == RecordKind.FUEL) return emptySet()
        // A value of the wrong type under the key (a damaged file) reads as no favorites, never stopping the form.
        return runCatching { preferences.getStringSet(ServiceDescriptionRules.preferenceKey(server, kind), emptySet()) }
            .getOrNull().orEmpty().asSequence().map(ServiceDescriptionRules::normalize)
            .filter { it.isNotBlank() }.take(ServiceDescriptionRules.MAX_FAVORITES).toSet()
    }

    fun isFavorite(kind: RecordKind, description: String): Boolean {
        val target = ServiceDescriptionRules.normalize(description).lowercase(Locale.ROOT)
        return target.isNotBlank() && favorites(kind).any { it.lowercase(Locale.ROOT) == target }
    }

    fun setFavorite(kind: RecordKind, description: String, favorite: Boolean) {
        if (kind == RecordKind.FUEL) return
        val target = ServiceDescriptionRules.normalize(description)
        if (target.isBlank()) return
        val previous = favorites(kind)
        val updated = previous.filterNot { it.equals(target, ignoreCase = true) }.toMutableSet()
        if (favorite && updated.size < ServiceDescriptionRules.MAX_FAVORITES) updated.add(target)
        preferences.edit { putStringSet(ServiceDescriptionRules.preferenceKey(server, kind), updated.toSet()) }
    }
}
