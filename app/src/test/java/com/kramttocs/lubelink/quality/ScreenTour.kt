package com.kramttocs.lubelink.quality

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.ui.LocalStationLookups
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import java.io.File
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

/**
 * The screen tour on the JVM: the same [ScreenTourShots] as the emulator, drawn with Robolectric's native graphics
 * into `app/build/screen-tour/<normal|largest>/`. Run it before every push that changes a screen, and look at every
 * picture:
 *
 *     ./gradlew :app:testDebugUnitTest --tests '*ScreenTour' -Plubelink.screenTour=true
 *
 * Skipped unless asked for, so ordinary unit runs cost nothing. The emulator's pictures, from full CI runs, are the
 * reference. Dialog windows are laid out at the screen's size by JvmDialogWindowShadow, so every shot, the Add/Edit
 * dialog included, is drawn here too.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenTour(private val fontScale: Float, private val size: String) {
    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{1}")
        fun scales() = listOf(arrayOf<Any>(1f, "normal"), arrayOf<Any>(2f, "largest"))
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private var server: ScreenshotServer? = null
    private val failures = mutableListOf<String>()
    private val out = File("build/screen-tour/$size").apply { mkdirs() }
    // Back goes to the top window, as on a phone, so an open dialog receives it (not the activity beneath).
    private val driver = TourDriver(compose) {
        val top = org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowWindowManagerImpl>(
            compose.activity.windowManager).views.last()
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP))
            top.dispatchKeyEvent(android.view.KeyEvent(action, android.view.KeyEvent.KEYCODE_BACK))
    }

    @After fun cleanUp() {
        RuntimeEnvironment.setFontScale(1f)
        ScreenshotGarage.withoutReminders = emptySet()
        settings.disconnect()
        settings.removeManagerKey()
        server?.close()
        context.getSharedPreferences(StoredFormat.UNCONFIRMED_WRITE.fileName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun tour() {
        assumeTrue("Run with -Plubelink.screenTour=true", System.getProperty("lubelink.screenTour") == "true")
        out.listFiles()?.forEach { it.delete() }
        ScreenshotGarage.startFresh(context)
        var connected by mutableStateOf(false)
        // The system font scale, as a phone's text-size setting sets it: dialogs are their own windows, so a scale set
        // only inside the composition would leave them at normal size.
        RuntimeEnvironment.setFontScale(fontScale)
        compose.activityRule.scenario.recreate()
        compose.setContent {
            LubeTheme {
                Surface(Modifier.fillMaxSize()) {
                    // A new LubeLinkApp after connecting, as the app has after its Setup screen.
                    key(connected) {
                        CompositionLocalProvider(LocalStationLookups provides ScreenshotGarage.stationLookups) {
                            LubeLinkApp(settings)
                        }
                    }
                }
            }
        }
        draw(ScreenTourShots.setup)
        ScreenshotGarage.withoutReminders = setOf(4)   // the Kubota: the suggested reminders (shot 16)
        val running = ScreenshotServer().also { server = it }
        settings.baseUrl = running.baseUrl
        ScreenshotGarage.preferNearbyList(context, running.baseUrl)
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        settings.saveManagerKey(ScreenshotGarage.MANAGER_KEY)
        compose.runOnIdle { connected = true }
        draw(ScreenTourShots.connected)
        assertTrue("Screens that could not be reached:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun draw(shots: List<TourShot>) {
        for (shot in shots) {
            val ok = runCatching { shot.reach(driver); compose.awaitCondition(5_000) { true }; passTime(700) }
                .onFailure { failures += "$size/${shot.name}: ${it.message}" }.isSuccess
            save(File(out, "${shot.name}${if (ok) "" else "-FAILED"}.png"))
            if (ok) runCatching { shot.leave(driver) }.onFailure { failures += "$size/${shot.name} (leaving): ${it.message}" }
        }
    }

    /** The activity's window, with an open dialog's window drawn over a scrim (Compose's capture misses dialogs). */
    private fun save(file: File) {
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val picture = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(picture)
            root.draw(canvas)
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView?.let { dialog ->
                canvas.drawColor(Color.argb(140, 0, 0, 0))
                canvas.save()
                canvas.translate(((root.width - dialog.width) / 2f).coerceAtLeast(0f),
                    ((root.height - dialog.height) / 2f).coerceAtLeast(0f))
                dialog.draw(canvas)
                canvas.restore()
            }
            file.outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
