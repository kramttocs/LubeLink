package com.kramttocs.lubelink.quality

import android.app.Notification
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.ReminderAlertCommit
import com.kramttocs.lubelink.core.ReminderId
import com.kramttocs.lubelink.core.ReminderNotificationScheduler
import com.kramttocs.lubelink.core.ReminderNotificationSettings
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Alert-setting changes made while a reminder check is posting are saved at once, for the
 * server and account they were made on, and a Disconnect's clean-up never cancels the next account's
 * schedule. Each case calls what the UI calls, in the UI's order. Synthetic servers `lubelogger-a.invalid`
 * and `lubelogger-b.invalid` share vehicle ID 42 and are never contacted. Fresh disposable emulator only.
 */
@RunWith(AndroidJUnit4::class)
class AlertSessionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = SecureConfig(context)
    private val alerts = ReminderNotificationSettings(context)
    private val serverA = "https://lubelogger-a.invalid"
    private val serverB = "https://lubelogger-b.invalid"
    private val shared = VehicleId(42)
    private val garage = GarageData(listOf(Vehicle(shared, 2015, "Synthetic", "Wagon", "", false)), emptyMap(),
        listOf(MaintenanceReminder(ReminderId(4), shared, "Rotate tires", "VeryUrgent", "Date", "2026-10-01",
            null, -3, null, "")))

    private fun connect(server: String, key: String): String {
        config.baseUrl = server
        config.saveApiKey(key)
        val scope = checkNotNull(alerts.accountScope(config))
        alerts.setEnabled(scope, true)
        return scope
    }

    @Before fun connectToA() { connect(serverA, "synthetic-key-a") }

    @After fun cleanUp() {
        ReminderNotificationScheduler.revoke(context)
        for (server in listOf(serverA, serverB)) { config.baseUrl = server; config.setVehicleHidden(shared, false) }
        config.disconnect()
        config.baseUrl = ""
        context.getSharedPreferences("lubelink_reminder_alerts", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** Starts account A's check and holds it inside Android's post until the returned latch is released. */
    private fun holdACheckInItsPost(): Pair<CountDownLatch, Thread> {
        val scope = checkNotNull(alerts.accountScope(config))
        val epoch = config.credentialEpoch
        val inPost = CountDownLatch(1)
        val release = CountDownLatch(1)
        val check = Thread {
            ReminderAlertCommit.commit(context, config, scope, epoch, garage, canPost = { true },
                post = { _: Notification -> inPost.countDown(); release.await(10, TimeUnit.SECONDS); true },
                withdraw = {})
        }.apply { start() }
        assertTrue(inPost.await(5, TimeUnit.SECONDS))
        return release to check
    }

    private fun finish(held: Pair<CountDownLatch, Thread>) { held.first.countDown(); held.second.join(10_000) }

    private fun hiddenOn(server: String): Boolean { config.baseUrl = server; return config.isVehicleHidden(shared) }

    /** A hide made on server A, then Disconnect and Setup on server B, stays on A only. */
    @Test fun aHideMadeWhileACheckPostsAppliesToItsOwnServerOnly() {
        val held = holdACheckInItsPost()
        config.setVehicleHidden(shared, true)                       // the Garage hide on A, as LubeLinkApp does
        config.disconnect(); ReminderNotificationScheduler.revoke(context) // Disconnect
        connect(serverB, "synthetic-key-b")                       // Setup on B
        finish(held)
        assertFalse("B's vehicle 42 must not be hidden", hiddenOn(serverB))
        assertTrue("A's vehicle 42 must stay hidden", hiddenOn(serverA))
    }

    /** Disconnect while a check posts, then Setup on another account: its schedule survives. */
    @Test fun aDisconnectWhileACheckPostsDoesNotCancelTheNextAccountsSchedule() {
        // B had alerts enabled in an earlier session, so connecting to it changes no alert setting.
        connect(serverB, "synthetic-key-b")
        connect(serverA, "synthetic-key-a")
        val held = holdACheckInItsPost()
        config.disconnect(); ReminderNotificationScheduler.revoke(context)  // Disconnect A
        config.baseUrl = serverB; config.saveApiKey("synthetic-key-b")     // Setup on B
        ReminderNotificationScheduler.synchronize(context, config)         // as SetupScreen does
        finish(held)
        Thread.sleep(3_000) // WorkManager applies enqueue and cancel operations asynchronously, in order
        val states = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("lubelink_reminder_background_checks").get().map { it.state }
        assertTrue("B's daily check must still be scheduled: $states",
            states.any { it == WorkInfo.State.ENQUEUED || it == WorkInfo.State.RUNNING || it == WorkInfo.State.BLOCKED })
    }

    /** Two quick taps on the alerts switch while a check posts are both saved, so they cancel out. */
    @Test fun twoQuickTapsWhileACheckPostsAreBothSaved() {
        val scope = checkNotNull(alerts.accountScope(config))
        val held = holdACheckInItsPost()
        repeat(2) { alerts.setEnabled(scope, !alerts.enabled(scope)) } // Settings shows, and toggles, the saved value
        assertTrue("the second tap reverses the first at once", alerts.enabled(scope))
        finish(held)
        assertTrue(alerts.enabled(scope))
    }
}
