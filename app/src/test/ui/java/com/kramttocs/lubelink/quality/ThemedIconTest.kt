package com.kramttocs.lubelink.quality

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.R
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-54: both launcher icons carry a monochrome layer for Android's themed icons, and it is the same artwork as the
 * foreground, compared by its pixels (same size, so the logo is the normal icon's size). Runs on the JVM and the emulator (API 33+).
 */
@RunWith(AndroidJUnit4::class)
class ThemedIconTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** The layer drawn at its own size: Android themes the monochrome layer by its alpha, so the alpha is the shape. */
    private fun alpha(layer: Drawable): IntArray {
        val bitmap = Bitmap.createBitmap(layer.intrinsicWidth, layer.intrinsicHeight, Bitmap.Config.ARGB_8888)
        layer.setBounds(0, 0, bitmap.width, bitmap.height)
        layer.draw(Canvas(bitmap))
        return IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
            .map { it ushr 24 }.toIntArray()
    }

    private fun check(id: Int) {
        val icon = context.getDrawable(id) as AdaptiveIconDrawable
        val monochrome = assertNotNull("A monochrome layer for themed icons", icon.monochrome).let { icon.monochrome!! }
        assertEquals("The same size as the foreground", icon.foreground.intrinsicWidth, monochrome.intrinsicWidth)
        assertEquals(icon.foreground.intrinsicHeight, monochrome.intrinsicHeight)
        val logo = alpha(icon.foreground)
        assertTrue("The logo draws something", logo.any { it > 0 })
        assertArrayEquals("The same artwork as the foreground, pixel for pixel", logo, alpha(monochrome))
    }

    @Test fun theLauncherIconHasAThemedLayer() = check(R.mipmap.ic_launcher)
    @Test fun theRoundLauncherIconHasAThemedLayer() = check(R.mipmap.ic_launcher_round)
}
