package com.kramttocs.lubelink.quality

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.EditOutcome
import com.kramttocs.lubelink.core.EditorDraft
import com.kramttocs.lubelink.core.FieldReadiness
import com.kramttocs.lubelink.core.GasStationFieldCheck
import com.kramttocs.lubelink.core.LubeLoggerEditorApi.EditableSnapshot
import com.kramttocs.lubelink.core.RecordEditorGateway
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordRef
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.EditorRecordDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** A station typed into the picker's Manual page survives rotation or process death, with the picker still open.
 * Synthetic data; the fake writer is never called and nothing leaves the test device. */
@RunWith(AndroidJUnit4::class)
class StationPickerRestoreUiTest {
    @get:Rule val compose = createComposeRule()
    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", useHours = false)

    private object NoWrites : RecordEditorGateway {
        override suspend fun addRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId, draft: EditorDraft): RecordId =
            error("not used")
        override suspend fun loadEditableRecord(vehicle: Vehicle, record: RecordRef): EditableSnapshot = error("not used")
        override suspend fun updateRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId,
                                          snapshot: EditableSnapshot, draft: EditorDraft): EditOutcome = error("not used")
    }

    @Test fun aManualStationBeingTypedSurvivesRestoration() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                initialKind = RecordKind.FUEL, baseUrl = "https://lubelogger.invalid", editorKey = "synthetic",
                gasFields = GasStationFieldCheck(FieldReadiness.READY), onGasFieldsNeeded = {}, onDismiss = {},
                onSaved = {}, editor = NoWrites)
        }
        compose.onNodeWithContentDescription("Find or choose gas station").performClick()
        compose.onNodeWithText("Manual").performClick()
        compose.onNode(hasText("Station name") and hasSetTextAction()).performTextInput("Synthetic Fuel Stop")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Manual").performClick()
        compose.onNodeWithText("Synthetic Fuel Stop").assertExists()
    }
}
