package com.kramttocs.lubelink.quality

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.ui.AddStationAddressButton
import com.kramttocs.lubelink.ui.Gold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** At the largest text (font scale 2.0, the Add/Edit dialog's content width) the wrapped "+ Add station
 * address (optional)" label is drawn whole: its first glyphs are not cut by the button's corners. The test draws the
 * window and counts the label colour's pixels along the button's left edge. Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class AddStationAddressButtonUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theLabelIsNotClippedAtTheLargestTextSize() {
        val label = "+ Add station address (optional)"
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                LubeTheme {
                    Column(Modifier.width(268.dp)) {
                        AddStationAddressButton {}
                        // The same words as plain text, never clipped: the reference for how much of the label's
                        // left edge should be drawn.
                        Text(label, style = MaterialTheme.typography.labelMedium, color = Gold,
                            modifier = Modifier.testTag("reference"))
                    }
                }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithTag("reference").fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        assertTrue("The label wraps at this width", layouts.single().lineCount >= 2)
        val button = compose.onNode(hasClickAction()).fetchSemanticsNode().boundsInWindow
        val reference = compose.onNodeWithTag("reference").fetchSemanticsNode().boundsInWindow
        lateinit var picture: Bitmap
        compose.runOnIdle {
            val root = compose.activity.window.decorView
            picture = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(picture))
        }
        fun edgeInk(left: Float, top: Float, bottom: Float): Int {
            val edge = (reference.width * 0.04f).toInt().coerceAtLeast(4)
            var ink = 0
            for (x in left.toInt() until left.toInt() + edge) for (y in top.toInt() until bottom.toInt()) {
                val pixel = picture.getPixel(x, y)
                // The gold label on the dark theme: strong red and green, little blue.
                if (android.graphics.Color.red(pixel) > 150 && android.graphics.Color.green(pixel) > 110 &&
                    android.graphics.Color.blue(pixel) < 120) ink++
            }
            return ink
        }
        val drawn = edgeInk(button.left, button.top, button.bottom)
        val whole = edgeInk(reference.left, reference.top, reference.bottom)
        println("Label pixels at the left edge: button $drawn, unclipped reference $whole")
        assertTrue("No reference ink ($whole)", whole > 0)
        assertTrue("The label's first glyphs are cut off: $drawn of $whole edge pixels drawn", drawn >= whole * 0.9)
    }
}
