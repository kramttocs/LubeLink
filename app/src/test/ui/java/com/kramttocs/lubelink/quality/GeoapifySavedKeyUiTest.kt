package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.KeyChecks
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LocalKeyChecks
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings → Gas station search → Test connection checks the saved Geoapify key, and its result belongs to
 * that key and that page. Replacing or removing the key, or leaving the page, while a test runs cancels it, and its
 * reply (even one that arrives anyway) is never shown; a new test checks the current key. Whole app against the
 * synthetic garage server, with the Geoapify check answered by hand. Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class GeoapifySavedKeyUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private val server = ScreenshotServer()
    private val driver = TourDriver(compose) {}

    /** Only the Geoapify test is used here; [pastCancel] makes it ignore cancellation, as a late reply would. */
    private class Checks(val pastCancel: Boolean) : KeyChecks {
        val geoapify = HeldCalls<Unit>()
        override suspend fun connect(server: String, key: String): String = error("not used")
        override suspend fun verifyEditorKey(server: String, key: String) = error("not used")
        override suspend fun verifyManagerKey(server: String, key: String, editorKey: String) = error("not used")
        override suspend fun testGeoapifyKey(key: String) =
            if (pastCancel) geoapify.holdPastCancel(key) else geoapify.hold(key)
    }

    private val success = "Connection successful"
    private val failure = "Connection failed"

    @Before fun connectToTheSyntheticGarage() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        settings.saveGeoapifyKey("synthetic-geoapify-a")
    }

    @After fun cleanUp() {
        settings.removeGeoapifyKey()
        settings.disconnect()
        server.close()
    }

    private fun openGasStationSearch(checks: Checks) {
        compose.setContent {
            LubeTheme {
                CompositionLocalProvider(LocalKeyChecks provides checks) {
                    Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) }
                }
            }
        }
        driver.waitFor("2019 Honda CR-V")
        driver.tap("Settings")
        driver.waitFor("Record Fields")
        scrollTo("Gas station search")
        driver.tap("Gas station search")
        driver.waitFor("Test connection")
    }

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** [awaitCondition] with a reason that is readable without the stack trace. */
    private fun awaitOrFail(reason: String, condition: () -> Boolean) =
        runCatching { compose.awaitCondition(condition = condition) }.onFailure { throw AssertionError(reason, it) }

    private fun startTest(checks: Checks, count: Int) {
        driver.tap("Test connection")
        compose.awaitCondition { checks.geoapify.calls.size == count }
    }

    @Test fun aTestOfAReplacedKeyIsCancelledAndNeverShown() {
        val checks = Checks(pastCancel = false)
        openGasStationSearch(checks)
        startTest(checks, 1)
        assertEquals("synthetic-geoapify-a", checks.geoapify.last.argument)
        driver.tap("Replace Geoapify key")
        compose.onNode(hasText("Geoapify API key") and hasSetTextAction()).performTextInput("synthetic-geoapify-b")
        driver.tap("Save key")
        awaitOrFail("Saving key B did not cancel the running test of key A") { checks.geoapify[0].cancelled }
        assertEquals("synthetic-geoapify-b", settings.readGeoapifyKey())
        checks.geoapify[0].succeed(Unit)
        compose.waitForIdle()
        assertFalse("Key A's result is not shown for key B", shows(success))
        // A new test checks the key that is saved now, and its result is shown.
        startTest(checks, 2)
        assertEquals("synthetic-geoapify-b", checks.geoapify.last.argument)
        checks.geoapify.last.succeed(Unit)
        compose.awaitCondition { shows(success) }
    }

    @Test fun aLateReplyForAReplacedKeyIsNeverShown() {
        val checks = Checks(pastCancel = true)
        openGasStationSearch(checks)
        startTest(checks, 1)
        driver.tap("Replace Geoapify key")
        compose.onNode(hasText("Geoapify API key") and hasSetTextAction()).performTextInput("synthetic-geoapify-b")
        driver.tap("Save key")
        awaitOrFail("After saving key B the page still shows key A's test running") { shows("Test connection") }
        checks.geoapify[0].fail(IllegalStateException("Synthetic: key A refused"))
        compose.waitForIdle()
        assertFalse("Key A's failure is not shown for key B", shows(failure))
        assertTrue("The test button is ready again", shows("Test connection"))
    }

    @Test fun removingTheKeyCancelsItsTest() {
        val checks = Checks(pastCancel = true)
        openGasStationSearch(checks)
        startTest(checks, 1)
        scrollTo("Remove Geoapify key")
        driver.tap("Remove Geoapify key")
        driver.tap("Remove")
        driver.waitFor("No Geoapify key configured", substring = true)
        // Another key is added before key A's reply arrives: the reply belongs to the removed key, not this one.
        driver.tap("Add Geoapify key")
        compose.onNode(hasText("Geoapify API key") and hasSetTextAction()).performTextInput("synthetic-geoapify-c")
        driver.tap("Save key")
        awaitOrFail("After adding key C the page still shows key A's test running") { shows("Test connection") }
        checks.geoapify[0].succeed(Unit)
        compose.waitForIdle()
        assertFalse("The removed key's result is not shown for key C", shows(success))
    }

    @Test fun aFinishedResultIsClearedWhenTheKeyChangesOrThePageIsLeft() {
        val checks = Checks(pastCancel = false)
        openGasStationSearch(checks)
        startTest(checks, 1)
        checks.geoapify.last.succeed(Unit)
        compose.awaitCondition { shows(success) }
        driver.tap("Replace Geoapify key")
        compose.onNode(hasText("Geoapify API key") and hasSetTextAction()).performTextInput("synthetic-geoapify-b")
        driver.tap("Save key")
        awaitOrFail("Key A's finished result still shows after key B was saved") { !shows(success) }
        startTest(checks, 2)
        checks.geoapify.last.fail(IllegalStateException("Synthetic: key B refused"))
        compose.awaitCondition { shows(failure) }
        driver.tapDescription("Back")
        driver.waitFor("Record Fields")
        scrollTo("Gas station search")
        driver.tap("Gas station search")
        driver.waitFor("Test connection")
        assertFalse("The earlier visit's result is not shown on a new visit", shows(failure))
    }

    @Test fun leavingThePageCancelsItsTest() {
        val checks = Checks(pastCancel = false)
        openGasStationSearch(checks)
        startTest(checks, 1)
        driver.tapDescription("Back")
        driver.waitFor("Record Fields")
        awaitOrFail("Leaving the page did not cancel its running test") { checks.geoapify[0].cancelled }
        checks.geoapify[0].succeed(Unit)
        compose.waitForIdle()
        scrollTo("Gas station search")
        driver.tap("Gas station search")
        awaitOrFail("Back on the page, the earlier visit's test still shows as running") { shows("Test connection") }
        assertFalse("A test from the earlier visit is not shown", shows(success))
    }
}
