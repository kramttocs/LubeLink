package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Garage reports screen in the whole app, against [ScreenshotGarage]'s server: once a report is shown, "Calculating
 * report…" has gone. The JVM screen tour found both on screen at once. Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class ReportsUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    // One service description and one station name ~600,000 characters long (synthetic).
    private val hugeDescription = "Huge synthetic description ".repeat(22_500)
    private val hugeStation = "Huge synthetic station ".repeat(26_000)
    @Volatile private var huge = false
    private val server = ScreenshotServer(override = { path ->
        if (!huge) null else ScreenshotGarage.answer(path)
            ?.replace("Oil and filter change", hugeDescription)?.replace("Corner Fuel & Wash", hugeStation)
    })
    private val driver = TourDriver(compose) {}

    @After fun cleanUp() {
        settings.disconnect()
        settings.removeManagerKey()
        server.close()
    }

    @Test fun aFinishedReportNoLongerSaysCalculating() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        settings.saveManagerKey(ScreenshotGarage.MANAGER_KEY)
        compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }
        driver.waitFor("2019 Honda CR-V")
        ScreenshotGarage.cars.forEach { driver.tryWaitForDescription("${it.year} ${it.make} ${it.model} picture") }
        driver.tapDescription("Garage reports")
        driver.waitFor("Total recorded expenses")
        passTime(1_000)
        assertTrue("A finished report must not also say it is calculating",
            compose.onAllNodes(hasText("Calculating", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    /** Every visible text node that shows part of [prefix] is cut to the display bound and lays out in a few lines. */
    private fun assertBounded(prefix: String, where: String) {
        val nodes = compose.onAllNodes(hasText(prefix, substring = true), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("$where: no text shows \"$prefix\"", nodes.isNotEmpty())
        for (node in nodes) {
            val text = node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: continue
            assertTrue("$where: ${text.length} characters laid out", text.length <= 320)
            val layouts = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
            layouts.firstOrNull()?.let { assertTrue("$where: ${it.lineCount} lines", it.lineCount <= 3) }
        }
    }

    /** Scrolls the report list (the outermost scrolling list) until a node showing [text] is composed. */
    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text, substring = true))
        compose.waitForIdle()
    }

    @Test fun hugeServerTextIsShownBoundedInReports() {
        huge = true
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }
        driver.waitFor("2019 Honda CR-V")
        driver.tapDescription("Garage reports")
        driver.waitFor("Total recorded expenses")
        driver.tap("Maintenance")
        driver.waitFor("By work type")
        scrollTo("Huge synthetic description")
        assertBounded("Huge synthetic description", "Recent maintenance")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToIndex(0)
        driver.tap("Fuel")
        driver.waitFor("Fuel spending by month")
        scrollTo("Huge synthetic station")
        assertBounded("Huge synthetic station", "Station bar")
        compose.onAllNodes(hasText("Huge synthetic station", substring = true)).onFirst().performClick()
        driver.waitFor("Huge synthetic station", substring = true)
        compose.waitForIdle()
        assertBounded("Huge synthetic station", "Station drill-down")
        assertFalse("The drill-down shows its records", compose.onAllNodes(hasText("Close")).fetchSemanticsNodes().isEmpty() &&
            compose.onAllNodes(hasText("No records for this selection.")).fetchSemanticsNodes().isNotEmpty())
    }
}
