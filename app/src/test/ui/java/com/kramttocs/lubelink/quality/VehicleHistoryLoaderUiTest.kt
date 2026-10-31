package com.kramttocs.lubelink.quality

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordsLoad
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.VehicleHistorySource
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.rememberVehicleHistory
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** History for a vehicle restored after process death loads once the Garage arrives. */
@RunWith(AndroidJUnit4::class)
class VehicleHistoryLoaderUiTest {
    @get:Rule val compose = createComposeRule()

    private val car = VehicleId(1)
    private val record = ServiceRecord(RecordId(15), car, "2026-10-01", "Synthetic brake service", 1200,
        "10.00", "", "", emptyList(), RecordKind.SERVICE)

    @Test fun restoredSelectionLoadsHistoryWhenTheGarageArrives() {
        val loads = CopyOnWriteArrayList<VehicleId>()
        val source = VehicleHistorySource { id, _ -> loads += id; RecordsLoad(listOf(record)) }
        // As after process death: the selected vehicle is already restored, the Garage is not.
        var garageReady by mutableStateOf(false)
        compose.setContent {
            val history = rememberVehicleHistory(source, car, includeFuel = true,
                garageReady = garageReady, generation = 0)
            Text(history.records.joinToString { it.description }.ifEmpty { "No records" })
        }
        compose.onNodeWithText("No records").assertExists()
        assertEquals("Nothing may load before the Garage is known", 0, loads.size)
        compose.runOnIdle { garageReady = true }
        compose.awaitCondition(5_000) { loads.size == 1 }
        compose.onNodeWithText("Synthetic brake service").assertExists()
        assertEquals(listOf(car), loads.toList())
    }
}
