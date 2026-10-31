package com.kramttocs.lubelink.quality

import android.os.Looper
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A JVM-only test flake's cause, checked directly: a screen's effect that waits on an IO thread (as every network call does) must
 * resume on the main thread, as on a phone. Compose's first test rules resumed it on the IO thread under
 * Robolectric, so a state write there could recompose off the main thread (CalledFromWrongThreadException, or
 * "performMeasureAndLayout called during measure layout"). The v2 rules, used by every screen test, queue it to the
 * main thread. Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class TestDispatcherThreadTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anEffectResumesOnTheMainThreadAfterWorkOnAnIoThread() {
        val off = Collections.synchronizedList(mutableListOf<String>())
        var done = 0
        compose.setContent {
            LaunchedEffect(Unit) {
                repeat(50) {
                    withContext(Dispatchers.IO) { Thread.sleep(1) }
                    if (Looper.myLooper() != Looper.getMainLooper()) off += Thread.currentThread().name
                    done++
                }
            }
        }
        compose.waitUntil(10_000) { done == 50 }
        assertEquals("Resumed off the main thread", emptyList<String>(), off.toList())
    }
}
