package com.kramttocs.lubelink.quality

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import com.kramttocs.lubelink.core.AlertLevel
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.GarageSection
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.NotificationRules
import com.kramttocs.lubelink.core.ReminderAlertCommit
import com.kramttocs.lubelink.core.ReminderId
import com.kramttocs.lubelink.core.ReminderNotificationScheduler
import com.kramttocs.lubelink.core.ReminderNotificationSettings
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What a reminder check does after its network read ([ReminderAlertCommit]),
 * driven with a synthetic Garage. It posts nothing and records nothing once the account is
 * disconnected, its key replaced, reminders switched off, the vehicle excluded or hidden, or the
 * threshold tightened; settings changes and revoke() wait for a check that is deciding; revoke()
 * leaves no "already alerted" memory behind. The server is `lubelogger.invalid`, which never
 * resolves; the network read itself is not exercised here. Fresh disposable emulator only.
 */
@RunWith(AndroidJUnit4::class)
class ReminderRevocationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = SecureConfig(context)
    private val alerts = ReminderNotificationSettings(context)
    private lateinit var scope: String
    private var epoch = 0L

    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", false)
    private val dueReminder = MaintenanceReminder(ReminderId(4), car.id, "Rotate tires", "VeryUrgent", "Date",
        "2026-10-01", null, -3, null, "")
    private val garage = GarageData(listOf(car), emptyMap(), listOf(dueReminder))
    private val eventKey = NotificationRules.eventKey(car.id, dueReminder.id)
    private val posted = CopyOnWriteArrayList<Notification>()

    @Before fun connectAndEnable() {
        config.baseUrl = "https://lubelogger.invalid"
        config.saveApiKey("synthetic-reminder-key")
        scope = checkNotNull(alerts.accountScope(config)) { "synthetic account must have a scope" }
        alerts.setEnabled(scope, true)
        epoch = config.credentialEpoch // what the worker captures before its network read
    }

    @After fun cleanUp() {
        ReminderNotificationScheduler.revoke(context)
        config.setVehicleHidden(car.id, false)
        config.disconnect()
        config.baseUrl = ""
        context.getSharedPreferences("lubelink_reminder_alerts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** The step after the network read, with posting captured instead of shown. */
    private val withdrawn = java.util.concurrent.atomic.AtomicInteger()
    private fun commitAfterTheRead(stopped: Boolean = false, post: (Notification) -> Boolean = { posted += it; true }): Boolean =
        ReminderAlertCommit.commit(context, config, scope, epoch, garage, stopped = { stopped }, canPost = { true },
            post = post, withdraw = { withdrawn.incrementAndGet() })

    private fun assertNothingPostedOrRecorded(result: Boolean) {
        assertFalse(result)
        assertTrue("nothing may be posted", posted.isEmpty())
        assertNull("nothing may be recorded as alerted", alerts.previousRank(scope, eventKey))
        assertEquals("no result may be recorded for a retired check", 0L, alerts.lastCompleted(scope))
    }

    @Test fun controlTheCurrentEnabledAccountPostsAndRecords() {
        assertTrue(commitAfterTheRead())
        assertEquals(1, posted.size)
        assertEquals(1, alerts.previousRank(scope, eventKey))
        assertTrue(alerts.lastSuccess(scope) > 0)
    }

    /** OD-24: reminders that failed to load are unknown, not "none due". The check records nothing, so the last
     * real result and what was alerted stay as they were. */
    @Test fun aCheckWhoseRemindersFailedToLoadRecordsNothing() {
        assertTrue(commitAfterTheRead())
        val lastSuccess = alerts.lastSuccess(scope)
        posted.clear()
        val withoutReminders = garage.copy(reminders = emptyList(), missing = setOf(GarageSection.REMINDERS))
        val result = ReminderAlertCommit.commit(context, config, scope, epoch, withoutReminders, canPost = { true },
            post = { posted += it; true }, withdraw = { withdrawn.incrementAndGet() })
        assertFalse("a check without reminders is not a successful check", result)
        assertTrue(posted.isEmpty())
        assertEquals("what was alerted before is kept", 1, alerts.previousRank(scope, eventKey))
        assertEquals("no new success is recorded", lastSuccess, alerts.lastSuccess(scope))
        assertEquals(0, withdrawn.get())
    }

    @Test fun nothingAfterDisconnect() {
        config.disconnect()
        assertNothingPostedOrRecorded(commitAfterTheRead())
    }

    @Test fun nothingForTheOldKeyAfterAKeyChange() {
        config.saveApiKey("synthetic-replacement-key")
        assertNothingPostedOrRecorded(commitAfterTheRead())
    }

    @Test fun nothingAfterRemindersAreSwitchedOff() {
        alerts.setEnabled(scope, false)
        assertNothingPostedOrRecorded(commitAfterTheRead())
    }

    @Test fun nothingFromAStoppedCheck() = assertNothingPostedOrRecorded(commitAfterTheRead(stopped = true))

    // The vehicle and threshold settings as they are when the check decides, not when it started.
    @Test fun nothingForAVehicleExcludedFromAlerts() {
        alerts.setVehicleEnabled(scope, car.id, false)
        assertTrue(commitAfterTheRead())
        assertTrue(posted.isEmpty())
        assertNull(alerts.previousRank(scope, eventKey))
    }

    @Test fun nothingForAHiddenVehicle() {
        config.setVehicleHidden(car.id, true)
        assertTrue(commitAfterTheRead())
        assertTrue(posted.isEmpty())
        assertNull(alerts.previousRank(scope, eventKey))
    }

    @Test fun nothingBelowATightenedThreshold() {
        alerts.setThreshold(scope, AlertLevel.PAST_DUE) // the reminder is Very urgent, not Past due
        assertTrue(commitAfterTheRead())
        assertTrue(posted.isEmpty())
        assertNull(alerts.previousRank(scope, eventKey))
    }

    /**
     * Runs a check whose post blocks until released, and runs [during] while it is posting. [during]
     * must finish without waiting for the post. Returns the check's result.
     */
    private fun whileACheckIsPosting(during: () -> Unit): Boolean {
        val inPost = CountDownLatch(1)
        val release = CountDownLatch(1)
        var result: Boolean? = null
        val check = Thread {
            result = commitAfterTheRead { posted += it; inPost.countDown(); release.await(5, TimeUnit.SECONDS); true }
        }
        check.start()
        assertTrue(inPost.await(5, TimeUnit.SECONDS))
        val start = System.nanoTime()
        during()
        assertTrue("a change must not wait for a check that is posting", (System.nanoTime() - start) / 1e6 < 200)
        release.countDown()
        check.join(5_000)
        return checkNotNull(result)
    }

    /** OD-13: a change made while a check is posting withdraws that notification and records nothing. */
    private fun assertWithdrawn(result: Boolean) {
        assertFalse(result)
        assertEquals("the posted notification is withdrawn", 1, withdrawn.get())
        assertNull("and not counted as alerted", alerts.previousRank(scope, eventKey))
    }

    @Test fun excludingAVehicleWhileACheckPostsWithdrawsIt() {
        assertWithdrawn(whileACheckIsPosting { alerts.setVehicleEnabled(scope, car.id, false) })
        assertFalse(alerts.vehicleEnabled(scope, car.id, false))
    }

    @Test fun hidingAVehicleWhileACheckPostsWithdrawsIt() {
        assertWithdrawn(whileACheckIsPosting { config.setVehicleHidden(car.id, true) })
        assertTrue(config.isVehicleHidden(car.id))
    }

    @Test fun switchingAlertsOffWhileACheckPostsWithdrawsIt() =
        assertWithdrawn(whileACheckIsPosting { alerts.setEnabled(scope, false); ReminderNotificationScheduler.revoke(context) })

    /** Revoke leaves no alert memory, so a reconnect to the same account alerts again. */
    @Test fun disconnectWhileACheckPostsWithdrawsItAndAReconnectAlertsAgain() {
        assertWithdrawn(whileACheckIsPosting { config.disconnect(); ReminderNotificationScheduler.revoke(context) })
        config.saveApiKey("synthetic-reminder-key") // the same account again
        epoch = config.credentialEpoch               // a new check after reconnecting
        posted.clear()
        assertTrue(commitAfterTheRead())
        assertEquals("the still-due reminder alerts again", 1, posted.size)
        assertNotNull(alerts.previousRank(scope, eventKey))
    }

    @Test fun controlAChangeAfterTheCheckFinishedLeavesItsAlert() {
        assertTrue(commitAfterTheRead())
        alerts.setVehicleEnabled(scope, car.id, false)
        assertEquals("nothing is withdrawn for a change made afterwards", 0, withdrawn.get())
        assertNotNull(alerts.previousRank(scope, eventKey))
    }

    @Test fun checkNowIsNamedWorkThatRevokeEnds() {
        ReminderNotificationScheduler.checkNow(context)
        val manager = WorkManager.getInstance(context)
        assertTrue("Check Now must be unique work so it can be cancelled",
            manager.getWorkInfosForUniqueWork("lubelink_reminder_check_now").get().isNotEmpty())
        ReminderNotificationScheduler.revoke(context)
        val deadline = System.currentTimeMillis() + 10_000
        var infos = manager.getWorkInfosForUniqueWork("lubelink_reminder_check_now").get()
        while (infos.any { !it.state.isFinished } && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            infos = manager.getWorkInfosForUniqueWork("lubelink_reminder_check_now").get()
        }
        assertTrue("After revoke no Check Now may still be pending or running: $infos", infos.all { it.state.isFinished })
    }


    /** The remembered account identity follows every key change, server change and Disconnect. */
    @Test fun theRememberedAccountFollowsKeyServerAndDisconnect() {
        assertEquals(scope, alerts.accountScope(config))
        config.saveApiKey("synthetic-replacement-key")
        val replaced = alerts.accountScope(config)
        assertTrue("a new key is a new account", replaced != null && replaced != scope)
        config.baseUrl = "https://lubelogger-other.invalid"
        val moved = alerts.accountScope(config)
        assertTrue("a new server is a new account", moved != null && moved != replaced)
        config.disconnect()
        assertNull("no account after Disconnect", alerts.accountScope(config))
        config.baseUrl = "https://lubelogger.invalid"
        config.saveApiKey("synthetic-reminder-key")
        assertEquals("the same server and key are the same account again", scope, alerts.accountScope(config))
    }
}
