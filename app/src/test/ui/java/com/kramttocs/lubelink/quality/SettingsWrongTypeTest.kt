package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.CornerStyle
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.StoredFormats
import com.kramttocs.lubelink.core.VehicleId
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every setting read tolerates a value of the wrong type under its key (a damaged file): it reads as its default,
 * never stopping the app. Each key gets a value of another type, in a current-format file. Synthetic values; runs on
 * the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class SettingsWrongTypeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun file() = context.getSharedPreferences(StoredFormat.SETTINGS.fileName, Context.MODE_PRIVATE)

    @Before fun fresh() {
        StoredFormats.forgetCheckedForTest()
        file().edit().clear().commit()
    }

    @After fun cleanUp() = fresh()

    private val server = "https://lubelogger.invalid"

    @Test fun aServerAddressOfTheWrongTypeReadsAsNotConnected() {
        file().edit().putInt(StoredFormats.VERSION_KEY, StoredFormat.SETTINGS.version).putInt("server", 7).commit()
        assertEquals("", SecureConfig(context).baseUrl)
    }

    /** Every other setting, with a readable server (vehicle nicknames and hidden vehicles are kept per server). */
    @Test fun everySettingReadsItsDefaultWhenItsValueHasTheWrongType() {
        file().edit()
            .putInt(StoredFormats.VERSION_KEY, StoredFormat.SETTINGS.version)
            .putString("server", server)
            .putString("credential_epoch", "seven")
            .putString("last_vehicle", "seven")
            .putBoolean("favorite_vehicle", true)
            .putString("include_fuel", "no")
            .putInt("history_newest_first", 0)
            .putInt("garage_layout", 1)
            .putInt("startup", 1)
            .putString("suggest_reminders", "off")
            .putInt("corner_style", 1)
            .putString("primary_key_is_editor", "yes")
            .putInt("encrypted_key", 7)
            .putInt("encrypted_manager_key", 7)
            .putInt("encrypted_geoapify_key", 7)
            .putInt(MaintenanceRules.nicknamePreferenceKey(server, VehicleId(7)), 7)
            .putString(MaintenanceRules.visibilityPreferenceKey(server, VehicleId(7)), "yes")
            .commit()
        val settings = SecureConfig(context)
        assertEquals(server, settings.baseUrl)
        assertEquals(0L, settings.credentialEpoch)
        assertNull(settings.lastVehicleId)
        assertNull(settings.favoriteVehicleId)
        assertTrue(settings.includeFuel)
        assertTrue(settings.historyNewestFirst)
        assertEquals(SecureConfig.GarageLayout.GRID, settings.garageLayout)
        assertEquals(SecureConfig.Startup.LAST, settings.startup)
        assertTrue(settings.suggestReminders)
        assertEquals(CornerStyle.ROUNDED, settings.cornerStyle)
        assertNull(settings.readApiKey())
        assertFalse(settings.isEditorKeyConfigured())
        assertNull(settings.readManagerKey())
        assertNull(settings.readGeoapifyKey())
        assertEquals("", settings.nicknameFor(VehicleId(7)))
        assertFalse(settings.isVehicleHidden(VehicleId(7)))
    }
}
