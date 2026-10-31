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

/** At the Editor key dialog: when LubeLogger's account check names nobody, "Verify and save" shows an error and
 * keeps the old key. Runs on the JVM and the emulator (on the JVM through JvmDialogWindowShadow). */
@RunWith(AndroidJUnit4::class)
class EditorKeyIdentityTest {
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

    @Test fun replacingTheEditorKeyWithAnAccountCheckThatNamesNobodyKeepsTheOldKey() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        app()
        driver.waitFor("2019 Honda CR-V")
        blankAccount = true
        driver.tap("Settings")
        driver.tap("Connection")
        driver.tap("Replace Editor key")
        type("Editor API key", ScreenshotGarage.MANAGER_KEY)
        driver.tap("Verify and save")
        driver.waitFor("could not read the account check", substring = true)
        compose.runOnIdle { assertEquals("The old key is kept", ScreenshotGarage.EDITOR_KEY, settings.readApiKey()) }
    }
}
