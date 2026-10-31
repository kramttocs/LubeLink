package com.kramttocs.lubelink.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Every preferences file LubeLink stores, with its format version. A file is opened only through [StoredFormats.prefs],
 * which reads the version first, so an older or newer layout is never read as if it were the current one.
 *
 * Any change to a file's format raises its [version]: a new key, a key stored or meant differently,
 * a key removed. The same change adds the step from the previous version to [StoredFormats.migrate], unless the file
 * is [disposable] (then it is cleared and rebuilt). A file written before versions were recorded holds the version-1
 * layout and is migrated from 1. A file from a newer LubeLink (a downgrade) is cleared, never guessed at.
 */
enum class StoredFormat(val fileName: String, val version: Int, val disposable: Boolean,
                        val holdsWriteLock: Boolean = false) {
    /** Server address, encrypted keys, credential epoch, layout, corner and start-up choices, reminder suggestions,
     * nicknames, hidden vehicles. Version 2 added the corner style and the suggestions switch (OD-63). */
    SETTINGS("lubelink_settings", 2, disposable = false),
    /** Reminder alert switches and threshold per account, alert ranks already shown, last check status. */
    REMINDER_ALERTS("lubelink_reminder_alerts", 1, disposable = false),
    /** Favourite fuel stations per account. */
    FUEL_FAVORITES("lubelink_fuel_favorites_v2", 1, disposable = false),
    /** Favourite record descriptions per server and record kind. */
    DESCRIPTION_FAVORITES("lubelink_service_descriptions", 1, disposable = false),
    /** Whether the station picker last showed the map or the list. */
    STATION_VIEW("lubelink_station_view", 1, disposable = true),
    /** The write that may or may not have reached LubeLogger, which blocks new writes until checked. */
    UNCONFIRMED_WRITE("lubelink_unconfirmed_writes", 1, disposable = false, holdsWriteLock = true);
}

/** What opening a file does, from the version found in it. */
enum class StoredFormatAction { KEEP, STAMP, MIGRATE, CLEAR, CLEAR_KEEP_LOCK }

/** The keys of the unconfirmed-write marker ([StoredFormat.UNCONFIRMED_WRITE]), read and written by WriteSafety. */
object WriteMarkerKeys {
    const val ACTIVE = "pending"
    const val INTENT = "pending_intent"
    /** Shown when a marker this build cannot read had to be replaced: the lock stays until the user has checked. */
    const val UNREADABLE_DESCRIPTION = "A record change saved by a different LubeLink version was not confirmed"
    /** Shown when the marker's value cannot be read in this build's format (a damaged file): the lock stays too. */
    const val DAMAGED_DESCRIPTION = "A record change whose details LubeLink could not read was not confirmed"
}

object StoredFormatRules {
    /**
     * [found] is null when the file has no version yet: an empty file is stamped, and one with data holds the
     * version-1 layout, so it is migrated from 1 when the format has moved on. A version newer than this build knows
     * (a downgraded app) cannot be read safely: the file is cleared, never guessed at. An older version is migrated,
     * or cleared when the file is disposable.
     */
    fun action(found: Int?, format: StoredFormat, hasData: Boolean = false): StoredFormatAction = when {
        found == null -> if (!hasData || format.version == 1) StoredFormatAction.STAMP
            else if (format.disposable) StoredFormatAction.CLEAR else StoredFormatAction.MIGRATE
        found == format.version -> StoredFormatAction.KEEP
        found > format.version || found < 1 -> clear(format, hasData)
        format.disposable -> StoredFormatAction.CLEAR
        else -> StoredFormatAction.MIGRATE
    }

    /** A file that may hold a write lock is never simply emptied: an unreadable marker could be a write LubeLink
     * has not confirmed, so the lock is kept (as a generic marker) until the user has checked LubeLogger. */
    private fun clear(format: StoredFormat, hasData: Boolean) =
        if (format.holdsWriteLock && hasData) StoredFormatAction.CLEAR_KEEP_LOCK else StoredFormatAction.CLEAR

