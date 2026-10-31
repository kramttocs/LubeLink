package com.kramttocs.lubelink.quality

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.LubeLoggerEditorApi
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordWriteIntent
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.WriteAction
import com.kramttocs.lubelink.ui.LocalStationLookups
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import com.kramttocs.lubelink.ui.WriteSafety
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * At Android's largest text (font scale 2.0), every dialog body other than Delete's
 * (which `DialogLargeTextUiTest` covers), and the station picker's Nearby page, can bring each of its sentences fully
 * into view, and its buttons stay on screen. Each dialog is reached the way a user reaches it, in the whole app
 * against the synthetic garage server; the station lookups are answered by a synthetic fake. Runs on the JVM and the
 * emulator.
 */
@RunWith(AndroidJUnit4::class)
class DialogsLargeTextUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private val server = ScreenshotServer()
    private val driver = TourDriver(compose) {}
    private var originalScale: Float? = null

    private val lookups = ScreenshotGarage.stationLookups

    @Before fun connectToTheSyntheticGarage() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        ScreenshotGarage.preferNearbyList(context, server.baseUrl)
    }

    @After fun cleanUp() {
        originalScale?.let { setFontScale(it) }
        compose.runOnIdle { if (WriteSafety.state !is WriteSafety.State.Idle && WriteSafety.state !is WriteSafety.State.Pending)
            WriteSafety.acknowledge(context, checkedServer = true) }
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Idle }
        context.getSharedPreferences(StoredFormat.UNCONFIRMED_WRITE.fileName, Context.MODE_PRIVATE).edit().clear().commit()
        settings.removeGeoapifyKey()
        settings.removeManagerKey()
        settings.disconnect()
        server.close()
    }

    /** The activity's own font scale: dialogs are separate windows and take their text size from the configuration. */
    private fun setFontScale(scale: Float) = compose.runOnUiThread {
        val resources = compose.activity.resources
        originalScale = originalScale ?: resources.configuration.fontScale
        @Suppress("DEPRECATION")
        resources.updateConfiguration(Configuration(resources.configuration).apply { fontScale = scale },
            resources.displayMetrics)
    }

    private fun appAtLargestText() = appAt(2f)

    private fun appAt(scale: Float) {
        setFontScale(scale)
        compose.setContent {
            LubeTheme {
                CompositionLocalProvider(LocalStationLookups provides lookups) {
                    Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) }
                }
            }
        }
        driver.waitFor("2019 Honda CR-V")
    }

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    private fun openSettings(row: String) {
        driver.tap("Settings")
        driver.waitFor("Record Fields")
        scrollTo(row)
        driver.tap(row)
    }

    /** Each sentence can be brought into view (by scrolling, where the body scrolls) with none of it cut off by its
     * container or shortened by its own layout ("…"), and each button is on screen. */
    private fun assertReadable(texts: List<String>, buttons: List<String>) {
        texts.forEach { text ->
            driver.waitFor(text, substring = true)
            val found = compose.onAllNodes(hasText(text, substring = true), useUnmergedTree = true).onFirst()
            runCatching { found.performScrollTo() }
            val node = found.assertIsDisplayed().fetchSemanticsNode()
            assertEquals("Not at the largest text", 2f, node.layoutInfo.density.fontScale)
            assertEquals("\"$text\" is cut off", node.size.height.toFloat(), node.boundsInWindow.height, 1f)
            val layout = mutableListOf<TextLayoutResult>()
            node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layout)
            // Shortened = cut to fewer lines than it needs, with or without "…". (A glyph overhanging its box by a pixel,
            // which `hasVisualOverflow` also counts, is not shortening: the pictures of those texts show them whole.)
            layout.firstOrNull()?.let { assertFalse("\"$text\" is shortened", it.multiParagraph.didExceedMaxLines) }
        }
        buttons.forEach { compose.onAllNodes(hasText(it)).onFirst().assertIsDisplayed() }
    }

    @Test fun disconnect() {
        appAtLargestText()
        openSettings("Connection")
        scrollTo("Disconnect and change server/key")
        driver.tap("Disconnect and change server/key")
        assertReadable(listOf("This removes saved Editor and Manager keys", "Keep a copy of your API keys"),
            listOf("Cancel", "Disconnect"))
    }

    @Test fun editorKey() {
        appAtLargestText()
        openSettings("Connection")
        driver.tap("Replace Editor key")
        assertReadable(listOf("Enter one Editor API key", "Replacing the Editor key removes the Manager key"),
            listOf("Cancel", "Verify and save"))
    }

    @Test fun managerKey() {
        appAtLargestText()
        openSettings("Connection")
        scrollTo("Add Manager key")
        driver.tap("Add Manager key")
        assertReadable(listOf("Create a Manager key for the same LubeLogger user", "Authentication is checked now"),
            listOf("Cancel", "Verify and save"))
    }

    @Test fun removeManagerKey() {
        settings.saveManagerKey(ScreenshotGarage.MANAGER_KEY)
        appAtLargestText()
        openSettings("Connection")
        scrollTo("Remove Manager key")
        driver.tap("Remove Manager key")
        assertReadable(listOf("Delete will be hidden"), listOf("Cancel", "Remove key"))
    }

    @Test fun geoapifyKey() {
        appAtLargestText()
        openSettings("Gas station search")
        driver.tap("Add Geoapify key")
        assertReadable(listOf("Enter the Places API key", "Saving a key is optional"), listOf("Cancel", "Save key"))
    }

    @Test fun removeGeoapifyKey() {
        settings.saveGeoapifyKey("synthetic-geoapify")
        appAtLargestText()
        openSettings("Gas station search")
        scrollTo("Remove Geoapify key")
        driver.tap("Remove Geoapify key")
        assertReadable(listOf("Gas station searches will use public Overpass servers"), listOf("Cancel", "Remove"))
    }

    @Test fun vehicleNickname() {
        appAtLargestText()
        openSettings("Garage & personalization")
        scrollTo("Vehicle nicknames")
        driver.tap("Vehicle nicknames")
        driver.tap("2019 Honda CR-V")
        assertReadable(listOf("Nickname (max 48)", "Only shown in LubeLink on this device"), listOf("Cancel", "Save"))
    }

    private fun openAddService() {
        driver.tap("2019 Honda CR-V")
        driver.tap("Add record")
        driver.waitFor("Description")
    }

    @Test fun discardChanges() {
        appAtLargestText()
        openAddService()
        compose.onAllNodes(hasText("Notes") and hasSetTextAction()).onFirst().performTextInput("Synthetic note")
        driver.tap("Cancel")
        assertReadable(listOf("Anything entered in this form that has not been saved will be lost"),
            listOf("Keep editing", "Discard"))
    }

    /** OD-45: in the Add record form at the largest text, "Description" is whole with the favourite star and the ▾
     * arrow inside the field beside it, and both are on screen. */
    @Test fun descriptionFieldWithItsStar() {
        appAtLargestText()
        openAddService()
        assertReadable(listOf("Description"), emptyList())
        compose.onAllNodes(hasContentDescription("Save description as favorite")).onFirst().assertIsDisplayed()
        compose.onAllNodes(hasContentDescription("Choose a description")).onFirst().assertIsDisplayed()
    }

    /** OD-49: the vehicle details dialog, and History's reading line and reminders-due panel. */
    @Test fun vehicleDetailsAndTheHistoryPanel() {
        appAtLargestText()
        driver.tap("2019 Honda CR-V")
        driver.tapDescription("Vehicle details")
        assertReadable(listOf("License plate", "Mar 14, 2019 · \$27,450.00", "family, commuter",
            "From LubeLogger; edit them there."), listOf("Close"))
        driver.tap("Close")
        driver.tap("History")
        assertReadable(listOf("48,210 mi", "1 reminder due", "Tire rotation"), listOf("Reminders"))
    }

    @Test fun descriptionPicker() {
        appAtLargestText()
        openAddService()
        driver.tapDescription("Choose a description")
        assertReadable(listOf("Search or enter new", "Gold stars are favorites saved on this phone"), listOf("Cancel"))
    }

    @Test fun writeLock() {
        appAtLargestText()
        val reason = "Edit acknowledged, but LubeLink could not read the saved record back. Verify in LubeLogger."
        compose.runOnIdle {
            WriteSafety.start(context, "Edit Service for synthetic car", RecordWriteIntent(WriteAction.EDIT, RecordKind.SERVICE)) {
                throw LubeLoggerEditorApi.WriteUncertainException(reason)
            }
        }
        assertReadable(listOf(reason, "Check this record in the LubeLogger web interface",
            "I checked LubeLogger and reconciled this write"), listOf("Unlock after verification"))
    }

    private fun openStationPicker() {
        driver.tap("2019 Honda CR-V")
        driver.tap("History")
        driver.tap("Fuel")
        driver.tap("Add record")
        driver.waitFor("Gas station (optional)")
        driver.tapDescription("Find or choose gas station")
        driver.waitFor("Manual")
    }

    @Test fun stationPickerSavedAndManual() {
        appAtLargestText()
        openStationPicker()
        assertReadable(listOf("No favorites yet"), listOf("Cancel", "Enter manually"))
        driver.tap("Manual")
        assertReadable(listOf("Station name", "One-time fuel stops aren't saved unless you explicitly favorite them",
            "Use current address"), listOf("Cancel", "Use station"))
    }

    private fun openNearbyList() {
        openStationPicker()
        driver.tap("Nearby")
        driver.waitFor("Synthetic Corner Garage")
    }

    @Test fun nearbyPage() {
        appAtLargestText()
        openNearbyList()
        assertReadable(listOf("Nearby gas stations", "2 stations were left out", "Synthetic Fuel Stop With A Long Name",
            "Synthetic Corner Garage", "Near me"), listOf("Map", "List", "Manual"))
        compose.onAllNodes(hasContentDescription("Back to station picker")).onFirst().assertIsDisplayed()
    }

    @Test fun favoriteStation() {
        appAtLargestText()
        openNearbyList()
        compose.onAllNodes(hasContentDescription("Save as favorite")).onFirst().performClick()
        assertReadable(listOf("This changes only the local favorite and future selections"),
            listOf("Cancel", "Save favorite"))
    }

    /** OD-50: the favourite-station dialog's Name and Address fill the dialog's width: they start where its text
     * starts and leave the same margin on the right as on the left, at normal and the largest text. */
    private fun favoriteStationFieldsFillTheDialogAt(scale: Float) {
        appAt(scale)
        openNearbyList()
        compose.onAllNodes(hasContentDescription("Save as favorite")).onFirst().performClick()
        driver.waitFor("Save favorite station")
        val text = compose.onNode(hasText("This changes only the local favorite", substring = true))
            .fetchSemanticsNode().boundsInWindow
        // Three dialogs are open (the record form, the station picker, this one): this one is the smallest holding its text.
        val dialog = compose.onAllNodes(isDialog()).fetchSemanticsNodes().map { it.boundsInWindow }
            .filter { it.contains(text.center) }.minBy { it.width * it.height }
        for (label in listOf("Name", "Address")) {
            val field = compose.onNode(hasText(label) and hasSetTextAction()).fetchSemanticsNode().boundsInWindow
            assertEquals("$label at $scale starts where the dialog's text starts", text.left, field.left, 1f)
            assertEquals("$label at $scale leaves the same margin on both sides",
                field.left - dialog.left, dialog.right - field.right, 1f)
        }
    }

    @Test fun favoriteStationFieldsFillTheDialogAtNormalText() = favoriteStationFieldsFillTheDialogAt(1f)
    @Test fun favoriteStationFieldsFillTheDialogAtTheLargestText() = favoriteStationFieldsFillTheDialogAt(2f)

    @Test fun suggestedStationAddress() {
        settings.saveGeoapifyKey("synthetic-geoapify")
        appAtLargestText()
        openNearbyList()
        driver.tap("Synthetic Corner Garage")
        compose.onAllNodes(hasText("Find missing street address")).onFirst().assertIsDisplayed()
        driver.tap("Find missing street address")
        assertReadable(listOf("This is a nearby mapped address and may belong to a neighboring property",
            "3 Synthetic Avenue"), listOf("Use this address", "Keep original"))
    }
}
