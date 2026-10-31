package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Scrolled to the end, the last card of a screen sits clear of the bottom
 * navigation bar, with space after it, rather than meeting it. The whole app at the largest text, so the Reminders
 * list must scroll. Runs on the JVM and the emulator; synthetic server. */
@RunWith(AndroidJUnit4::class)
class ScreenEndSpacingUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private val server = ScreenshotServer()
    private val driver = TourDriver(compose) {}

    @After fun cleanUp() {
        settings.disconnect()
        settings.removeManagerKey()
        server.close()
    }

    @Test fun theLastReminderEndsClearOfTheBottomBar() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } }
            }
        }
        driver.waitFor("2019 Honda CR-V")
        driver.tap("Reminders")
        driver.waitFor("Tire rotation")
        val list = compose.onNode(hasScrollToIndexAction())
        repeat(8) { list.performTouchInput { swipeUp() }; compose.waitForIdle() }
        val node = list.fetchSemanticsNode()
        val listBottom = node.boundsInRoot.bottom
        val lastCardBottom = node.children.maxOf { it.boundsInRoot.bottom }
        val gapDp = (listBottom - lastCardBottom) / node.layoutInfo.density.density
        assertTrue("The list must be long enough to scroll", node.children.size >= 2)
        assertTrue("The last card must end at least 16 dp above the bar; it ends $gapDp dp above", gapDp >= 16f)
    }
}