    /** The version stored in a file: null when there is none, [INVALID] when it is not a whole number. */
    fun storedVersion(value: Any?, present: Boolean): Int? = when {
        !present -> null
        value is Int -> value
        else -> INVALID
    }

    const val INVALID = 0
}

object StoredFormats {
    const val VERSION_KEY = "lubelink_format_version"
    private val checked = java.util.Collections.synchronizedSet(mutableSetOf<StoredFormat>())
    /** Formats whose stamp or clear reached memory but whose disk write failed. The in-memory version then
     * looks current, so it is not evidence of a finished check: the next open writes again. */
    private val notWritten = java.util.Collections.synchronizedSet(mutableSetOf<StoredFormat>())

    /** The preferences file for [format], after its version has been checked once in this process. */
    fun prefs(context: Context, format: StoredFormat): SharedPreferences {
        // Through the given context, so a test can hand in its own preferences (WriteSafetyMarkerFailureTest).
        val prefs = context.getSharedPreferences(format.fileName, Context.MODE_PRIVATE)
        if (format in checked) return prefs
        synchronized(checked) {
            if (format in notWritten) {
                // The earlier transition is in memory only: write it again. SharedPreferences writes its whole
                // in-memory state, so this persists the stamp or the clear that failed.
                if (prefs.edit().putInt(VERSION_KEY, format.version).commit()) {
                    notWritten.remove(format)
                    checked.add(format)
                }
            } else if (format !in checked) {
                val stored = prefs.all
                // Read with its type checked: a version that is not a whole number is invalid, not a crash.
                val found = StoredFormatRules.storedVersion(stored[VERSION_KEY], stored.containsKey(VERSION_KEY))
                val hasData = stored.keys.any { it != VERSION_KEY }
                val written = when (StoredFormatRules.action(found, format, hasData)) {
                    StoredFormatAction.KEEP -> true
                    StoredFormatAction.STAMP -> prefs.edit().putInt(VERSION_KEY, format.version).commit()
                    StoredFormatAction.CLEAR -> prefs.edit().clear().putInt(VERSION_KEY, format.version).commit()
                    StoredFormatAction.CLEAR_KEEP_LOCK -> prefs.edit().clear().putInt(VERSION_KEY, format.version)
                        .putString(WriteMarkerKeys.ACTIVE, WriteMarkerKeys.UNREADABLE_DESCRIPTION).commit()
                    StoredFormatAction.MIGRATE -> migrate(prefs, format, found ?: 1)
                }
                // A failed disk write is not a finished check: the next open checks the file again. This process
                // reads the file as changed in memory (SharedPreferences applies an edit before writing it).
                if (written) checked.add(format) else notWritten.add(format)
            }
        }
        return prefs
    }

    /**
     * Brings [format]'s file from version [from] to the current one, step by step, and records the new version in the
     * same write. Returns whether that write reached the disk. A version raised without its step here fails loudly
     * instead of misreading the file.
     */
    private fun migrate(prefs: SharedPreferences, format: StoredFormat, from: Int): Boolean {
        val edit = prefs.edit()
        for (version in from until format.version) when (format to version) {
            // 1 -> 2 (OD-63): `corner_style` and `suggest_reminders` were added; every version-1 key keeps its
            // encoding and meaning, and an absent new key reads as its default (Rounded; on).
            StoredFormat.SETTINGS to 1 -> Unit
            else -> error("No migration for ${format.fileName} from version $version to ${version + 1}")
        }
        return edit.putInt(VERSION_KEY, format.version).commit()
    }

    /** Whether [format] has been checked, and its result written, in this process. For tests. */
    internal fun isCheckedForTest(format: StoredFormat) = format in checked

    /** Lets a test open a file again as a fresh process would. */
    internal fun forgetCheckedForTest() { checked.clear(); notWritten.clear() }
}
