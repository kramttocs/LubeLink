package com.kramttocs.lubelink.quality

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.core.VehicleImages
import com.kramttocs.lubelink.ui.LubeTheme
import com.kramttocs.lubelink.ui.VehiclePicture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Vehicle pictures load through [VehicleImages], which these tests answer by hand. A cached picture shows at
 * once and a fresh copy replaces it; a picture that cannot be loaded keeps what is shown; another vehicle cancels the
 * load, no further request is made for the old picture, and a late reply for it never appears. Each picture
 * is one solid colour, and the tests read the colour actually drawn in the picture's place, so a fresh copy that is
 * fetched but never shown fails them. Synthetic vehicles and generated pictures; nothing leaves the test device. Runs
 * on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class VehiclePictureUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun car(id: Int, name: String) =
        Vehicle(VehicleId(id), 2015, "Synthetic", name, imageLocation = "/images/synthetic-$id.png", useHours = false)
    private val wagon = car(1, "Wagon")
    private val pickup = car(2, "Pickup")
    private var shown by mutableStateOf(wagon)
    private var refresh by mutableStateOf(0)

    private class Images(val pastCancel: Boolean = false) : VehicleImages {
        val cached = HeldCalls<Bitmap?>()
        val fresh = HeldCalls<Bitmap?>()
        val generations = mutableListOf<Int>()
        override suspend fun getCachedImage(path: String) =
            if (pastCancel) cached.holdPastCancel(path) else cached.hold(path)
        override suspend fun getImage(path: String, refreshGeneration: Int): Bitmap? {
            generations += refreshGeneration
            return if (pastCancel) fresh.holdPastCancel(path) else fresh.hold(path)
        }
    }

    private fun show(images: VehicleImages) = compose.setContent {
        LubeTheme { VehiclePicture(shown, images, refreshGeneration = refresh, modifier = Modifier.width(200.dp)) }
    }

    /** The three pictures: the cached copy, the server's copy, and the copy after a manual refresh. */
    private enum class Shade(val argb: Int) { CACHED(0xFFD02020.toInt()), FRESH(0xFF2040D0.toInt()), REFRESHED(0xFF20B040.toInt()) }

    private fun picture(width: Int, height: Int, shade: Shade = Shade.CACHED): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(shade.argb) }
    private fun pictureShown(vehicle: Vehicle) = compose.onAllNodes(
        hasContentDescription("${vehicle.displayName} picture")).fetchSemanticsNodes().isNotEmpty()

    /** The colour drawn at the centre of [vehicle]'s picture, read from the window as the user would see it. */
    private fun shadeShown(vehicle: Vehicle): Shade? {
        val node = compose.onAllNodes(hasContentDescription("${vehicle.displayName} picture")).fetchSemanticsNodes()
            .singleOrNull() ?: return null
        var pixel = 0
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            val window = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(window))
            pixel = window.getPixel(node.boundsInWindow.center.x.toInt(), node.boundsInWindow.center.y.toInt())
        }
        return Shade.entries.firstOrNull { shade ->
            listOf(Color::red, Color::green, Color::blue).all { channel -> kotlin.math.abs(channel(pixel) - channel(shade.argb)) < 24 }
        }
    }
    private fun awaitShade(vehicle: Vehicle, shade: Shade) = runCatching { compose.awaitCondition { shadeShown(vehicle) == shade } }
        .onFailure { throw AssertionError("Expected the $shade picture for ${vehicle.displayName}; drawn: ${shadeShown(vehicle)}", it) }
    private fun placeholderShown() = compose.onAllNodes(hasText("Vehicle")).fetchSemanticsNodes().isNotEmpty()
    private fun <T> awaitCalls(calls: HeldCalls<T>, count: Int) = compose.awaitCondition { calls.calls.size == count }

    @Test fun aCachedPictureShowsAtOnceAndTheServerCopyFollows() {
        val images = Images()
        show(images)
        assertTrue("The placeholder shows while nothing is loaded", placeholderShown())
        awaitCalls(images.cached, 1)
        images.cached.last.succeed(picture(40, 20, Shade.CACHED))
        awaitCalls(images.fresh, 1)
        awaitShade(wagon, Shade.CACHED)
        images.fresh.last.succeed(picture(30, 20, Shade.FRESH))
        awaitShade(wagon, Shade.FRESH)
        assertEquals("/images/synthetic-1.png", images.fresh.last.argument)
    }

    @Test fun aManualRefreshShowsTheNewServerCopy() {
        val images = Images()
        show(images)
        awaitCalls(images.cached, 1)
        images.cached.last.succeed(picture(40, 20, Shade.CACHED))
        awaitCalls(images.fresh, 1)
        images.fresh.last.succeed(picture(40, 20, Shade.FRESH))
        awaitShade(wagon, Shade.FRESH)
        refresh = 1
        awaitCalls(images.cached, 2)
        images.cached.last.succeed(null)
        awaitCalls(images.fresh, 2)
        assertEquals("The refresh asks for generation 1", listOf(0, 1), images.generations)
        assertEquals("The current picture stays while the refresh loads", Shade.FRESH, shadeShown(wagon))
        images.fresh.last.succeed(picture(40, 20, Shade.REFRESHED))
        awaitShade(wagon, Shade.REFRESHED)
    }

    @Test fun aPictureThatCannotBeLoadedKeepsWhatIsShown() {
        val images = Images()
        show(images)
        awaitCalls(images.cached, 1)
        images.cached.last.succeed(picture(40, 20, Shade.CACHED))
        awaitCalls(images.fresh, 1)
        images.fresh.last.fail(IllegalStateException("Synthetic: image unavailable"))
        compose.waitForIdle()
        assertEquals("The cached picture stays", Shade.CACHED, shadeShown(wagon))
    }

    @Test fun withNoPictureAtAllThePlaceholderStays() {
        val images = Images()
        show(images)
        awaitCalls(images.cached, 1)
        images.cached.last.fail(IllegalStateException("Synthetic: unreadable cache"))
        awaitCalls(images.fresh, 1)
        images.fresh.last.fail(IllegalStateException("Synthetic: image unavailable"))
        compose.waitForIdle()
        assertTrue(placeholderShown())
        assertFalse(pictureShown(wagon))
    }

    @Test fun anotherVehicleCancelsTheLoadAndNothingMoreIsAskedForTheOldPicture() {
        val images = Images()
        show(images)
        awaitCalls(images.cached, 1)
        shown = pickup
        compose.awaitCondition { images.cached[0].cancelled }
        awaitCalls(images.cached, 2)
        assertEquals("/images/synthetic-2.png", images.cached.last.argument)
        compose.waitForIdle()
        assertTrue("No fresh copy of the old picture was asked for",
            images.fresh.calls.none { it.argument == "/images/synthetic-1.png" })
    }

    @Test fun aLatePictureForTheOldVehicleNeverAppears() {
        val images = Images(pastCancel = true)
        show(images)
        awaitCalls(images.cached, 1)
        shown = pickup
        awaitCalls(images.cached, 2)
        images.cached[0].succeed(picture(80, 10))
        compose.waitForIdle()
        assertFalse("The old vehicle's picture is not shown for the new one", pictureShown(pickup))
        assertTrue(placeholderShown())
    }
}
