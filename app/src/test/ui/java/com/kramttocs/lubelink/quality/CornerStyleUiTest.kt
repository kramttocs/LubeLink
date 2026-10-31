package com.kramttocs.lubelink.quality

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.CornerStyle
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.StoredFormats
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-53: the corner style, tapped through the whole app against the synthetic garage server. A new install, and a
 * saved value the app does not know, draw Rounded corners; choosing Square in Settings → Garage & personalization
 * changes the corners on every screen at once and is kept for the next start; choosing Rounded again brings them back.
 * Each check reads the pixels drawn at a control's top-left corner: a square corner is drawn in the colour of the
 * control's edge, a rounded one leaves the background showing. Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class CornerStyleUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private var server: ScreenshotServer? = null
    private val driver = TourDriver(compose) {}

    @After fun cleanUp() {
        settings.removeManagerKey()
        settings.disconnect()
        server?.close()
        ScreenshotGarage.startFresh(context)
    }

    private fun open(saved: (() -> Unit)? = null) {
        server = ScreenshotServer()
        ScreenshotGarage.startFresh(context)
        saved?.invoke()
        settings.baseUrl = server!!.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }
        driver.waitFor("2019 Honda CR-V")
    }

    private fun window(): Bitmap {
        lateinit var picture: Bitmap
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            picture = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(picture))
        }
        return picture
    }

    private fun close(a: Int, b: Int) =
        listOf(Color::red, Color::green, Color::blue).all { kotlin.math.abs(it(a) - it(b)) < 28 }

    /**
     * Whether the control [matcher] finds is drawn with a square top-left corner. From just outside the node, scan to
     * the first pixel that is not background along the middle of its top and of its left side: that is where its
     * edge is drawn. A square corner is drawn there in the edge's colour; a rounded one is still background.
     */
    private fun squareCorner(matcher: SemanticsMatcher): Boolean {
        val bounds: Rect = compose.onAllNodes(matcher).onFirst().fetchSemanticsNode().boundsInWindow
        val picture = window()
        val midX = bounds.center.x.toInt()
        val midY = bounds.center.y.toInt()
        val outsideY = (bounds.top.toInt() - 2).coerceAtLeast(0)
        val outsideX = (bounds.left.toInt() - 2).coerceAtLeast(0)
        val background = picture.getPixel(midX, outsideY)
        val top = (outsideY..midY).first { !close(picture.getPixel(midX, it), background) }
        val left = (outsideX..midX).first { !close(picture.getPixel(it, midY), background) }
        val edge = picture.getPixel(left, midY)
        return close(picture.getPixel(left, top), edge)
    }

    private val addRecord = hasText("Add record") and hasClickAction()

    private fun openVehicle() { driver.tap("2019 Honda CR-V"); driver.waitFor("Add record") }

    private fun chooseCorners(label: String) {
        driver.tap("Settings")
        driver.tap("Garage & personalization")
        driver.scrollToText(label)
        driver.tap(label)
        compose.waitForIdle()
    }

    @Test fun aNewInstallDrawsRoundedCorners() {
        open()
        assertEquals(CornerStyle.ROUNDED, settings.cornerStyle)
        openVehicle()
        assertFalse("A new install's buttons are rounded", squareCorner(addRecord))
    }

    @Test fun aSavedValueTheAppDoesNotKnowDrawsRoundedCorners() {
        open { StoredFormats.prefs(context, StoredFormat.SETTINGS).edit().putString("corner_style", "triangle").commit() }
        assertEquals(CornerStyle.ROUNDED, settings.cornerStyle)
        openVehicle()
        assertFalse("An unknown saved value reads as Rounded", squareCorner(addRecord))
    }

    /** A value of the wrong type under the key (a damaged file) reads as Rounded too, never stopping the app. */
    @Test fun aSavedValueOfTheWrongTypeReadsAsRounded() {
        StoredFormats.prefs(context, StoredFormat.SETTINGS).edit().putInt("corner_style", 1).commit()
        assertEquals(CornerStyle.ROUNDED, settings.cornerStyle)
    }

    @Test fun choosingSquareChangesTheCornersAtOnceAndIsSaved() {
        open()
        chooseCorners("Square")
        assertEquals("The choice is saved on the phone", CornerStyle.SQUARE, settings.cornerStyle)
        assertTrue("The chosen chip itself is square", squareCorner(hasText("Square") and hasClickAction()))
        driver.tap("Overview")
        openVehicle()
        assertTrue("Buttons on another screen are square", squareCorner(addRecord))
        chooseCorners("Rounded")
        assertEquals(CornerStyle.ROUNDED, settings.cornerStyle)
        driver.tap("Overview")
        driver.waitFor("Add record")
        assertFalse("Rounded again brings the rounded corners back", squareCorner(addRecord))
    }

    @Test fun aSavedSquareChoiceAppliesFromTheStart() {
        open { settings.cornerStyle = CornerStyle.SQUARE }
        openVehicle()
        assertTrue("The saved choice applies when the app starts", squareCorner(addRecord))
    }
}
