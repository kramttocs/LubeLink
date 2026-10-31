package com.kramttocs.lubelink.core

import androidx.core.content.edit
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.kramttocs.lubelink.MainActivity
import com.kramttocs.lubelink.R
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Short, memory-only critical sections that keep a reminder check consistent with alert-setting
 * changes: a check's decision and its bookkeeping, every alert-preference write (including hiding a
 * vehicle), and revoke's reset. Nothing slow runs under it, in particular never Android's notify, so a
 * settings tap on the main thread never waits for a check that is posting.
 */
internal object ReminderAlertLock

/**
 * Counts alert-setting changes (settings, hiding a vehicle, revoke). A check records its alert only if
 * nothing changed between its decision and the end of its post; otherwise it withdraws what it posted
 * (OD-13). Credential changes are tracked by [SecureConfig.credentialEpoch].
 */
internal object ReminderAlertChanges {
    @Volatile var count = 0L
        private set
    /** Call under [ReminderAlertLock], with the write it announces. */
    fun changed() { count++ }
}

/** One private, per-device account identity, not a family-wide key or root account. */
class ReminderNotificationSettings(context: Context) {
    private val prefs = StoredFormats.prefs(context, StoredFormat.REMINDER_ALERTS)
    /**
     * The account the alert settings belong to. Deriving it decrypts the key with the Keystore, which
     * measured tens to hundreds of ms on the main thread, so it is remembered for the current
     * [SecureConfig.credentialEpoch], which changes with every server or key change.
     */
    fun accountScope(config: SecureConfig): String? {
        val epoch = config.credentialEpoch // read first: a change while deriving is never cached as current
        cachedScope?.let { (cachedEpoch, scope) -> if (cachedEpoch == epoch) return scope }
        val server = MaintenanceRules.allowedServerUrl(config.baseUrl)
        val key = server?.let { config.readApiKey()?.takeIf { it.isNotBlank() } }
        val scope = if (server != null && key != null) NotificationRules.scope(server, key) else null
        cachedScope = epoch to scope
        return scope
    }
    // All settings scoped by account (server + API key), never by shared vehicle ID alone.
    private fun field(scope: String, suffix: String) = "${scope}_$suffix"
    // Every read tolerates a value of the wrong type under its key (a damaged file): it reads as the default, never
    // stopping the app or the reminder check. Only the app writes this file, each key with its own type.
    private fun flag(key: String, default: Boolean): Boolean = runCatching { prefs.getBoolean(key, default) }.getOrDefault(default)
    private fun time(key: String): Long = runCatching { prefs.getLong(key, 0) }.getOrDefault(0)
    fun enabled(scope: String): Boolean = flag(field(scope, "enabled"), false)
    fun setEnabled(scope: String, enabled: Boolean) = synchronized(ReminderAlertLock) {
        prefs.edit { putBoolean(field(scope, "enabled"), enabled) }
        ReminderAlertChanges.changed()
    }
    fun threshold(scope: String): AlertLevel = AlertLevel.of(runCatching { prefs.getString(field(scope, "level"), null) }.getOrNull())
    fun setThreshold(scope: String, level: AlertLevel) = synchronized(ReminderAlertLock) {
        prefs.edit { putString(field(scope, "level"), level.code) }
        ReminderAlertChanges.changed()
    }
    fun vehicleEnabled(scope: String, vehicleId: VehicleId, hiddenByDefault: Boolean): Boolean =
        flag(field(scope, "vehicle_$vehicleId"), !hiddenByDefault)
    fun setVehicleEnabled(scope: String, vehicleId: VehicleId, enabled: Boolean) = synchronized(ReminderAlertLock) {
        prefs.edit { putBoolean(field(scope, "vehicle_$vehicleId"), enabled) }
        ReminderAlertChanges.changed()
    }
    private companion object {
        @Volatile var cachedScope: Pair<Long, String?>? = null
    }
    fun lastSuccess(scope: String): Long = time(field(scope, "last_success"))
    fun lastCompleted(scope: String): Long = time(field(scope, "last_completed"))
    fun lastError(scope: String): String = runCatching { prefs.getString(field(scope, "last_error"), "") }.getOrNull().orEmpty()
    fun setResult(scope: String, success: Boolean, message: String = "") {
        prefs.edit {
            putLong(field(scope, "last_completed"), System.currentTimeMillis())
            if (success) {
                putLong(field(scope, "last_success"), System.currentTimeMillis())
                remove(field(scope, "last_error"))
            } else putString(field(scope, "last_error"), message.take(120))
        }
    }
    /** The rank this reminder was last alerted at; an unreadable one reads as never alerted, so at worst one alert
     * repeats, and none is missed. */
    fun previousRank(scope: String, eventId: String): Int? =
        field(scope, "alert_$eventId").let { if (prefs.contains(it)) runCatching { prefs.getInt(it, 3) }.getOrNull() else null }
    fun recordRank(scope: String, eventId: String, rank: Int) {
        prefs.edit { putInt(field(scope, "alert_$eventId"), rank) }
    }
    fun resetEvent(scope: String, eventId: String) {
        prefs.edit { remove(field(scope, "alert_$eventId")) }
    }
    /** Forget which reminders were already alerted, for every account. [revoke] removes the posted
     * notifications, so anything still due may alert again. */
    internal fun clearAlertRanks() = prefs.edit {
        prefs.all.keys.filter { it.contains("_alert_") }.forEach { remove(it) }
    }
}

