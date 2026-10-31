package com.kramttocs.lubelink.quality

import android.content.Context
import android.location.Location
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onFirst
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.FuelStation
import com.kramttocs.lubelink.core.FuelStationLookup
import com.kramttocs.lubelink.core.StationBounds
import com.kramttocs.lubelink.core.StationLookups
import com.kramttocs.lubelink.core.StationSearchProvider
import com.kramttocs.lubelink.ui.FuelStationPickerDialog
import com.kramttocs.lubelink.ui.LocalStationLookups
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-42: the station picker's location and station lookups go through [StationLookups], which
 * these tests answer by hand: Near me, Use current address and Find missing street address. A failed lookup shows
 * its reason and can be tried again; leaving the page or choosing another station cancels the lookup; a lookup that replies after the user moved on, even after a newer lookup started, is never shown; and a
 * current search lists its stations. Synthetic positions and stations; nothing leaves the test device. Runs on the
 * JVM and the emulator. The Map/List choice a test makes is kept only for this test's own synthetic account.
 */
@RunWith(AndroidJUnit4::class)
class StationLookupUiTest {
    @get:Rule val compose = createComposeRule()

    private class Lookups(val pastCancel: Boolean) : StationLookups {
        val locations = HeldCalls<Location>()
        val addresses = HeldCalls<String>()
        val searches = HeldCalls<FuelStationLookup.SearchResult>()
        val suggestions = HeldCalls<String>()
        override fun hasLocationPermission(context: Context) = true
        override fun recentLocation(context: Context): Location? = null
        override suspend fun freshLocation(context: Context): Location? = null
        override suspend fun currentLocation(context: Context) =
            if (pastCancel) locations.holdPastCancel("location") else locations.hold("location")
        override suspend fun addressAt(context: Context, position: Location) =
            if (pastCancel) addresses.holdPastCancel("address") else addresses.hold("address")
        override suspend fun suggestedStationAddress(key: String, station: FuelStation) =
            if (pastCancel) suggestions.holdPastCancel(station.name) else suggestions.hold(station.name)
        override suspend fun nearbyWithProvider(position: Location, forceRefresh: Boolean, geoapifyKey: String?,
                                                bounds: StationBounds?) = searches.hold("search")
    }

    private val position = Location("synthetic").apply { latitude = 0.5; longitude = 0.5; time = 1 }
    private val settings = SecureConfig(ApplicationProvider.getApplicationContext())

    @After fun removeGeoapifyKey() = settings.removeGeoapifyKey()

