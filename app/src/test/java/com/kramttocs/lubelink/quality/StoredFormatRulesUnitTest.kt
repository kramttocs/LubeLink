package com.kramttocs.lubelink.quality

import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.StoredFormatAction
import com.kramttocs.lubelink.core.StoredFormatRules
import org.junit.Assert.assertEquals
import org.junit.Test

/** What opening a stored preferences file does with the format version found in it. */
class StoredFormatRulesUnitTest {
    @Test fun currentVersionIsKeptAndAnUnversionedFileIsStamped() {
        for (format in StoredFormat.entries) {
            assertEquals(StoredFormatAction.KEEP, StoredFormatRules.action(format.version, format))
            assertEquals(StoredFormatAction.STAMP, StoredFormatRules.action(null, format))
        }
    }

    @Test fun aNewerOrInvalidVersionIsClearedNeverRead() {
        for (format in StoredFormat.entries) {
            assertEquals(StoredFormatAction.CLEAR, StoredFormatRules.action(format.version + 1, format))
            assertEquals(StoredFormatAction.CLEAR, StoredFormatRules.action(0, format))
            assertEquals(StoredFormatAction.CLEAR, StoredFormatRules.action(-1, format))
        }
    }

    @Test fun everyFileHasItsOwnName() {
        assertEquals(StoredFormat.entries.size, StoredFormat.entries.map { it.fileName }.toSet().size)
    }

    @Test fun aWriteLockFileWithDataKeepsItsLockWhenItCannotBeRead() {
        val lock = StoredFormat.UNCONFIRMED_WRITE
        assertEquals(StoredFormatAction.CLEAR_KEEP_LOCK, StoredFormatRules.action(lock.version + 1, lock, hasData = true))
        assertEquals(StoredFormatAction.CLEAR_KEEP_LOCK, StoredFormatRules.action(0, lock, hasData = true))
        assertEquals(StoredFormatAction.CLEAR, StoredFormatRules.action(lock.version + 1, lock, hasData = false))
        for (format in StoredFormat.entries.filter { !it.holdsWriteLock })
            assertEquals(StoredFormatAction.CLEAR, StoredFormatRules.action(format.version + 1, format, hasData = true))
    }

    @Test fun aStoredVersionIsReadWithItsType() {
        assertEquals(null, StoredFormatRules.storedVersion(null, present = false))
        assertEquals(1, StoredFormatRules.storedVersion(1, present = true))
        for (wrong in listOf<Any?>("1", true, 1L, 1.0f, null))
            assertEquals(StoredFormatRules.INVALID, StoredFormatRules.storedVersion(wrong, present = true))
    }

    /** OD-63: settings is format 2. Version 1, and a file with data from before versions were recorded, migrate;
     * an empty file is stamped. */
    @Test fun anOlderSettingsFileIsMigratedFromVersion1() {
        val settings = StoredFormat.SETTINGS
        assertEquals(2, settings.version)
        assertEquals(StoredFormatAction.MIGRATE, StoredFormatRules.action(1, settings, hasData = true))
        assertEquals(StoredFormatAction.MIGRATE, StoredFormatRules.action(null, settings, hasData = true))
        assertEquals(StoredFormatAction.STAMP, StoredFormatRules.action(null, settings, hasData = false))
        assertEquals("A version-1 format with no version yet is simply stamped", StoredFormatAction.STAMP,
            StoredFormatRules.action(null, StoredFormat.REMINDER_ALERTS, hasData = true))
    }
}
