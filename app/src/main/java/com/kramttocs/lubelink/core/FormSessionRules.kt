package com.kramttocs.lubelink.core

/** Stale form callbacks must not act on a subsequently opened form. */
internal object FormSessionRules {
    fun accepts(activeSession: Int, callbackSession: Int, editorOpen: Boolean): Boolean =
        editorOpen && activeSession == callbackSession
}
