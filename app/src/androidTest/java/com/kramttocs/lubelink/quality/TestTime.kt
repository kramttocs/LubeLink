package com.kramttocs.lubelink.quality

/** Emulator: time passes by itself; wait a little. The JVM version of this file moves Robolectric's clock. */
fun passTime(ms: Long) = Thread.sleep(ms)
