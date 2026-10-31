package com.kramttocs.lubelink.core

/**
 * The corner style chosen in Settings → Garage & personalization (OD-53): Rounded (the default) or Square. Only the
 * corners of buttons, fields, chips, cards and dialogs differ; colours, sizes, layout and wording are the same. The
 * choice belongs to the phone.
 */
enum class CornerStyle(val code: String) {
    ROUNDED("rounded"),
    SQUARE("square");

    companion object {
        /** A missing or unknown saved value is Rounded, the default. */
        fun of(code: String?): CornerStyle = entries.firstOrNull { it.code == code } ?: ROUNDED
    }
}
