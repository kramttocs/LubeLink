package com.kramttocs.lubelink.quality

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
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
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OD-50: in the Add record form every text field spans the form's width, so its left and right edges line up with the
 * date button's (Service: Odometer, Description, Cost, Notes; Fuel: Odometer, the gallons-and-cost pair, Notes), at
 * normal and at the largest text. Nothing is saved. Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class FormAlignmentUiTest {
    @get:Rule val compose = createComposeRule()
    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", false)

    private val noWrites = object : RecordEditorGateway {
        override suspend fun addRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId, draft: EditorDraft): RecordId =
            error("not used")
        override suspend fun loadEditableRecord(vehicle: Vehicle, record: RecordRef): EditableSnapshot = error("not used")
        override suspend fun updateRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId, snapshot: EditableSnapshot,
                                          draft: EditorDraft): EditOutcome = error("not used")
    }

    private fun open(kind: RecordKind, fontScale: Float) = compose.setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
            LubeTheme {
                EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                    baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", initialKind = kind,
                    gasFields = GasStationFieldCheck(FieldReadiness.READY), onGasFieldsNeeded = {}, onDismiss = {},
                    onSaved = {}, editor = noWrites)
            }
        }
    }

    private fun bounds(label: String): Rect =
        compose.onNode(hasText(label) and hasSetTextAction(), useUnmergedTree = false).fetchSemanticsNode().boundsInWindow
    private fun dateButton(): Rect =
        compose.onNode(hasText("Date:", substring = true) and hasClickAction()).fetchSemanticsNode().boundsInWindow

    private fun assertEdges(what: String, left: Float, right: Float, form: Rect) {
        assertEquals("$what: left edge", form.left, left, 1f)
        assertEquals("$what: right edge", form.right, right, 1f)
    }

    private fun checkService(fontScale: Float) {
        open(RecordKind.SERVICE, fontScale)
        val form = dateButton()
        for (label in listOf("Odometer / hours", "Description", "Cost (0 if untracked)", "Notes"))
            bounds(label).let { assertEdges("$label at $fontScale", it.left, it.right, form) }
    }

    private fun checkFuel(fontScale: Float) {
        open(RecordKind.FUEL, fontScale)
        val form = dateButton()
        for (label in listOf("Odometer / hours", "Notes"))
            bounds(label).let { assertEdges("$label at $fontScale", it.left, it.right, form) }
        assertEdges("US gallons and Total cost at $fontScale", bounds("US gallons").left, bounds("Total cost").right, form)
    }

    @Test fun serviceFieldsLineUpAtNormalText() = checkService(1f)
    @Test fun serviceFieldsLineUpAtTheLargestText() = checkService(2f)
    @Test fun fuelFieldsLineUpAtNormalText() = checkFuel(1f)
    @Test fun fuelFieldsLineUpAtTheLargestText() = checkFuel(2f)
}
