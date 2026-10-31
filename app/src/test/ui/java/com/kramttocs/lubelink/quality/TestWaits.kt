package com.kramttocs.lubelink.quality

import androidx.compose.ui.test.junit4.ComposeTestRule

/**
 * [ComposeTestRule.waitUntil] for tests that run on the JVM and the emulator. Under Robolectric the main looper runs
 * only when the test idles it, and its clock moves only when the test moves it, so work a coroutine queues on the
 * main thread (and a delay, such as the write lock's minimum spinner time) would never run. This idles, checks, then
 * lets [passTime] move time on, until [condition] holds or [timeoutMs] of real time has gone by.
 */
fun ComposeTestRule.awaitCondition(timeoutMs: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        waitForIdle()
        if (condition()) return
        check(System.currentTimeMillis() < deadline) { "Condition still not satisfied after $timeoutMs ms" }
        passTime(20)
    }
}
