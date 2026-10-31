package com.kramttocs.lubelink.quality

import android.content.Context
import android.graphics.Bitmap
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.ui.LocalStationLookups
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The screen tour on the emulator: every shot in [ScreenTourShots], the whole app against
 * [ScreenshotGarage] served from the emulator itself, at normal text and at Android's largest (font scale 2.0). Each
 * shot is a full-screen PNG (dialogs included) through the test storage service; CI uploads them as the
 * "screenshots" artifact. A shot that cannot be reached is still photographed, named "FAILED", and fails the test.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotWalkthroughTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private var server: ScreenshotServer? = null
    private val failures = mutableListOf<String>()
    private val driver = TourDriver(compose) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    private fun setSystemFontScale(scale: Float) {
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("settings put system font_scale $scale").close()
        val deadline = System.currentTimeMillis() + 5_000
        while (context.resources.configuration.fontScale != scale && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @After fun cleanUp() {
        setSystemFontScale(1f)
        ScreenshotGarage.withoutReminders = emptySet()
        settings.disconnect()
        settings.removeManagerKey()
        server?.close()
        context.getSharedPreferences(StoredFormat.UNCONFIRMED_WRITE.fileName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun setupScreenAtNormalText() = tour(1f, "normal", ScreenTourShots.setup, connect = false)
    @Test fun setupScreenAtLargestText() = tour(2f, "largest", ScreenTourShots.setup, connect = false)
    @Test fun walkthroughAtNormalText() = tour(1f, "normal", ScreenTourShots.connected, connect = true)
    @Test fun walkthroughAtLargestText() = tour(2f, "largest", ScreenTourShots.connected, connect = true)

    private fun tour(fontScale: Float, size: String, shots: List<TourShot>, connect: Boolean) {
        ScreenshotGarage.startFresh(context)
        if (connect) {
            ScreenshotGarage.withoutReminders = setOf(4)   // the Kubota: the suggested reminders (shot 16)
            val running = ScreenshotServer().also { server = it }
            settings.baseUrl = running.baseUrl
            ScreenshotGarage.preferNearbyList(context, running.baseUrl)
            settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
            settings.saveManagerKey(ScreenshotGarage.MANAGER_KEY)
        }
        // The system font scale, as the phone's text-size setting sets it: dialogs are their own windows, so a scale
        // set only inside the composition would leave them at normal size.
        setSystemFontScale(fontScale)
        compose.activityRule.scenario.recreate()
        compose.setContent {
            LubeTheme {
                Surface(Modifier.fillMaxSize()) {
                    CompositionLocalProvider(LocalStationLookups provides ScreenshotGarage.stationLookups) { LubeLinkApp(settings) }
                }
            }
        }
        for (shot in shots) {
            val ok = runCatching { shot.reach(driver); compose.waitForIdle(); Thread.sleep(700) }
                .onFailure { failures += "$size/${shot.name}: ${it.message}" }.isSuccess
            val picture: Bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            TestStorage().openOutputFile("screenshots/$size/${shot.name}${if (ok) "" else "-FAILED"}.png").use {
                picture.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            if (ok) runCatching { shot.leave(driver) }.onFailure { failures += "$size/${shot.name} (leaving): ${it.message}" }
        }
        assertTrue("Screens that could not be reached:\n" + failures.joinToString("\n"), failures.isEmpty())
    }
}
