package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.KeyChecks
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.EditorKeyDialog
import com.kramttocs.lubelink.ui.GeoapifyKeyDialog
import com.kramttocs.lubelink.ui.LocalKeyChecks
import com.kramttocs.lubelink.ui.LubeTheme
import com.kramttocs.lubelink.ui.ManagerKeyDialog
import com.kramttocs.lubelink.ui.SetupScreen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-42: Setup's Connect and the Settings key dialogs check keys through [KeyChecks], which
 * these tests answer by hand. A failed check shows its reason and saves nothing; a check made stale by an edited key
 * is cancelled and never saves, even when its reply arrives anyway; closing the dialog cancels the check; and a
 * current success saves exactly the key that was checked. Synthetic keys; nothing leaves the test device. Runs on the
 * JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class KeyCheckUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private val server = "https://lubelogger.invalid"

    /** Each check waits for the test; [pastCancel] makes the checks ignore cancellation, as a late reply would. */
    private inner class Checks(val pastCancel: Boolean = false) : KeyChecks {
        val connects = HeldCalls<String>()
        val units = HeldCalls<Unit>()
        override suspend fun connect(server: String, key: String) =
            if (pastCancel) connects.holdPastCancel(key) else connects.hold(key)
        override suspend fun verifyEditorKey(server: String, key: String) = held(key)
        override suspend fun verifyManagerKey(server: String, key: String, editorKey: String) = held(key)
        override suspend fun testGeoapifyKey(key: String) = held(key)
        private suspend fun held(key: String) = if (pastCancel) units.holdPastCancel(key) else units.hold(key)
    }

    private var saved = 0
    private var shown by mutableStateOf(true)

    /** Settings start empty: `disconnect()` keeps the server address, and on the emulator an earlier test's address
     * would otherwise fill Setup's server field, so the typed address would be appended to it. */
    @Before fun startDisconnected() { ScreenshotGarage.startFresh(context); settings.disconnect() }
    @After fun cleanUp() { settings.removeGeoapifyKey(); settings.disconnect() }

    private fun show(checks: KeyChecks, content: @Composable () -> Unit) = compose.setContent {
        LubeTheme { CompositionLocalProvider(LocalKeyChecks provides checks) { if (shown) content() } }
    }

    private fun type(label: String, text: String) =
        compose.onNode(hasText(label) and hasSetTextAction()).performTextInput(text)

    private fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    private fun <T> awaitCalls(calls: HeldCalls<T>, count: Int) = compose.awaitCondition { calls.calls.size == count }

    private fun connectedAsEditor() {
        settings.baseUrl = server
        settings.saveApiKey("synthetic-editor-key")
    }

    // Setup's Connect

    @Test fun setupShowsWhyTheConnectionFailedAndSavesNothing() {
        val checks = Checks()
        show(checks) { SetupScreen(settings) { saved++ } }
        type("HTTPS server", server)
        type("Editor API key", "synthetic-key")
        compose.onNodeWithText("Connect").performClick()
        awaitCalls(checks.connects, 1)
        assertTrue(shows("Testing connection…"))
        checks.connects.last.fail(LubeLoggerApi.ApiException("Synthetic refusal from the server"))
        compose.awaitCondition { shows("Synthetic refusal from the server") }
        compose.onNodeWithText("Connect").assertIsEnabled()
        assertNull("No key was saved", settings.readApiKey())
        assertEquals(0, saved)
    }

    @Test fun setupNeverConnectsWithAKeyThatWasEditedWhileItWasChecked() {
        val checks = Checks(pastCancel = true)
        show(checks) { SetupScreen(settings) { saved++ } }
        type("HTTPS server", server)
        type("Editor API key", "synthetic-key")
        compose.onNodeWithText("Connect").performClick()
        awaitCalls(checks.connects, 1)
        type("Editor API key", "-2")
        compose.awaitCondition { shows("Connect") && !shows("Testing connection…") }
        checks.connects.last.succeed("synthetic-user")
        compose.waitForIdle()
        assertEquals("The stale check did not connect", 0, saved)
        assertNull("The stale check saved no key", settings.readApiKey())
        compose.onNodeWithText("Connect").performClick()
        awaitCalls(checks.connects, 2)
        assertEquals("The current key is the one checked", "synthetic-key-2", checks.connects.last.argument)
        checks.connects.last.succeed("synthetic-user")
        compose.awaitCondition { saved == 1 }
        assertEquals("synthetic-key-2", settings.readApiKey())
        assertEquals(server, settings.baseUrl)
    }

    @Test fun leavingSetupCancelsTheConnectionCheck() {
        val checks = Checks()
        show(checks) { SetupScreen(settings) { saved++ } }
        type("HTTPS server", server)
        type("Editor API key", "synthetic-key")
        compose.onNodeWithText("Connect").performClick()
        awaitCalls(checks.connects, 1)
        shown = false
        compose.awaitCondition { checks.connects.last.cancelled }
        assertNull(settings.readApiKey())
    }

    // Settings → Editor API key

    @Test fun editorKeyFailureShowsTheReasonAndKeepsTheOldKey() {
        connectedAsEditor()
        val checks = Checks()
        show(checks) { EditorKeyDialog(settings, onSaved = { saved++ }, onDismiss = {}) }
        type("Editor API key", "synthetic-replacement")
        compose.onNodeWithText("Verify and save").performClick()
        awaitCalls(checks.units, 1)
        assertTrue(shows("Checking…"))
        checks.units.last.fail(LubeLoggerApi.ApiException("Synthetic key refused"))
        compose.awaitCondition { shows("Synthetic key refused") }
        compose.onNodeWithText("Verify and save").assertIsEnabled()
        assertEquals("synthetic-editor-key", settings.readApiKey())
        assertEquals(0, saved)
    }

    @Test fun editorKeyEditedWhileCheckingIsNeverSaved() {
        connectedAsEditor()
        val checks = Checks(pastCancel = true)
        show(checks) { EditorKeyDialog(settings, onSaved = { saved++ }, onDismiss = {}) }
        type("Editor API key", "synthetic-replacement")
        compose.onNodeWithText("Verify and save").performClick()
        awaitCalls(checks.units, 1)
        type("Editor API key", "-2")
        compose.awaitCondition { !shows("Checking…") }
        checks.units.last.succeed(Unit)
        compose.waitForIdle()
        assertEquals("The stale check saved nothing", "synthetic-editor-key", settings.readApiKey())
        assertEquals(0, saved)
        compose.onNodeWithText("Verify and save").performClick()
        awaitCalls(checks.units, 2)
        checks.units.last.succeed(Unit)
        compose.awaitCondition { saved == 1 }
        assertEquals("The key checked is the key saved", "synthetic-replacement-2", settings.readApiKey())
    }

    @Test fun closingTheEditorKeyDialogCancelsItsCheck() {
        connectedAsEditor()
        val checks = Checks()
        show(checks) { EditorKeyDialog(settings, onSaved = { saved++ }, onDismiss = {}) }
        type("Editor API key", "synthetic-replacement")
        compose.onNodeWithText("Verify and save").performClick()
        awaitCalls(checks.units, 1)
        shown = false
        compose.awaitCondition { checks.units.last.cancelled }
        assertEquals("synthetic-editor-key", settings.readApiKey())
        assertEquals(0, saved)
    }

    // Settings → Manager API key

    @Test fun managerKeyFailureShowsTheReasonAndSavesNothing() {
        connectedAsEditor()
        val checks = Checks()
        show(checks) { ManagerKeyDialog(settings, onSaved = { saved++ }, onDismiss = {}) }
        type("Manager API key", "synthetic-manager")
        compose.onNodeWithText("Verify and save").performClick()
        awaitCalls(checks.units, 1)
        checks.units.last.fail(LubeLoggerApi.ApiException("Synthetic: a different LubeLogger user"))
        compose.awaitCondition { shows("Synthetic: a different LubeLogger user") }
        assertNull(settings.readManagerKey())
        assertEquals(0, saved)
    }

    @Test fun managerKeyEditedWhileCheckingIsNeverSaved() {
        connectedAsEditor()
        val checks = Checks(pastCancel = true)
        show(checks) { ManagerKeyDialog(settings, onSaved = { saved++ }, onDismiss = {}) }
        type("Manager API key", "synthetic-manager")
        compose.onNodeWithText("Verify and save").performClick()
        awaitCalls(checks.units, 1)
        type("Manager API key", "-2")
        compose.awaitCondition { !shows("Checking…") }
        checks.units.last.succeed(Unit)
        compose.waitForIdle()
        assertNull("The stale check saved nothing", settings.readManagerKey())
        assertEquals(0, saved)
    }

    // Settings → Geoapify API key

    @Test fun geoapifyTestFailureIsShown() {
        val checks = Checks()
        show(checks) { GeoapifyKeyDialog(settings, onSaved = { saved++ }, onDismiss = {}) }
        type("Geoapify API key", "synthetic-geoapify")
        compose.onNodeWithText("Test connection").performClick()
        awaitCalls(checks.units, 1)
        checks.units.last.fail(IllegalStateException("synthetic"))
        compose.awaitCondition { shows("Geoapify verification failed") }
        compose.onNodeWithText("Test connection").assertIsEnabled()
    }

    @Test fun aGeoapifyTestOfAnEditedKeyNeverReportsSuccess() {
        val checks = Checks(pastCancel = true)
        show(checks) { GeoapifyKeyDialog(settings, onSaved = { saved++ }, onDismiss = {}) }
        type("Geoapify API key", "synthetic-geoapify")
        compose.onNodeWithText("Test connection").performClick()
        awaitCalls(checks.units, 1)
        type("Geoapify API key", "-2")
        compose.awaitCondition { shows("Test connection") }
        checks.units.last.succeed(Unit)
        compose.waitForIdle()
        assertFalse("The edited key is not reported as working", shows("Connection successful"))
        assertNull(settings.readGeoapifyKey())
    }
}
