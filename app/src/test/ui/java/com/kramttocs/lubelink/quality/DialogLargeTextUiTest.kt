package com.kramttocs.lubelink.quality

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.LubeLoggerManagerApi
import com.kramttocs.lubelink.core.LubeLoggerManagerApi.DeleteSnapshot
import com.kramttocs.lubelink.core.RecordDeleteGateway
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.DeleteRecordDialog
import com.kramttocs.lubelink.ui.LubeTheme
import com.kramttocs.lubelink.ui.WriteSafety
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** At Android's largest text (font scale 2.0) every sentence of the Delete confirmation, and the error shown
 * under it, can be brought fully into view, and its buttons stay on screen. The real Delete dialog with a long
 * synthetic description (within the 300-character display bound). Every dialog body uses the same `DialogBody`.
 * Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class DialogLargeTextUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Long Wheelbase Touring Wagon", "", useHours = false)

    @Before fun noPendingWrite() {
        assertTrue("WriteSafety must start idle", WriteSafety.unresolved(context) == null &&
            WriteSafety.state is WriteSafety.State.Idle)
    }

    private var originalScale: Float? = null

    /** Sets the activity's own font scale: a dialog is a separate window and takes its text size from the
     * configuration, not from a LocalDensity provided around it. */
    private fun setFontScale(scale: Float) = compose.runOnUiThread {
        val resources = compose.activity.resources
        originalScale = originalScale ?: resources.configuration.fontScale
        @Suppress("DEPRECATION")
        resources.updateConfiguration(Configuration(resources.configuration).apply { fontScale = scale },
            resources.displayMetrics)
    }

    @After fun restoreFontScale() { originalScale?.let { setFontScale(it) } }

    private fun atLargestText(content: @Composable () -> Unit) {
        setFontScale(2f)
        compose.setContent { LubeTheme { content() } }
    }

    /** Brings [text] into view the way a user can (by scrolling, where the body scrolls) and checks that none of it
     * is clipped. */
    private fun assertWhollyVisible(text: String) {
        val found = compose.onNodeWithText(text, substring = true)
        // A body that cannot scroll has nothing to scroll; the clipping check below then decides.
        runCatching { found.performScrollTo() }
        val node = found.assertIsDisplayed().fetchSemanticsNode()
        assertEquals("Not at the largest text", 2f, node.layoutInfo.density.fontScale)
        assertEquals("\"$text\" is cut off", node.size.height.toFloat(), node.boundsInWindow.height, 1f)
    }

    @Test fun deleteShowsEveryConsequenceAndTheErrorAtTheLargestText() {
        val failing = object : RecordDeleteGateway {
            override suspend fun prepare(vehicle: Vehicle, displayed: ServiceRecord): DeleteSnapshot =
                throw LubeLoggerManagerApi.ManagerException("LubeLogger returned an error; nothing was deleted.")
            override suspend fun delete(snapshot: DeleteSnapshot) = error("Delete must not run")
        }
        // Near the 300-character display bound, as a detailed LubeLogger description can be.
        val description = "Synthetic front and rear brake pads and rotors, caliper slide pins, brake fluid flush, " +
            "parking brake adjustment, wheel bearing inspection, tire rotation and balance, alignment check, " +
            "cabin and engine air filters, wiper blades and a road test after the work was finished"
        assertTrue(description.length in 250..300)
        val record = ServiceRecord(RecordId(15), car.id, "2026-10-01",
            description, 123456, "1,245.50", "", "",
            emptyList(), RecordKind.SERVICE)
        atLargestText {
            DeleteRecordDialog(car, record, "2015 Synthetic Long Wheelbase Touring Wagon", "https://lubelogger.invalid",
                "synthetic-manager", onDismiss = {}, manager = failing)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTextCount("nothing was deleted") > 0
        }
        assertWhollyVisible("This permanently removes this record")
        assertWhollyVisible("may return used supplies to inventory")
        assertWhollyVisible("nothing was deleted")
        compose.onNodeWithText("Delete record").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }

    private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
}
