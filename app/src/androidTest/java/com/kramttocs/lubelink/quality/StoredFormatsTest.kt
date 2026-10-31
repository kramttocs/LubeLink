package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.StoredFormats
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

/** Real SharedPreferences on the disposable emulator; synthetic values only. Uses the station-view file,
 * which holds nothing another test relies on. */
@RunWith(AndroidJUnit4::class)
class StoredFormatsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val format = StoredFormat.STATION_VIEW
    private fun raw() = context.getSharedPreferences(format.fileName, Context.MODE_PRIVATE)

    @After fun reset() {
        raw().edit().clear().commit()
        StoredFormats.forgetCheckedForTest()
    }

    @Test fun aFileFromBeforeVersionsKeepsItsValuesAndIsStamped() {
        raw().edit().clear().putString("synthetic_choice", "List").commit()
        StoredFormats.forgetCheckedForTest()
        val prefs = StoredFormats.prefs(context, format)
        assertEquals("List", prefs.getString("synthetic_choice", null))
        assertEquals(format.version, raw().getInt(StoredFormats.VERSION_KEY, -1))
    }

    @Test fun aFileFromANewerAppVersionIsClearedBeforeAnythingReadsIt() {
        raw().edit().clear().putInt(StoredFormats.VERSION_KEY, format.version + 1)
            .putString("synthetic_choice", "Written by a newer layout").commit()
        StoredFormats.forgetCheckedForTest()
        val prefs = StoredFormats.prefs(context, format)
        assertFalse(prefs.contains("synthetic_choice"))
        assertEquals(format.version, prefs.getInt(StoredFormats.VERSION_KEY, -1))
    }
}
