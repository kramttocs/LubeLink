package com.kramttocs.lubelink.ui

import androidx.compose.runtime.saveable.Saver

/**
 * Saved-state bound for the record form's text fields. Android keeps saved state in a
 * Binder transaction with a shared ~1 MB limit, so no field may save an unbounded string.
 *
 * Typing is limited to each field's Save limit (EditorRules), so typed text always fits and is saved
 * as typed. Only text prefilled from LubeLogger (an Edit, or a Copy) can be longer. Such text is saved
 * as a marker instead: [SAME_AS_SOURCE] when it still equals the server or copied value, so restoring
 * reads it again from there; [NOT_KEPT] when it had been edited, so restoring says the edit was lost.
 * Markers contain a NUL character, which cannot be typed, and are never shown or sent: an Edit hides
 * its fields until the server record has loaded and [resolveSavedText] has replaced them.
 */
internal object FormTextSaver {
    const val SAME_AS_SOURCE = "\u0000lubelink:same-as-source"
    const val NOT_KEPT = "\u0000lubelink:not-kept"

    /** [source] is the server (Edit) or copied (Copy) value of this field, if the form has one. */
    fun bounded(limit: Int, source: () -> String?): Saver<String, String> = Saver(
        save = { value ->
            when {
                value.length <= limit -> value
                value == source() -> SAME_AS_SOURCE
                else -> NOT_KEPT
            }
        },
        restore = { it }
    )

    data class Resolved(val text: String, val lost: Boolean)

    /** The text for a restored [value], or null when it was saved as typed. */
    fun resolveSavedText(value: String, source: String?): Resolved? = when (value) {
        SAME_AS_SOURCE -> if (source != null) Resolved(source, lost = false) else Resolved("", lost = true)
        NOT_KEPT -> Resolved(source.orEmpty(), lost = true)
        else -> null
    }
}
