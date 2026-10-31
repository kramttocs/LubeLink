package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** At the real screens: when LubeLogger's account check names nobody (a blank user name), Setup's Connect and
 * Settings' "Verify and save" show an error and save nothing. Runs on the JVM and the emulator; synthetic server. */
@RunWith(AndroidJUnit4::class)
class AccountIdentityUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    @Volatile private var blankAccount = false
    private val server = ScreenshotServer(override = { path ->
        if (blankAccount && path.startsWith("/api/whoami")) """{"username":"","emailAddress":"","isAdmin":false,"isRoot":false}""" else null
    })
    private val driver = TourDriver(compose) {}

    @After fun cleanUp() {
        settings.disconnect()
        settings.removeManagerKey()
        server.close()
    }

    private fun app() = compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }

    private fun type(field: String, text: String) =
        compose.onAllNodes(hasText(field) and hasSetTextAction()).onFirst().performTextInput(text)

    @Test fun setupWithAnAccountCheckThatNamesNobodySavesNothing() {
        ScreenshotGarage.startFresh(context)
        blankAccount = true
        app()
        driver.waitFor("Connect")
        type("HTTPS server", server.baseUrl)
        type("Editor API key", ScreenshotGarage.EDITOR_KEY)
        driver.tap("Connect")
        driver.waitFor("could not read the account check", substring = true)
        compose.runOnIdle {
            assertEquals("No server address is saved", "", settings.baseUrl)
            assertNull("No key is saved", settings.readApiKey())
        }
    }
    // The Editor key dialog's case is in androidTest (EditorKeyIdentityTest): a text field in a dialog never becomes
    // idle under Robolectric.
}
