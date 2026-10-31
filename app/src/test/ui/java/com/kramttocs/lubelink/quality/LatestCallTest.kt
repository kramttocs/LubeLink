package com.kramttocs.lubelink.quality

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.ui.LatestCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** OD-42: the holder behind every dialog's network call. Only the latest run reports; a cancelled run is stopped;
 * a reply that arrives after its run went stale is never used. Plain coroutines, no screen. */
@RunWith(AndroidJUnit4::class)
class LatestCallTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val calls = HeldCalls<String>()
    private val reported = mutableListOf<String>()
    private val latest = LatestCall(scope)

    @After fun stop() = scope.cancel()

    private fun start(argument: String, pastCancel: Boolean = false) = latest.run(
        { if (pastCancel) calls.holdPastCancel(argument) else calls.hold(argument) },
        onSuccess = { reported += "ok:$it" }, onFailure = { reported += "failed:${it.message}" })

    @Test fun aFailureIsReportedAndEndsTheRun() {
        start("a")
        assertTrue(latest.busy)
        calls.last.fail(IllegalStateException("synthetic refusal"))
        assertEquals(listOf("failed:synthetic refusal"), reported)
        assertFalse(latest.busy)
    }

    @Test fun cancelStopsTheRunningCallAndItNeverReports() {
        start("a")
        latest.cancel()
        assertTrue("The running call was cancelled", calls.last.cancelled)
        assertFalse(latest.busy)
        calls.last.succeed("a")
        assertEquals(emptyList<String>(), reported)
    }

    @Test fun aNewerRunCancelsTheOlderOne() {
        start("a")
        start("b")
        assertTrue("The older call was cancelled", calls[0].cancelled)
        calls[1].succeed("b")
        assertEquals(listOf("ok:b"), reported)
    }

    @Test fun aLateReplyToAStaleRunIsNeverUsed() {
        start("a", pastCancel = true)
        start("b")
        calls[0].succeed("a")
        assertEquals("The stale reply was not reported", emptyList<String>(), reported)
        assertTrue("The newer run is still running", latest.busy)
        calls[1].succeed("b")
        assertEquals(listOf("ok:b"), reported)
        assertFalse(latest.busy)
    }

    @Test fun aLateFailureOfACancelledRunIsNeverShown() {
        start("a", pastCancel = true)
        latest.cancel()
        calls[0].fail(IllegalStateException("late"))
        assertEquals(emptyList<String>(), reported)
        assertFalse(latest.busy)
    }
}