object ReminderNotificationScheduler {
    private const val UNIQUE_WORK = "lubelink_reminder_background_checks"
    /** Check Now is unique too, so Disconnect and key changes can cancel it. */
    private const val MANUAL_WORK = "lubelink_reminder_check_now"
    const val CHANNEL_ID = "lubelink_maintenance"
    /** The notification's tap opens this vehicle's reminder (-1 when it covers several); read by MainActivity. */
    const val EXTRA_VEHICLE_ID = "notification_vehicle_id"
    const val EXTRA_REMINDER_ID = "notification_reminder_id"

    fun synchronize(context: Context, config: SecureConfig) {
        val settings = ReminderNotificationSettings(context)
        val scope = settings.accountScope(config)
        val manager = WorkManager.getInstance(context)
        if (scope == null || !settings.enabled(scope)) {
            manager.cancelUniqueWork(UNIQUE_WORK)
            manager.cancelUniqueWork(MANUAL_WORK)
            return
        }
        // Daily is a preference, not an exact clock: Doze and battery policies may defer it.
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        manager.enqueueUniquePeriodicWork(UNIQUE_WORK, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ReminderNotificationWorker>(24, TimeUnit.HOURS)
                .setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, ReminderCheckRules.BACKOFF_SECONDS, TimeUnit.SECONDS).build())
    }

    fun checkNow(context: Context) {
        val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ReminderNotificationWorker>().setConstraints(online)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, ReminderCheckRules.BACKOFF_SECONDS, TimeUnit.SECONDS).build())
    }

    /**
     * Stops all checks, removes posted reminders and forgets which were alerted. Call it after the
     * credential or setting change it follows has been stored: a check already past its network read
     * then sees the change in [ReminderAlertCommit], records nothing and withdraws anything it posts.
     */
    fun revoke(context: Context) {
        // Memory only under the lock; a check posting meanwhile sees the change and withdraws.
        synchronized(ReminderAlertLock) {
            ReminderNotificationSettings(context).clearAlertRanks()
            ReminderAlertChanges.changed()
        }
        // On the caller's thread, in call order, so a later synchronize() for a new account is
        // enqueued after these cancellations and is not cancelled by them.
        val manager = WorkManager.getInstance(context)
        manager.cancelUniqueWork(UNIQUE_WORK)
        manager.cancelUniqueWork(MANUAL_WORK)
        NotificationManagerCompat.from(context).cancelAll()
    }

    /** Permission and app-level notifications. A muted Maintenance channel is checked separately. */
    fun appCanPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** The user turned off the Maintenance reminders channel (importance "none"); not yet created counts as on. */
    fun channelMuted(context: Context): Boolean =
        NotificationManagerCompat.from(context).getNotificationChannel(CHANNEL_ID)?.importance ==
            NotificationManager.IMPORTANCE_NONE

    /** Whether Android would show a reminder now. Only then is it counted as alerted. */
    fun canPost(context: Context): Boolean = appCanPost(context) && !channelMuted(context)

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Maintenance reminders",
            NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Vehicle maintenance reminders from your LubeLogger account"
        })
    }
}

/** Why a reminder check failed: the message it records, and whether a quick retry can help (OD-32). */
enum class ReminderCheckFailure(val message: String, val retry: Boolean) {
    REJECTED("LubeLogger rejected the API key", retry = false),
    UNREADABLE("LubeLogger sent data LubeLink could not read", retry = false),
    SERVER("LubeLogger returned an error", retry = true),
    CONNECTION("Connection failed; check network or VPN", retry = true)
}

object ReminderCheckRules {
    /** Quick retries of one failed check (WorkManager backoff from 30 s, doubling); after them the failure stands
     * until the next daily check or "Check now" (OD-32). */
    const val MAX_RETRIES = 3
    const val BACKOFF_SECONDS = 30L

