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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-63: the settings file is format 2 (it gained the corner style and the reminder-suggestions switch). A settings
 * file an earlier LubeLink wrote, at version 1 or from before versions were recorded, keeps every value through the
 * migration, and the two new settings read as their defaults. A newer file (a downgraded app) is cleared, never read.
 * Synthetic values; the real initializer through SecureConfig. Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class SettingsFormatMigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val server = "https://lubelogger.invalid"
    private fun file() = context.getSharedPreferences(StoredFormat.SETTINGS.fileName, Context.MODE_PRIVATE)

    @Before fun fresh() {
        StoredFormats.forgetCheckedForTest()
        file().edit().clear().commit()
    }

    @After fun cleanUp() = fresh()

    /** What a version-1 LubeLink wrote: a server, choices, a nickname and a hidden vehicle. */
    private fun writeVersion1File(version: Int?) {
        val nickname = MaintenanceRules.nicknamePreferenceKey(server, VehicleId(7))
        file().edit().apply {
            if (version != null) putInt(StoredFormats.VERSION_KEY, version)
            putString("server", server)
            putString("garage_layout", SecureConfig.GarageLayout.LIST.name)
            putString("startup", SecureConfig.Startup.GARAGE.name)
            putInt("last_vehicle", 7)
            putBoolean("include_fuel", false)
            putBoolean("history_newest_first", false)
            putBoolean(MaintenanceRules.visibilityPreferenceKey(server, VehicleId(7)), true)
            putString(nickname, "Synthetic Runabout")
        }.commit()
    }

    private fun assertEverythingKept(settings: SecureConfig) {
        assertEquals("Now format 2", 2, file().getInt(StoredFormats.VERSION_KEY, -1))
        assertEquals(server, settings.baseUrl)
        assertEquals(SecureConfig.GarageLayout.LIST, settings.garageLayout)
        assertEquals(SecureConfig.Startup.GARAGE, settings.startup)
        assertEquals(VehicleId(7), settings.lastVehicleId)
        assertFalse(settings.includeFuel)
        assertFalse(settings.historyNewestFirst)
        assertTrue("The hidden vehicle stays hidden", settings.isVehicleHidden(VehicleId(7)))
        assertEquals("Synthetic Runabout", settings.nicknameFor(VehicleId(7)))
        assertEquals("The new corner setting reads as its default", CornerStyle.ROUNDED, settings.cornerStyle)
        assertTrue("The new suggestions switch reads as its default", settings.suggestReminders)
    }

    @Test fun aVersion1SettingsFileKeepsEverythingAndBecomesFormat2() {
        writeVersion1File(version = 1)
        assertEverythingKept(SecureConfig(context))
    }

    @Test fun aSettingsFileFromBeforeVersionsKeepsEverythingAndBecomesFormat2() {
        writeVersion1File(version = null)
        assertEverythingKept(SecureConfig(context))
    }

    @Test fun aNewInstallIsFormat2() {
        val settings = SecureConfig(context)
        assertEquals("", settings.baseUrl)
        assertEquals(2, file().getInt(StoredFormats.VERSION_KEY, -1))
    }

    /** The downgrade policy, unchanged: a file from a newer LubeLink is cleared, never guessed at. */
    @Test fun aNewerSettingsFileIsClearedNeverRead() {
        writeVersion1File(version = 3)
        val settings = SecureConfig(context)
        assertEquals("", settings.baseUrl)
        assertEquals(2, file().getInt(StoredFormats.VERSION_KEY, -1))
    }
}
