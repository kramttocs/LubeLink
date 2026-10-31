package com.kramttocs.lubelink.core

/** Stable machine identity of a write; labels are only presentation. */
internal enum class WriteAction { ADD, EDIT, DELETE }

internal data class RecordWriteIntent(val action: WriteAction, val kind: RecordKind) {
    fun persistedCode(): String = "${action.name}:${kind.name}"
    companion object {
        fun fromPersistedCode(value: String?): RecordWriteIntent? {
            val parts = value?.split(':') ?: return null
            if (parts.size != 2) return null
            val action = WriteAction.entries.firstOrNull { it.name == parts[0] } ?: return null
            val kind = RecordKind.entries.firstOrNull { it.name == parts[1] } ?: return null
            return RecordWriteIntent(action, kind)
        }
    }
}

/** Short, non-sensitive feedback for a server-confirmed operation. */
internal object WriteSuccessNotice {
    fun message(intent: RecordWriteIntent): String =
        "${intent.kind.label} record " + when (intent.action) {
            WriteAction.ADD -> "added"
            WriteAction.EDIT -> "updated"
            WriteAction.DELETE -> "deleted"
        }
}