    private fun show(lookups: StationLookups) = compose.setContent {
        LubeTheme {
            CompositionLocalProvider(LocalStationLookups provides lookups) {
                FuelStationPickerDialog("", "", baseUrl = "https://lubelogger.invalid", editorKey = "synthetic-station-lookups",
                    onChoose = {}, onDismiss = {})
            }
        }
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    private fun addressField() = compose.onNode(hasText("Address") and hasSetTextAction())
    private fun <T> awaitCalls(calls: HeldCalls<T>, count: Int) = compose.awaitCondition { calls.calls.size == count }

    // Manual → Use current address

    @Test fun aFailedAddressLookupShowsWhyAndCanBeTriedAgain() {
        val lookups = Lookups(pastCancel = false)
        show(lookups)
        compose.onNodeWithText("Manual").performClick()
        compose.onNodeWithText("Use current address").performClick()
        awaitCalls(lookups.locations, 1)
        assertTrue(shows("Finding address…"))
        lookups.locations.last.succeed(position)
        awaitCalls(lookups.addresses, 1)
        lookups.addresses.last.fail(IllegalStateException("Synthetic: no address for this position"))
        compose.awaitCondition { shows("Synthetic: no address for this position") }
        compose.onNodeWithText("Use current address").assertIsEnabled()
    }

    @Test fun anAddressThatArrivesAfterLeavingManualIsNeverFilledIn() {
        val lookups = Lookups(pastCancel = true)
        show(lookups)
        compose.onNodeWithText("Manual").performClick()
        compose.onNodeWithText("Use current address").performClick()
        awaitCalls(lookups.locations, 1)
        lookups.locations.last.succeed(position)
        awaitCalls(lookups.addresses, 1)
        compose.onNodeWithText("Saved").performClick()
        compose.waitForIdle()
        lookups.addresses.last.succeed("1 Synthetic Way")
        compose.waitForIdle()
        compose.onNodeWithText("Manual").performClick()
        compose.waitForIdle()
        assertFalse("The late address was not filled in", shows("1 Synthetic Way"))
        compose.onNodeWithText("Use current address").assertIsEnabled()
        addressField().assertExists()
    }

    @Test fun leavingManualCancelsTheAddressLookup() {
        val lookups = Lookups(pastCancel = false)
        show(lookups)
        compose.onNodeWithText("Manual").performClick()
        compose.onNodeWithText("Use current address").performClick()
        awaitCalls(lookups.locations, 1)
        compose.onNodeWithText("Saved").performClick()
        compose.awaitCondition { lookups.locations.last.cancelled }
    }

    // Nearby

    @Test fun aFailedNearbySearchShowsWhy() {
        val lookups = Lookups(pastCancel = false)
        show(lookups)
        compose.onNodeWithText("Nearby").performClick()
        awaitCalls(lookups.locations, 1)
        lookups.locations.last.fail(IllegalStateException("Synthetic: location unavailable"))
        compose.awaitCondition { shows("Synthetic: location unavailable") }
        compose.onNodeWithText("Find nearby").assertIsEnabled()
    }

    @Test fun aCurrentNearbySearchListsItsStations() {
        val lookups = Lookups(pastCancel = false)
        show(lookups)
        compose.onNodeWithText("Nearby").performClick()
        awaitCalls(lookups.locations, 1)
        compose.onNodeWithText("List").performClick()
        lookups.locations.last.succeed(position)
        awaitCalls(lookups.searches, 1)
        lookups.searches.last.succeed(FuelStationLookup.SearchResult(
            listOf(FuelStation("Synthetic Fuel Stop", "2 Synthetic Road")), StationSearchProvider.OVERPASS))
        compose.awaitCondition { shows("Synthetic Fuel Stop") }
    }

    @Test fun leavingNearbyCancelsTheSearch() {
        val lookups = Lookups(pastCancel = false)
        show(lookups)
        compose.onNodeWithText("Nearby").performClick()
        awaitCalls(lookups.locations, 1)
        compose.onNodeWithContentDescription("Back to station picker").performClick()
        compose.awaitCondition { lookups.locations.last.cancelled }
    }

    @Test fun aLateFailureOfAnEarlierNearbySearchIsNeverShown() {
        val lookups = Lookups(pastCancel = true)
        show(lookups)
        compose.onNodeWithText("Nearby").performClick()
        awaitCalls(lookups.locations, 1)
        compose.onNodeWithContentDescription("Back to station picker").performClick()
        compose.onNodeWithText("Nearby").performClick()
        awaitCalls(lookups.locations, 2)
        lookups.locations[0].fail(IllegalStateException("Synthetic: stale failure"))
        compose.waitForIdle()
        assertFalse("The earlier search's failure is not shown", shows("Synthetic: stale failure"))
        assertTrue("The current search is still running", shows("Searching…"))
        lookups.locations[1].fail(IllegalStateException("Synthetic: current failure"))
        compose.awaitCondition { shows("Synthetic: current failure") }
        assertEquals(2, lookups.locations.calls.size)
    }

    // Nearby → a station → Find missing street address (Geoapify)

    /** Lists two synthetic stations with coordinates and no street address, then selects the first. */
    private fun selectFirstOfTwoStations(lookups: Lookups) {
        settings.saveGeoapifyKey("synthetic-geoapify")
        show(lookups)
        compose.onNodeWithText("Nearby").performClick()
        awaitCalls(lookups.locations, 1)
        compose.onNodeWithText("List").performClick()
        lookups.locations.last.succeed(position)
        awaitCalls(lookups.searches, 1)
        lookups.searches.last.succeed(FuelStationLookup.SearchResult(listOf(
            FuelStation("Synthetic Stop A", latitude = 0.51, longitude = 0.5),
            FuelStation("Synthetic Stop B", latitude = 0.52, longitude = 0.5)), StationSearchProvider.GEOAPIFY))
        compose.awaitCondition { shows("Synthetic Stop A") }
        compose.onAllNodes(hasText("Synthetic Stop A")).onFirst().performClick()
        compose.onNodeWithText("Find missing street address").performClick()
        awaitCalls(lookups.suggestions, 1)
    }

    @Test fun aFailedAddressSuggestionShowsWhy() {
        val lookups = Lookups(pastCancel = false)
        selectFirstOfTwoStations(lookups)
        assertTrue(shows("Finding street address…"))
        lookups.suggestions.last.fail(IllegalStateException("Synthetic: no street address found"))
        compose.awaitCondition { shows("Synthetic: no street address found") }
        compose.onNodeWithText("Find missing street address").assertIsEnabled()
    }

    @Test fun choosingAnotherStationCancelsTheSuggestion() {
        val lookups = Lookups(pastCancel = false)
        selectFirstOfTwoStations(lookups)
        compose.onAllNodes(hasText("Synthetic Stop B")).onFirst().performClick()
        compose.awaitCondition { lookups.suggestions.last.cancelled }
    }

    @Test fun aSuggestionForAStationNoLongerChosenIsNeverProposed() {
        val lookups = Lookups(pastCancel = true)
        selectFirstOfTwoStations(lookups)
        compose.onAllNodes(hasText("Synthetic Stop B")).onFirst().performClick()
        compose.waitForIdle()
        lookups.suggestions.last.succeed("3 Synthetic Avenue")
        compose.waitForIdle()
        assertFalse("The first station's address is not proposed", shows("3 Synthetic Avenue"))
        assertFalse(shows("Use this address"))
    }
}
