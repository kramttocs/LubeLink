package com.kramttocs.lubelink.quality

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordWriteIntent
import com.kramttocs.lubelink.core.WriteAction
import com.kramttocs.lubelink.ui.WriteSafety
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The marker's disk commit fails but, like Android's SharedPreferences, the
 * in-memory map keeps the write. Nothing may be sent, and the user must be able to unlock.
 */
@RunWith(AndroidJUnit4::class)
class WriteSafetyMarkerFailureTest {
    /** In-memory preferences whose commit() always reports a disk failure after applying the change. */
    private class FailingCommitPrefs : SharedPreferences {
        val data = HashMap<String, Any?>()
        override fun getAll(): Map<String, *> = HashMap(data)
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
                }
                return false // the disk write failed; memory already changed
            }
        }
    }

    @Test fun failedMarkerCommitSendsNothingAndCanBeUnlocked() {
        val prefs = FailingCommitPrefs()
        val context = object : ContextWrapper(ApplicationProvider.getApplicationContext<Context>()) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }
        val sent = AtomicInteger(0)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(WriteSafety.start(context, "Add synthetic record",
                RecordWriteIntent(WriteAction.ADD, RecordKind.SERVICE)) { sent.incrementAndGet() })
        }
        val deadline = System.currentTimeMillis() + 10_000
        while (WriteSafety.state is WriteSafety.State.Pending && System.currentTimeMillis() < deadline) Thread.sleep(25)
        assertEquals("No request may be sent without a durable marker", 0, sent.get())
        assertTrue("Expected Failed, got ${WriteSafety.state}", WriteSafety.state is WriteSafety.State.Failed)
        assertNull("The marker left in memory must be cleared", WriteSafety.unresolved(context))
        // The Failed dialog's OK button calls acknowledge(checkedServer = false); it must unlock.
        InstrumentationRegistry.getInstrumentation().runOnMainSync { WriteSafety.acknowledge(context, checkedServer = false) }
        assertTrue("OK must unlock, state is ${WriteSafety.state}", WriteSafety.state is WriteSafety.State.Idle)
    }
}