    /** From the exception's type and HTTP status, never its message. A missing cause counts as a server fault. */
    fun classify(e: Exception?): ReminderCheckFailure = when {
        e is LubeLoggerApi.ApiException && (e.httpStatus == 401 || e.httpStatus == 403) -> ReminderCheckFailure.REJECTED
        e is UnreadableResponseException || e is org.json.JSONException -> ReminderCheckFailure.UNREADABLE
        e == null || e is LubeLoggerApi.ApiException -> ReminderCheckFailure.SERVER
        else -> ReminderCheckFailure.CONNECTION
    }

    /** [runAttemptCount] is WorkManager's: 0 on the first run of a check. */
    fun retries(failure: ReminderCheckFailure, runAttemptCount: Int): Boolean =
        failure.retry && runAttemptCount < MAX_RETRIES
}

/** Read-only HTTP checks. No service, reminder or fuel data is ever modified on the server. */
class ReminderNotificationWorker(context: Context, workerParams: WorkerParameters) :
    CoroutineWorker(context, workerParams) {
    override suspend fun doWork(): Result = backgroundMutex.withLock {
        val app = applicationContext
        val config = SecureConfig(app)
        val preferences = ReminderNotificationSettings(app)
        val epoch = config.credentialEpoch // before the scope, so any later change is seen
        val scope = preferences.accountScope(config) ?: return@withLock Result.success()
        if (!preferences.enabled(scope)) return@withLock Result.success()
        val server = config.baseUrl
        val key = config.readApiKey()?.takeIf { it.isNotBlank() } ?: return@withLock Result.success()
        // Records the failure (a fixed message, never a URL, key or response body) and decides whether to try again
        // soon: at most ReminderCheckRules.MAX_RETRIES times, and never for a rejected key or unreadable data (OD-32).
        fun failed(failure: ReminderCheckFailure): Result {
            ReminderAlertCommit.recordFailure(app, config, scope, epoch, failure.message) { isStopped }
            return if (ReminderCheckRules.retries(failure, runAttemptCount)) Result.retry() else Result.failure()
        }
        try {
            val garage = LubeLoggerApi(server, key).loadGarage()
            // Reminders that failed to load are unknown, not "none due": this check has not happened (OD-24). Their
            // cause decides the message and whether to retry.
            if (GarageSection.REMINDERS in garage.missing)
                return@withLock failed(ReminderCheckRules.classify(garage.causes[GarageSection.REMINDERS]))
            // Everything after the read is one step under ReminderAlertLock, against current settings.
            ReminderAlertCommit.commit(app, config, scope, epoch, garage, stopped = { isStopped })
            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(ReminderCheckRules.classify(e))
        }
    }
    companion object {
        private val backgroundMutex = Mutex()
    }
}

/**
 * What a reminder check does after its network read:
 * 1. decide, under [ReminderAlertLock] (memory only): stop unless [scope] is still the stored, enabled
 *    account and the check was not stopped; choose events with the settings as they are now;
 * 2. post, outside the lock, so settings changes and revoke never wait for Android;
 * 3. under the lock again, record what was alerted and the result only if no credential or alert
 *    setting changed since step 1; otherwise record nothing and withdraw the notification (OD-13: it may
 *    show for a moment). Nothing is recorded for a retired account.
 */
internal object ReminderAlertCommit {
    private const val NOTIFICATION_ID = 18218

    private fun current(context: Context, config: SecureConfig, scope: String, epoch: Long, stopped: () -> Boolean): Boolean =
        !stopped() && config.credentialEpoch == epoch && ReminderNotificationSettings(context).enabled(scope)

    private class Decision(val changes: Long, val events: List<Pair<MaintenanceReminder, Int>>)

