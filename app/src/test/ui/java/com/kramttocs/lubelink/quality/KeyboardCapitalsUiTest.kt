package com.kramttocs.lubelink.quality

import android.content.Context
import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.runtime.mutableIntStateOf
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LocalStationLookups
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-67: in every free-text field the keyboard starts each sentence with a capital letter: the record form's Notes,
 * Description, the description list's "Search or enter new", the gas station name and address; the station picker's
 * manual name and address and a favourite's name and address; a vehicle's nickname. The station search keeps the
 * keyboard as it is. Each field is tapped the way a user reaches it, in the whole app against the synthetic garage
 * server, and the keyboard the field asks Android for is read from the focused view. Runs on the JVM and the emulator.
 */
@OptIn(ExperimentalComposeUiApi::class)
@RunWith(AndroidJUnit4::class)
class KeyboardCapitalsUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private val server = ScreenshotServer()
    private val driver = TourDriver(compose) {}
    private val requestedInputType = mutableIntStateOf(-1)

    @Before fun connectToTheSyntheticGarage() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        ScreenshotGarage.preferNearbyList(context, server.baseUrl)
        compose.setContent {
            LubeTheme {
                CompositionLocalProvider(LocalStationLookups provides ScreenshotGarage.stationLookups) {
                    // Records the keyboard each text field asks Android for when it starts taking input, then lets
                    // the input start as usual. Dialogs are composed inside the app, so their fields pass through too.
                    InterceptPlatformTextInput(interceptor = { request, next ->
                        requestedInputType.intValue = EditorInfo().also { request.createInputConnection(it) }.inputType
                        next.startInputMethod(request)
                    }) {
                        Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) }
                    }
                }
            }
        }
        driver.waitFor("2019 Honda CR-V")
    }

    @After fun cleanUp() {
        settings.disconnect()
        server.close()
    }

    /** Taps the text field labelled [label] and returns the keyboard type it asks Android for. */
    private fun keyboardAfterTapping(label: String): Int {
        driver.waitFor(label)
        compose.runOnIdle { requestedInputType.intValue = -1 }
        compose.onAllNodes(hasText(label) and hasSetTextAction()).onFirst().performClick()
        compose.waitUntil(5_000) { requestedInputType.intValue != -1 }
        return requestedInputType.intValue
    }

    private fun assertCapitals(label: String) {
        val type = keyboardAfterTapping(label)
        assertTrue("\"$label\" starts each sentence with a capital (input type 0x${type.toString(16)})",
            type and InputType.TYPE_TEXT_FLAG_CAP_SENTENCES != 0)
    }

    private fun assertAsTyped(label: String) {
        val type = keyboardAfterTapping(label)
        val capitals = InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_CAP_WORDS or
            InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        assertTrue("\"$label\" keeps the keyboard as it is (input type 0x${type.toString(16)})", type and capitals == 0)
    }

    @Test fun theRecordFormsNotesAndDescription() {
        driver.tap("2019 Honda CR-V")
        driver.tap("Add record")
        assertCapitals("Notes")
        assertCapitals("Description")
        driver.tapDescription("Choose a description")
        assertCapitals("Search or enter new")
    }

    @Test fun theGasStationFieldsAndThePicker() {
        driver.tap("2019 Honda CR-V")
        driver.tap("History")
        driver.tap("Fuel")
        driver.tap("Add record")
        assertCapitals("Gas station (optional)")
        driver.tap("+ Add station address (optional)")
        assertCapitals("Station address (optional)")
        driver.tapDescription("Find or choose gas station")
        driver.tap("Manual")
        assertCapitals("Station name")
        assertCapitals("Address")
    }

    @Test fun theNearbySearchAndAFavourite() {
        driver.tap("2019 Honda CR-V")
        driver.tap("History")
        driver.tap("Fuel")
        driver.tap("Add record")
        driver.tapDescription("Find or choose gas station")
        driver.tap("Nearby")
        driver.waitFor("Synthetic Corner Garage")
        assertAsTyped("Search stations")
        compose.onAllNodes(hasContentDescription("Save as favorite")).onFirst().performClick()
        driver.waitFor("Save favorite station")
        assertCapitals("Name")
        assertCapitals("Address")
    }

    @Test fun aVehicleNickname() {
        driver.tap("Settings")
        driver.waitFor("Record Fields")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Garage & personalization"))
        driver.tap("Garage & personalization")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Vehicle nicknames"))
        driver.tap("Vehicle nicknames")
        driver.tap("2019 Honda CR-V")
        assertCapitals("Nickname (max 48)")
    }
}
