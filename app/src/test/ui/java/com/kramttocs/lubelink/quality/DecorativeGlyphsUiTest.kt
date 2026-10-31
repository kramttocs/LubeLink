package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The decorative glyphs (a radio row's ● / ○, a record's ● ◆ ▲ ◉ beside its kind) are not
 * part of what TalkBack reads. A radio row reads its label once, with its role and selected state; a record row reads
 * its words. Whole app against the synthetic garage server. Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class DecorativeGlyphsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private val server = ScreenshotServer()
    private val driver = TourDriver(compose) {}
    private val glyphs = listOf("●", "○", "◆", "▲", "◉")

    @Before fun connect() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }
        driver.waitFor("2019 Honda CR-V")
    }

    @After fun cleanUp() { settings.disconnect(); server.close() }

    /** Everything the node TalkBack lands on reads out: its merged text. */
    private fun readOut(label: String) =
        compose.onAllNodes(hasText(label, substring = true)).onFirst().fetchSemanticsNode().config.let { config ->
            Triple(config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text },
                config.getOrNull(SemanticsProperties.Role), config.getOrNull(SemanticsProperties.Selected))
        }

    @Test fun aRadioRowReadsItsLabelAndStateButNotItsDot() {
        driver.tap("Settings")
        driver.waitFor("Record Fields")
        driver.tap("Garage & personalization")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Last used vehicle"))
        val (texts, role, selected) = readOut("Last used vehicle")
        assertEquals("Read once, without the dot", listOf("Last used vehicle"), texts)
        assertEquals(Role.RadioButton, role)
        assertTrue("Its state is read as selected or not", selected != null)
    }

    @Test fun aRecordRowReadsItsWordsButNotItsShape() {
        driver.tap("2019 Honda CR-V")
        driver.tap("History")
        driver.waitFor("Oil and filter change")
        val (texts, _, _) = readOut("Oil and filter change")
        assertTrue("The record is read: $texts", texts.any { it.contains("Oil and filter change") })
        assertTrue("No shape glyph is read: $texts", texts.none { it in glyphs })
    }
}
