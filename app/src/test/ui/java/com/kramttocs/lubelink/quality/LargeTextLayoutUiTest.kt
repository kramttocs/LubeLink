package com.kramttocs.lubelink.quality

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.NavigationBarItem
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.ui.BottomNavLabel
import com.kramttocs.lubelink.ui.DescriptionField
import com.kramttocs.lubelink.ui.LubeTheme
import com.kramttocs.lubelink.ui.StationPageChips
import com.kramttocs.lubelink.ui.StationPickerPage
import com.kramttocs.lubelink.ui.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Rows of labels at Android's largest text size (font scale 2.0, a 411 dp wide phone). The screen tour found "Remin/ders"
 * broken mid-word in the bottom navigation, and, in the Add/Edit and station dialogs, "Descriptio/n" and the station
 * pages cut to "Sav", "Nea", "Man". Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class LargeTextLayoutUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun navigationLabelsStayOnOneLineAtTheLargestTextSize() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                LubeTheme { NavigationBar {
                    Tab.entries.filter { it != Tab.REPORTS }.forEach { tab ->
                        NavigationBarItem(selected = tab == Tab.HOME, onClick = {},
                            icon = { Icon(tab.icon, contentDescription = null) }, label = { BottomNavLabel(tab.title) })
                    }
                } }
            }
        }
        for (tab in Tab.entries.filter { it != Tab.REPORTS }) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(tab.title, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
            assertEquals("${tab.title} must stay on one line", 1, layouts.single().lineCount)
            assertFalse("${tab.title} must not be shortened", layouts.single().isLineEllipsized(0))
        }
    }

    /** A dialog's content width at font scale 2.0 on the tour's phone, measured from the emulator's screenshot. */
    private val dialogContent = 268.dp

    private fun atLargestText(content: @Composable () -> Unit) = atScale(2f, content)

    private fun atScale(scale: Float, content: @Composable () -> Unit) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = scale)) {
            LubeTheme { Box(Modifier.width(dialogContent)) { content() } }
        }
    }

    private fun layoutOf(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
        return layouts.single()
    }

    @Test fun stationPickerPagesAreWholeAtTheLargestTextSize() {
        atLargestText { StationPageChips(StationPickerPage.FAVORITES) {} }
        for (page in StationPickerPage.entries) {
            val layout = layoutOf(page.label)
            assertEquals("${page.label} must stay on one line", 1, layout.lineCount)
            // Whole: every letter on that line, not ellipsized, and the line fits the width the chip gave it.
            assertEquals("${page.label} must not be cut short", page.label.length, layout.getLineEnd(0))
            assertFalse("${page.label} must not be shortened", layout.isLineEllipsized(0))
            assertTrue("${page.label} must fit its chip", layout.getLineRight(0) <= layout.size.width)
        }
    }

    @Test fun theDescriptionLabelIsNeverBrokenMidWord() {
        atLargestText { DescriptionField("", onChange = {}, onChoose = {}, isFavorite = false, onToggleFavorite = {}) }
        assertEquals("Description must not break onto a second line", 1, layoutOf("Description").lineCount)
        assertFalse("OD-37: Description must not be shortened", layoutOf("Description").isLineEllipsized(0))
    }

    /** OD-49 (found on the emulator at 2.0: "Purchase / d"): in the details dialog's width, no label or value is
     * broken in the middle of a word or shortened, at every text size a phone offers up to the largest. */
    private fun detailsWrapOnlyBetweenWords(scale: Float) {
        val vehicle = com.kramttocs.lubelink.core.Vehicle(com.kramttocs.lubelink.core.VehicleId(1), 2019,
            "Synthetic", "Wagon", "", useHours = false, details = com.kramttocs.lubelink.core.VehicleDetails("SYN-101",
                isDiesel = false, purchaseDate = java.time.LocalDate.of(2019, 3, 14), purchasePrice = java.math.BigDecimal(27450),
                soldDate = java.time.LocalDate.of(2026, 10, 1), soldPrice = java.math.BigDecimal(9000),
                tags = listOf("synthetic-family", "synthetic-commuter")))
        atScale(scale) { androidx.compose.foundation.layout.Column { com.kramttocs.lubelink.ui.VehicleDetailsBody(vehicle) } }
        val texts = listOf("License plate", "SYN-101", "Fuel", "Gasoline", "Odometer in", "Miles", "Purchased",
            "Mar 14, 2019 · \$27,450.00", "Sold", "Oct 1, 2026 · \$9,000.00", "Tags", "synthetic-family, synthetic-commuter")
        for (text in texts) {
            val layout = layoutOf(text)
            for (line in 0 until layout.lineCount - 1) {
                val end = layout.getLineEnd(line)
                assertTrue("\"$text\" at $scale is broken mid-word after \"${text.substring(0, end)}\"",
                    text[end - 1].isWhitespace() || text[end - 1] == '-' || text[end - 1] == ',')
            }
            assertFalse("\"$text\" at $scale is shortened", layout.isLineEllipsized(layout.lineCount - 1))
        }
    }

    @Test fun vehicleDetailsWrapOnlyBetweenWordsAtNormalText() = detailsWrapOnlyBetweenWords(1f)
    @Test fun vehicleDetailsWrapOnlyBetweenWordsAt115() = detailsWrapOnlyBetweenWords(1.15f)
    @Test fun vehicleDetailsWrapOnlyBetweenWordsAt130() = detailsWrapOnlyBetweenWords(1.3f)
    @Test fun vehicleDetailsWrapOnlyBetweenWordsAtTheLargestText() = detailsWrapOnlyBetweenWords(2f)
}
