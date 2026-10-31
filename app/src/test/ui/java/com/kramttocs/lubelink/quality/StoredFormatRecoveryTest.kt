package com.kramttocs.lubelink.quality

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.StoredFormats
import com.kramttocs.lubelink.core.WriteMarkerKeys
import com.kramttocs.lubelink.ui.WriteSafety
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Opening a stored file with a malformed or newer version, or when the disk write fails, with the real
 * initializer. Synthetic preferences only. Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class StoredFormatRecoveryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun file(format: StoredFormat) = context.getSharedPreferences(format.fileName, Context.MODE_PRIVATE)

    @Before fun fresh() {
        StoredFormats.forgetCheckedForTest()
        listOf(StoredFormat.STATION_VIEW, StoredFormat.UNCONFIRMED_WRITE).forEach { file(it).edit().clear().commit() }
    }

    @After fun cleanUp() = fresh()

    @Test fun aVersionThatIsNotANumberIsTreatedAsInvalidNotACrash() {
        file(StoredFormat.STATION_VIEW).edit().putString(StoredFormats.VERSION_KEY, "one").putString("view", "map").commit()
        val prefs = StoredFormats.prefs(context, StoredFormat.STATION_VIEW)
        assertEquals(StoredFormat.STATION_VIEW.version, prefs.getInt(StoredFormats.VERSION_KEY, -1))
        assertFalse("An unreadable file is cleared", prefs.contains("view"))
    }

    @Test fun anUnreadableWriteMarkerKeepsTheWriteLock() {
        file(StoredFormat.UNCONFIRMED_WRITE).edit().putInt(StoredFormats.VERSION_KEY, 99)
            .putString("written_by_a_later_version", "synthetic").commit()
        assertEquals("The lock stays until the user has checked LubeLogger",
            WriteMarkerKeys.UNREADABLE_DESCRIPTION, WriteSafety.unresolved(context))
        val prefs = file(StoredFormat.UNCONFIRMED_WRITE)
        assertEquals(StoredFormat.UNCONFIRMED_WRITE.version, prefs.getInt(StoredFormats.VERSION_KEY, -1))
        assertFalse(prefs.contains("written_by_a_later_version"))
    }

    @Test fun anEmptyNewerWriteMarkerFileIsClearedWithoutALock() {
        file(StoredFormat.UNCONFIRMED_WRITE).edit().putInt(StoredFormats.VERSION_KEY, 99).commit()
        assertEquals(null, WriteSafety.unresolved(context))
    }

    @Test fun aFailedDiskWriteIsNotRecordedAsAFinishedCheck() {
        val failing = FailingPrefs()
        val wrapped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = failing
        }
        StoredFormats.prefs(wrapped, StoredFormat.STATION_VIEW)
        assertFalse("The stamp was not written", StoredFormats.isCheckedForTest(StoredFormat.STATION_VIEW))
        assertEquals(1, failing.commits)
        // The version is now in memory, but nothing reached the disk. A second open with the disk still
        // failing must write again and must not record a finished check.
        StoredFormats.prefs(wrapped, StoredFormat.STATION_VIEW)
        assertFalse("Still not written", StoredFormats.isCheckedForTest(StoredFormat.STATION_VIEW))
        assertEquals("The second open writes again", 2, failing.commits)
        assertEquals("Nothing durable yet", null, failing.disk[StoredFormats.VERSION_KEY])
        failing.commitSucceeds = true
        StoredFormats.prefs(wrapped, StoredFormat.STATION_VIEW)
        assertTrue("A successful write records it", StoredFormats.isCheckedForTest(StoredFormat.STATION_VIEW))
        assertEquals(3, failing.commits)
        assertEquals("The stamp is durable", StoredFormat.STATION_VIEW.version, failing.disk[StoredFormats.VERSION_KEY])
        StoredFormats.prefs(wrapped, StoredFormat.STATION_VIEW)
        assertEquals("A checked format writes nothing more", 3, failing.commits)
    }

    /** In-memory preferences whose commit() applies the change in memory and reports a disk failure, as Android's
     * SharedPreferences does when the disk write fails. */
    private class FailingPrefs : SharedPreferences {
        @Volatile var commitSucceeds = false
        @Volatile var commits = 0
        val data = HashMap<String, Any?>()
        /** What a successful commit wrote to "disk": the whole in-memory map, as Android does. */
        val disk = HashMap<String, Any?>()
        override fun getAll(): Map<String, *> = synchronized(data) { HashMap(data) }
        override fun getString(key: String, defValue: String?) = data[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?) = data[key] as Set<String>? ?: defValues
        override fun getInt(key: String, defValue: Int) = data[key] as Int? ?: defValue
        override fun getLong(key: String, defValue: Long) = data[key] as Long? ?: defValue
        override fun getFloat(key: String, defValue: Float) = data[key] as Float? ?: defValue
        override fun getBoolean(key: String, defValue: Boolean) = data[key] as Boolean? ?: defValue
        override fun contains(key: String) = data.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {}
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val puts = HashMap<String, Any?>(); val removes = HashSet<String>(); var clear = false
            override fun putString(k: String, v: String?) = apply { puts[k] = v }
            override fun putStringSet(k: String, v: Set<String>?) = apply { puts[k] = v }
            override fun putInt(k: String, v: Int) = apply { puts[k] = v }
            override fun putLong(k: String, v: Long) = apply { puts[k] = v }
            override fun putFloat(k: String, v: Float) = apply { puts[k] = v }
            override fun putBoolean(k: String, v: Boolean) = apply { puts[k] = v }
            override fun remove(k: String) = apply { removes += k }
            override fun clear() = apply { clear = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                synchronized(data) {
                    if (clear) data.clear()
                    removes.forEach { data.remove(it) }
                    data.putAll(puts)
                    commits++
                    if (commitSucceeds) { disk.clear(); disk.putAll(data) }
                }
                return commitSucceeds
            }
        }
    }
}
