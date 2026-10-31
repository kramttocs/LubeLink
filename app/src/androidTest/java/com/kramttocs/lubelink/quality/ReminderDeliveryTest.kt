package com.kramttocs.lubelink.quality

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kramttocs.lubelink.core.AlertLevel
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.NotificationRules
import com.kramttocs.lubelink.core.ReminderAlertCommit
import com.kramttocs.lubelink.core.ReminderId
import com.kramttocs.lubelink.core.ReminderNotificationScheduler
import com.kramttocs.lubelink.core.ReminderNotificationSettings
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import org.junit.runner.RunWith

/**
 * Reminder delivery and main-thread timing with Android's real notification service (permission granted, real channel, real
 * notify). Synthetic Garage; the server is `lubelogger.invalid` and is never contacted. Fresh disposable
 * emulator only: the Maintenance channel is muted by one test and stays muted for this emulator.
 */
@RunWith(AndroidJUnit4::class)
// The channel cannot be turned back on by the app, so the test that needs it on must run first.
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class ReminderDeliveryTest {
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

    @Before fun connectEnableAndAllowNotifications() {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        config.baseUrl = "https://lubelogger.invalid"
        config.saveApiKey("synthetic-delivery-key")
        scope = checkNotNull(alerts.accountScope(config))
        alerts.setEnabled(scope, true)
        epoch = config.credentialEpoch
    }

    @After fun cleanUp() {
        ReminderNotificationScheduler.revoke(context)
        config.disconnect()
        config.baseUrl = ""
        context.getSharedPreferences("lubelink_reminder_alerts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /**
     * The channel is turned off in Android's settings while the reminder is being posted, after the
     * check's first look. The real notify is called; the reminder must not count as alerted.
     * (App-level blocking cannot be tested the same way: revoking the permission ends the app's process.)
     */
    @Test fun aChannelMutedWhileAReminderPostsIsNotCountedAsAlerted() {
        ReminderNotificationScheduler.ensureChannel(context)
        assertTrue("control: permission granted and channel on", ReminderNotificationScheduler.canPost(context))
        val manager = context.getSystemService(NotificationManager::class.java)
        val result = ReminderAlertCommit.commit(context, config, scope, epoch, garage, post = { notification ->
            manager.createNotificationChannel(NotificationChannel(ReminderNotificationScheduler.CHANNEL_ID,
                "Maintenance reminders", NotificationManager.IMPORTANCE_NONE)) // the user's "off" switch
            NotificationManagerCompat.from(context).notify(18218, notification)
            true
        })
        assertTrue("the check itself completes", result)
        assertTrue(ReminderNotificationScheduler.channelMuted(context))
        assertNull("a reminder Android hid must not count as alerted", alerts.previousRank(scope, eventKey))
    }

    /** With the Maintenance channel turned off, nothing counts as alerted, so it can alert later. */
    @Test fun aMutedChannelIsNotCountedAsAlerted() {
        // The channel-on control is in the test before (which leaves the channel off; an app cannot turn
        // its own channel back on). An app may lower its own channel's importance; this is the "off" switch.
        ReminderNotificationScheduler.ensureChannel(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(ReminderNotificationScheduler.CHANNEL_ID,
            "Maintenance reminders", NotificationManager.IMPORTANCE_NONE))
        assertTrue(ReminderNotificationScheduler.channelMuted(context))
        assertFalse(ReminderNotificationScheduler.canPost(context))
        val posted = AtomicBoolean(false)
        // The production canPost, not a stub.
        assertTrue(ReminderAlertCommit.commit(context, config, scope, epoch, garage, post = { posted.set(true); true }))
        assertFalse("a hidden notification must not be posted", posted.get())
        assertNull("nor counted as alerted, so it alerts once the channel is on again",
            alerts.previousRank(scope, eventKey))
    }

    /**
     * How long does a settings tap on the main thread wait while checks post real
     * notifications? Checks post four times a second (Android itself drops an app's notifications above
     * about five a second; a real check posts at most once a day), and the main thread taps throughout,
     * calling the setter directly as Settings does. A single notify measured up to ~0.6 s here; the
     * check no longer holds the lock while posting, so a tap must not wait for it. Numbers are logged
     * under the tag AlertTapTiming, which CI copies into its summary.
     */
    @Test fun mainThreadSettingsChangesStayResponsiveWhileChecksPost() {
        val running = AtomicBoolean(true)
        var posts = 0
        val checks = Thread {
            repeat(20) {
                alerts.resetEvent(scope, eventKey) // so every check really posts
                ReminderAlertCommit.commit(context, config, scope, epoch, garage, canPost = { true })
                posts++
                Thread.sleep(250)
            }
            running.set(false)
        }.apply { start() }
        val waits = mutableListOf<Double>()
        var lastLevel = AlertLevel.DUE_SOON
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var i = 0
        while (running.get()) {
            instrumentation.runOnMainSync {
                val start = System.nanoTime()
                val level = if (i++ % 2 == 0) AlertLevel.DUE_SOON else AlertLevel.DUE
                alerts.setThreshold(scope, level)
                lastLevel = level
                waits += (System.nanoTime() - start) / 1e6
            }
            Thread.sleep(5)
        }
        checks.join(5_000)
        assertEquals("every tap is saved at once", lastLevel, alerts.threshold(scope))
        val sorted = waits.sorted()
        val summary = "posts=$posts taps=${waits.size} maxWaitMs=${"%.1f".format(sorted.last())} " +
            "p99WaitMs=${"%.1f".format(sorted[(sorted.size * 99) / 100])} meanWaitMs=${"%.2f".format(waits.average())}"
        Log.i("AlertTapTiming", summary)
        assertTrue("checks must have posted during the taps ($summary)", posts == 20)
        // The defect showed as taps waiting for each notify (p99 15.9 ms, max 393 ms before the fix).
        // One long outlier on a shared CI emulator is scheduling noise, so p99 is the main bound.
        assertTrue("taps waited too long: $summary; p99 must be under 5 ms", sorted[(sorted.size * 99) / 100] < 5.0)
        assertTrue("a tap waited too long: $summary; the longest must be under 250 ms", sorted.last() < 250.0)
    }

    /**
     * The alert switch (off and on), Disconnect and an Editor key change call WorkManager
     * and Android's notification service on the main thread. Each is timed there while checks keep
     * posting. Numbers are logged under the tag AlertActionTiming, which CI copies into its summary.
     */
    @Test fun mainThreadSwitchDisconnectAndKeyChangeStayResponsive() {
        val running = AtomicBoolean(true)
        val checks = Thread {
            while (running.get()) {
                alerts.resetEvent(scope, eventKey)
                ReminderAlertCommit.commit(context, config, scope, config.credentialEpoch, garage, canPost = { true })
                Thread.sleep(250)
            }
        }.apply { start() }
        val timings = mapOf("disable" to mutableListOf<Double>(), "enable" to mutableListOf(),
            "disconnect" to mutableListOf(), "keyChange" to mutableListOf())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun timeOnMain(action: String, block: () -> Unit) {
            instrumentation.runOnMainSync {
                val start = System.nanoTime()
                block()
                timings.getValue(action) += (System.nanoTime() - start) / 1e6
            }
            Thread.sleep(50)
        }
        repeat(20) {
            timeOnMain("disable") { alerts.setEnabled(scope, false); ReminderNotificationScheduler.revoke(context) }
            timeOnMain("enable") {
                alerts.setEnabled(scope, true)
                ReminderNotificationScheduler.synchronize(context, config)
                ReminderNotificationScheduler.checkNow(context)
            }
            timeOnMain("disconnect") { config.disconnect(); ReminderNotificationScheduler.revoke(context) }
            config.saveApiKey("synthetic-delivery-key") // reconnect (Setup's own work, not timed)
            timeOnMain("keyChange") {
                ReminderNotificationScheduler.revoke(context)
                ReminderNotificationScheduler.synchronize(context, config)
            }
        }
        running.set(false)
        checks.join(5_000)
        val worst = timings.map { (action, waits) ->
            val sorted = waits.sorted()
            Log.i("AlertActionTiming", "action=$action n=${sorted.size} maxMs=${"%.1f".format(sorted.last())} " +
                "p95Ms=${"%.1f".format(sorted[(sorted.size * 95) / 100])} meanMs=${"%.2f".format(waits.average())}")
            action to sorted.last()
        }
        worst.forEach { (action, maxMs) ->
            assertTrue("$action took ${"%.1f".format(maxMs)} ms on the main thread; must stay under 500 ms", maxMs < 500.0)
        }
    }
}
