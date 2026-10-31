package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole [LubeLinkApp] with a stored key whose Garage load fails. The
 * connection-problem screen must offer Retry and a way to Disconnect and connect again.
 *
 * The server is `lubelogger.invalid` (a reserved name that never resolves), so nothing leaves the
 * emulator. A rejected key (HTTP 401) reaches the same screen through the same `loadError` gate;
 * the screen does not depend on the cause. Fresh disposable emulator only.
 */
@RunWith(AndroidJUnit4::class)
class ConnectionRecoveryUiTest {
    @get:Rule val compose = createComposeRule()
    private val settings = SecureConfig(ApplicationProvider.getApplicationContext<Context>())

    @Before fun storeAConnectionThatCannotLoad() {
        settings.baseUrl = "https://lubelogger.invalid"
        settings.saveApiKey("synthetic-unusable-key")
    }

    @After fun cleanUp() {
        settings.disconnect()
        settings.baseUrl = ""
    }

    private fun awaitText(text: String) = compose.awaitCondition(30_000) {
        compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun aConnectionThatCannotLoadCanStillBeDisconnected() {
        compose.setContent { LubeLinkApp(settings) }
        awaitText("Connection problem")
        // Retry repeats the load and lands on the same screen; it is not the only way out any more.
        compose.onNode(hasText("Retry") and hasClickAction()).performClick()
        awaitText("Connection problem")
        compose.onNode(hasText("Disconnect and change server/key") and hasClickAction()).performClick()
        compose.onNodeWithText("Disconnect from LubeLogger?").assertExists()
        compose.onNode(hasText("Disconnect") and hasClickAction()).performClick()
        awaitText("Connect to your LubeLogger server")
        compose.runOnIdle { assertNull("The stored key must be removed", settings.readApiKey()) }
    }

    @Test fun cancellingTheConfirmationKeepsTheConnection() {
        compose.setContent { LubeLinkApp(settings) }
        awaitText("Connection problem")
        compose.onNode(hasText("Disconnect and change server/key") and hasClickAction()).performClick()
        compose.onNode(hasText("Cancel") and hasClickAction()).performClick()
        compose.onNodeWithText("Disconnect from LubeLogger?").assertDoesNotExist()
        compose.onNodeWithText("Connection problem").assertExists()
        compose.runOnIdle { assertEquals("synthetic-unusable-key", settings.readApiKey()) }
    }
}
