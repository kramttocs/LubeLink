package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordWriteIntent
import com.kramttocs.lubelink.core.WriteAction
import com.kramttocs.lubelink.ui.WriteSafety
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Fresh disposable emulator only; synthetic local marker, never any real HTTP requests. */
@RunWith(AndroidJUnit4::class)
class WriteSafetyAndroidTest {
    @Test fun markerIsCommittedBeforeOperationAndDuplicateTapIsBlocked() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("lubelink_unconfirmed_writes", Context.MODE_PRIVATE)
        assertTrue("Use a fresh emulator with no pending real writes", prefs.getString("pending", null) == null)
        val ran = CountDownLatch(1)
        val sawMarker = AtomicBoolean(false)
        val intent = RecordWriteIntent(WriteAction.ADD, RecordKind.SERVICE)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            assertTrue(WriteSafety.start(context, "Add synthetic test record", intent) {
                sawMarker.set(WriteSafety.unresolved(context) != null &&
                    WriteSafety.unresolvedIntent(context) == intent)
                ran.countDown()
            })
            assertFalse("Repeated Save must be blocked before the disk commit finishes",
                WriteSafety.start(context, "Duplicate synthetic operation", intent) { fail("duplicate") })
        }
        assertTrue("Synthetic operation did not complete", ran.await(10, TimeUnit.SECONDS))
        assertTrue("Durable marker must predate the operation", sawMarker.get())
        val deadline = System.currentTimeMillis() + 10000
        while (System.currentTimeMillis() < deadline && WriteSafety.state !is WriteSafety.State.Success) {
            Thread.sleep(25)
        }
        assertTrue(WriteSafety.state is WriteSafety.State.Success)
        assertNull(WriteSafety.unresolved(context))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            WriteSafety.acknowledge(context, checkedServer = false)
        }
    }
}