    /** Returns false, having recorded nothing, if the account or alert settings changed. */
    fun commit(context: Context, config: SecureConfig, scope: String, epoch: Long, garage: GarageData,
               stopped: () -> Boolean = { false },
               canPost: () -> Boolean = { ReminderNotificationScheduler.canPost(context) },
               /** Returns whether it was posted. */
               post: (android.app.Notification) -> Boolean = {
                   try { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, it); true }
                   catch (_: SecurityException) { false } // Permission was revoked between check and post.
               },
               withdraw: () -> Unit = { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }): Boolean {
        // Without the reminders there is nothing to decide, and no successful check to record.
        if (GarageSection.REMINDERS in garage.missing) return false
        val app = context.applicationContext
        ReminderNotificationScheduler.ensureChannel(app)
        val postable = canPost()
        val preferences = ReminderNotificationSettings(app)
        val decision = synchronized(ReminderAlertLock) {
            if (!current(app, config, scope, epoch, stopped)) return false
            Decision(ReminderAlertChanges.count, chooseEvents(config, preferences, scope, garage))
        }
        val posted = decision.events.isNotEmpty() && postable &&
            ReminderAlertChanges.count == decision.changes && post(buildNotification(app, config, garage, decision.events))
        // Android's own notification settings can change while posting, without the app being told. Count
        // the reminders as alerted only if Android would still show them. A change after this
        // re-check cannot be seen; then the reminder alerts again only once it becomes more urgent.
        val shown = posted && canPost()
        val recorded = synchronized(ReminderAlertLock) {
            val unchanged = ReminderAlertChanges.count == decision.changes && current(app, config, scope, epoch, stopped)
            if (unchanged) {
                if (shown) decision.events.forEach { (reminder, rank) ->
                    preferences.recordRank(scope, NotificationRules.eventKey(reminder.vehicleId, reminder.id), rank)
                }
                preferences.setResult(scope, true)
            }
            unchanged
        }
        if (posted && !recorded) withdraw()
        return recorded
    }

    private fun chooseEvents(config: SecureConfig, preferences: ReminderNotificationSettings, scope: String,
                             garage: GarageData): List<Pair<MaintenanceReminder, Int>> {
        val vehicles = garage.vehicles.associateBy { it.id }
        val events = mutableListOf<Pair<MaintenanceReminder, Int>>()
        for (reminder in garage.reminders) {
            val vehicle = vehicles[reminder.vehicleId] ?: continue // server-accessible only
            // Hidden Garage vehicles are also excluded from LubeLink Reminders views.
            // Never notify for a record the user cannot open in the app.
            if (config.isVehicleHidden(vehicle.id)) continue
            if (!preferences.vehicleEnabled(scope, vehicle.id, false)) continue
            val id = NotificationRules.eventKey(reminder.vehicleId, reminder.id)
            val rank = NotificationRules.eligible(reminder.urgency, preferences.threshold(scope))
            if (rank == null) {
                preferences.resetEvent(scope, id) // re-alert if a future cycle later becomes due
                continue
            }
            val previous = preferences.previousRank(scope, id)
            if (NotificationRules.isNewAlert(previous, rank)) events += reminder to rank
        }
        return events
    }

    private fun buildNotification(app: Context, config: SecureConfig, garage: GarageData,
                                  events: List<Pair<MaintenanceReminder, Int>>): android.app.Notification {
        val vehicles = garage.vehicles.associateBy { it.id }
        val top = events.sortedBy { it.second }.take(3)
        val first = top.first().first
        val name = vehicles[first.vehicleId]?.let { config.nicknameFor(it.id).ifBlank { it.displayName } }
            ?: "Vehicle"
        val title = if (events.size == 1) "$name — ${first.description.take(64)}"
            else "${events.size} maintenance reminders need attention"
        val body = if (events.size == 1) when (top.first().second) {
            0 -> "Past due. Tap to view this reminder."
            1 -> "Very urgent. Tap to view this reminder."
            else -> "Due soon. Tap to view this reminder."
        } else "${events.size} reminders across your vehicles. Expand for details."
        val groupedLines = top.map { (r, _) ->
            val vehicleName = vehicles[r.vehicleId]?.let { v ->
                config.nicknameFor(v.id).ifBlank { v.displayName }
            } ?: "Vehicle"
            "$vehicleName — ${r.description.take(48)}"
        }
        val intent = Intent(app, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(ReminderNotificationScheduler.EXTRA_VEHICLE_ID, if (events.size == 1) first.vehicleId.value else -1)
            putExtra(ReminderNotificationScheduler.EXTRA_REMINDER_ID, if (events.size == 1) first.id.value else -1)
        }
        val pending = PendingIntent.getActivity(app, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(app, ReminderNotificationScheduler.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_link)
            .setContentTitle(title).setContentText(body)
            .setStyle(if (events.size == 1) NotificationCompat.BigTextStyle().bigText(body)
                else NotificationCompat.InboxStyle().also { style ->
                    groupedLines.forEach { style.addLine(it) }
                    if (events.size > groupedLines.size)
                        style.setSummaryText("+${events.size - groupedLines.size} more")
                })
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT).build()
    }

    /** Records a failed check, unless the account was retired meanwhile. */
    fun recordFailure(context: Context, config: SecureConfig, scope: String, epoch: Long, message: String,
                      stopped: () -> Boolean) = synchronized(ReminderAlertLock) {
        if (current(context, config, scope, epoch, stopped)) ReminderNotificationSettings(context).setResult(scope, false, message)
    }
}
