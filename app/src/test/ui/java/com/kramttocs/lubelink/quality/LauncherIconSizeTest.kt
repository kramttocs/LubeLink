package com.kramttocs.lubelink.quality

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.R
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

/**
 * OD-66: the launcher icon's logo (a shorter circuit tail, so the gear can be larger) fills the icon without leaving
 * Android's safe zone: nothing it draws lies outside the 66 dp circle that every launcher shape keeps, and it spans at
 * least 58 × 56 dp of the 108 dp layer (the earlier artwork spanned 51 × 47). Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconSizeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun check(id: Int) {
        val foreground = (context.getDrawable(id) as AdaptiveIconDrawable).foreground
        val size = foreground.intrinsicWidth
        val bitmap = Bitmap.createBitmap(size, foreground.intrinsicHeight, Bitmap.Config.ARGB_8888)
        foreground.setBounds(0, 0, bitmap.width, bitmap.height)
        foreground.draw(Canvas(bitmap))
        val pixels = IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
        val dp = size / 108.0
        val centre = (size - 1) / 2.0
        var reach = 0.0
        var left = size; var right = -1; var top = size; var bottom = -1
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            if (pixels[y * bitmap.width + x] ushr 24 == 0) continue
            reach = maxOf(reach, hypot(x - centre, y - centre))
            left = minOf(left, x); right = maxOf(right, x); top = minOf(top, y); bottom = maxOf(bottom, y)
        }
        assertTrue("The logo draws something", right >= 0)
        assertTrue("Inside the 33 dp safe-zone radius: reaches %.2f dp".format(reach / dp), reach <= 33 * dp)
        val width = (right - left + 1) / dp
        val height = (bottom - top + 1) / dp
        assertTrue("At least 58 × 56 dp: %.1f × %.1f dp".format(width, height), width >= 58 && height >= 56)
    }

    @Test fun theLauncherLogoFillsTheSafeZone() = check(R.mipmap.ic_launcher)
    @Test fun theRoundLauncherLogoFillsTheSafeZone() = check(R.mipmap.ic_launcher_round)
}
