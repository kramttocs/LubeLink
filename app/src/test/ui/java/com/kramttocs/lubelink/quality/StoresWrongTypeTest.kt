package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.AlertLevel
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.ReminderNotificationSettings
import com.kramttocs.lubelink.core.ServiceDescriptionRules
import com.kramttocs.lubelink.core.ServiceDescriptionStore
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.StoredFormats
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.core.WriteMarkerKeys
import com.kramttocs.lubelink.ui.StationNearbyView
import com.kramttocs.lubelink.ui.WriteSafety
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The stored files besides the settings tolerate a value of the wrong type under a key (a damaged file), as the
 * settings do: reminder alerts, description favourites and the station view read their defaults, and the write
 * marker keeps its lock. Each key gets a value of another type, in a current-format file. Synthetic values; runs on
 * the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class StoresWrongTypeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val formats = listOf(StoredFormat.REMINDER_ALERTS, StoredFormat.DESCRIPTION_FAVORITES,
        StoredFormat.STATION_VIEW, StoredFormat.UNCONFIRMED_WRITE)
    private fun file(format: StoredFormat) = context.getSharedPreferences(format.fileName, Context.MODE_PRIVATE)
    private fun current(format: StoredFormat) = file(format).edit().clear().putInt(StoredFormats.VERSION_KEY, format.version)

    @Before fun fresh() {
        StoredFormats.forgetCheckedForTest()
        formats.forEach { file(it).edit().clear().commit() }
    }

    @After fun cleanUp() = fresh()

    @Test fun everyReminderAlertSettingReadsItsDefaultWhenItsValueHasTheWrongType() {
        val scope = "synthetic-scope"
        current(StoredFormat.REMINDER_ALERTS)
            .putString("${scope}_enabled", "yes")
            .putInt("${scope}_level", 2)
            .putString("${scope}_vehicle_${VehicleId(7)}", "off")
            .putString("${scope}_last_success", "today")
            .putBoolean("${scope}_last_completed", true)
            .putInt("${scope}_last_error", 7)
            .putString("${scope}_alert_synthetic-event", "urgent")
            .commit()
        val alerts = ReminderNotificationSettings(context)
        assertFalse("Alerts off", alerts.enabled(scope))
        assertEquals(AlertLevel.of(null), alerts.threshold(scope))
        assertTrue("A vehicle's own default", alerts.vehicleEnabled(scope, VehicleId(7), hiddenByDefault = false))
        assertFalse(alerts.vehicleEnabled(scope, VehicleId(7), hiddenByDefault = true))
        assertEquals(0L, alerts.lastSuccess(scope))
        assertEquals(0L, alerts.lastCompleted(scope))
        assertEquals("", alerts.lastError(scope))
        assertNull("An unreadable earlier alert reads as never alerted", alerts.previousRank(scope, "synthetic-event"))
    }

    @Test fun descriptionFavouritesOfTheWrongTypeReadAsNone() {
        val server = "https://lubelogger.invalid"
        current(StoredFormat.DESCRIPTION_FAVORITES)
            .putString(ServiceDescriptionRules.preferenceKey(server, RecordKind.SERVICE), "Synthetic oil change")
            .commit()
        val store = ServiceDescriptionStore(context, server)
        assertEquals(emptySet<String>(), store.favorites(RecordKind.SERVICE))
        assertFalse(store.isFavorite(RecordKind.SERVICE, "Synthetic oil change"))
    }

    @Test fun aStationViewOfTheWrongTypeReadsAsTheMap() {
        current(StoredFormat.STATION_VIEW).putInt("nearby_view_synthetic", 1).commit()
        assertEquals(StationNearbyView.MAP,
            StationNearbyView.read(StoredFormats.prefs(context, StoredFormat.STATION_VIEW), "nearby_view_synthetic"))
    }

    /** The lock is kept: only an absent marker means no write is unresolved. */
    @Test fun aWriteMarkerOfTheWrongTypeKeepsTheLock() {
        current(StoredFormat.UNCONFIRMED_WRITE).putInt(WriteMarkerKeys.ACTIVE, 7).putInt(WriteMarkerKeys.INTENT, 7).commit()
        assertEquals(WriteMarkerKeys.DAMAGED_DESCRIPTION, WriteSafety.unresolved(context))
        assertNull("The intent is unknown", WriteSafety.unresolvedIntent(context))
    }

    @Test fun anAbsentWriteMarkerIsNoLock() {
        current(StoredFormat.UNCONFIRMED_WRITE).commit()
        assertNull(WriteSafety.unresolved(context))
    }
}
