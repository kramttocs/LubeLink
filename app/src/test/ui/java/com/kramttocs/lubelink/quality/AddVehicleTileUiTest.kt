package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.GarageScreen
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** OD-38: the Garage ends with LubeLogger's "+" tile, in the list, the grid and an empty Garage; tapping it opens the
 * server's LubeLogger page and nothing else. Synthetic server. Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class AddVehicleTileUiTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private val server = ScreenshotServer()
    private val driver = TourDriver(compose) {}
    private val opened = CopyOnWriteArrayList<String>()
    private val browser = object : UriHandler { override fun openUri(uri: String) { opened += uri } }
    private val tile = hasContentDescription("Add a vehicle in LubeLogger")

    @After fun cleanUp() {
        settings.disconnect()
        server.close()
    }

    @Test fun theTileAtTheEndOfTheGarageOpensLubeLogger() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        compose.setContent {
            CompositionLocalProvider(LocalUriHandler provides browser) {
                LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } }
            }
        }
        driver.waitFor("2019 Honda CR-V")
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(tile)
        compose.onNode(tile).performClick()
        assertEquals(listOf(server.baseUrl + "/"), opened.toList())
    }

    @Test fun theGridAndAnEmptyGarageEndWithTheTile() {
        val api = LubeLoggerApi("https://lubelogger.invalid", "synthetic")
        val cars = ScreenshotGarage.cars.map { Vehicle(VehicleId(it.id), it.year, it.make, it.model, "", useHours = it.hours) }
        var data by mutableStateOf(GarageData(cars, emptyMap(), emptyList()))
        compose.setContent {
            LubeTheme {
                GarageScreen(data, api, 0, emptyMap(), SecureConfig.GarageLayout.GRID, onManageVisibility = {},
                    onSelect = {}, onReports = {}, onAddVehicle = { opened += "add" })
            }
        }
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(tile)
        compose.onNode(tile).performClick()
        assertEquals("Grid", listOf("add"), opened.toList())

        data = GarageData(emptyList(), emptyMap(), emptyList())
        compose.onNode(tile).performClick()
        assertEquals("Empty Garage", listOf("add", "add"), opened.toList())
    }
}
