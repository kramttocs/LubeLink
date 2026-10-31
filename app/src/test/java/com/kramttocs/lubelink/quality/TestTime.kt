package com.kramttocs.lubelink.quality

import android.os.Looper
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** JVM (Robolectric): move the main looper's clock on, so delayed main-thread work becomes due, and let real
 * background threads (Dispatchers.IO) progress. The emulator's version of this file waits in real time. */
fun passTime(ms: Long) {
    Thread.sleep(2)
    shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
}
