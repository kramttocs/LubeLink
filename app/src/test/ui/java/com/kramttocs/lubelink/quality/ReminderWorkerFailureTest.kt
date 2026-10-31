package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.kramttocs.lubelink.core.ReminderNotificationSettings
import com.kramttocs.lubelink.core.ReminderNotificationWorker
import com.kramttocs.lubelink.core.SecureConfig
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** OD-32 with the real reminder worker against the synthetic server: when the reminders fail to load, the
 * recorded message says why, unreadable data is not retried, and a server fault is retried at most three times.
 * Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class ReminderWorkerFailureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private val alerts = ReminderNotificationSettings(context)
    @Volatile private var remindersBody: String? = null
    @Volatile private var remindersFail = false
    private val server = ScreenshotServer(
        override = { path -> if (path.startsWith("/api/vehicle/reminders/all")) remindersBody else null },
        fails = { path -> remindersFail && path.startsWith("/api/vehicle/reminders/all") })
    private lateinit var scope: String

    @Before fun connectWithAlertsOn() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        scope = alerts.accountScope(settings)!!
        alerts.setEnabled(scope, true)
    }

    @After fun cleanUp() {
        alerts.setEnabled(scope, false)
        settings.disconnect()
        server.close()
    }

    private fun check(attempt: Int): Result = runBlocking {
        TestListenableWorkerBuilder<ReminderNotificationWorker>(context).setRunAttemptCount(attempt).build().doWork()
    }

    @Test fun unreadableRemindersAreRecordedAsSuchAndNotRetried() {
        remindersBody = "[{}]"
        assertEquals("No quick retry for data LubeLink cannot read", Result.failure(), check(0))
        assertEquals("LubeLogger sent data LubeLink could not read", alerts.lastError(scope))
    }

    @Test fun aServerFaultIsRetriedAtMostThreeTimes() {
        remindersFail = true
        assertEquals(Result.retry(), check(0))
        assertEquals(Result.retry(), check(2))
        assertEquals("After three retries the failure stands until the next check", Result.failure(), check(3))
        assertEquals("LubeLogger returned an error", alerts.lastError(scope))
    }
}
