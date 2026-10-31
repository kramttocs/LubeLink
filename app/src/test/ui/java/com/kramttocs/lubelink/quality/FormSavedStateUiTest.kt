package com.kramttocs.lubelink.quality

import android.content.Context
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kramttocs.lubelink.core.EditOutcome
import com.kramttocs.lubelink.core.EditorDraft
import com.kramttocs.lubelink.core.FieldReadiness
import com.kramttocs.lubelink.core.GasStationFieldCheck
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.LubeLoggerEditorApi.EditableSnapshot
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.ReminderId
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.RecordEditorGateway
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordRef
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.EditorRecordDialog
import com.kramttocs.lubelink.ui.FuelStationPickerDialog
import com.kramttocs.lubelink.ui.RecordDetailsDialog
import com.kramttocs.lubelink.ui.RecordRow
import com.kramttocs.lubelink.ui.ReminderRow
import com.kramttocs.lubelink.ui.ReminderDetailsDialog
import com.kramttocs.lubelink.ui.WriteSafety
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import android.os.Build
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The record form's saved state stays small whatever the record holds, and a restored form
 * still has the long server text and the user's other edits. Synthetic data only; a fake writer.
 *
 * Sizes are measured by writing what the form saves into a Parcel, the way Android does. The last test
 * stops a real Activity, which sends its saved state to the system over Binder (~1 MB limit); Robolectric does
 * not enforce that limit, so its evidence is the emulator run. Runs on the JVM and the emulator.
 */
@RunWith(AndroidJUnit4::class)
class FormSavedStateUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", useHours = false)
    private val ready = GasStationFieldCheck(FieldReadiness.READY)
    private val ref = RecordRef(car.id, RecordKind.SERVICE, RecordId(15))
    /** 600,000 characters: about 1.2 MB as a Parcel string, over Android's Binder limit on its own. */
    private val hugeNotes = "Synthetic long note line.\n".repeat(600_000 / 26)

    private class FakeEditor(val server: EditorDraft) : RecordEditorGateway {
        val loads = CopyOnWriteArrayList<RecordRef>()
        val updates = CopyOnWriteArrayList<EditorDraft>()
        override suspend fun addRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId, draft: EditorDraft) = RecordId(101)
        override suspend fun loadEditableRecord(vehicle: Vehicle, record: RecordRef): EditableSnapshot {
            loads += record
            val json = JSONObject().put("id", record.id.value).put("vehicleId", record.vehicleId.value)
                .put("date", server.date).put("odometer", server.odometer).put("description", server.description)
                .put("cost", server.cost).put("notes", server.notes).put("tags", server.tags)
                .put("files", JSONArray()).put("extraFields", JSONArray())
            return EditableSnapshot(record, json.toString(), server)
        }
        override suspend fun updateRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId,
                                          snapshot: EditableSnapshot, draft: EditorDraft): EditOutcome {
            updates += draft
            return EditOutcome.SAVED
        }
    }

    @Before fun cleanLock() {
        assertTrue("Run with no pending real write", WriteSafety.unresolved(context) == null)
    }

    @After fun releaseLock() {
        val deadline = System.currentTimeMillis() + 10_000
        while (WriteSafety.state is WriteSafety.State.Pending && System.currentTimeMillis() < deadline) Thread.sleep(25)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (WriteSafety.state !is WriteSafety.State.Idle) WriteSafety.acknowledge(context, checkedServer = true)
        }
        context.getSharedPreferences("lubelink_unconfirmed_writes", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun parcelBytes(saved: Map<String, List<Any?>>): Int {
        val parcel = Parcel.obtain()
        try {
            saved.forEach { (key, values) -> parcel.writeString(key); values.forEach(parcel::writeValue) }
            return parcel.dataSize()
        } finally { parcel.recycle() }
    }

    private fun serverDraft(notes: String) = EditorDraft(RecordKind.SERVICE, "2026-10-01", "1200", "Inspection", "10.00", notes, "")

    @Test fun anEditOfARecordWithHugeNotesSavesLittleAndRestoresTheNotesAndTheOtherEdits() {
        // Emulator only (an approved exception). Under Robolectric the restored Edit dialog never starts
        // its load after the simulated process death; not solved within a bounded effort. Skipped, and counted
        // as skipped, on the JVM; it runs in every emulator run.
        assumeFalse("Emulator only", Build.FINGERPRINT == "robolectric")
        val fake = FakeEditor(serverDraft(hugeNotes))
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        var mounted by mutableStateOf(true)
        compose.setContent {
            if (mounted) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                    baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                    onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {}, existingRecord = ref, editor = fake)
            }
        }
        compose.waitUntil(15_000) { fake.loads.size == 1 }
        // Too long to change (or to lay out) as a text field: kept, and said so.
        compose.onNodeWithText("so LubeLink keeps them unchanged", substring = true).assertExists()
        compose.onNodeWithText("Description").performTextClearance()
        compose.onNodeWithText("Description").performTextInput("Inspection and wipers")
        lateinit var saved: Map<String, List<Any?>>
        compose.runOnIdle { saved = registry.performSave() }
        val bytes = parcelBytes(saved)
        assertTrue("Saved form state is $bytes bytes; must stay under 32 KB", bytes < 32 * 1024)

        // Process death: a new composition restored from only what was saved.
        compose.runOnIdle { mounted = false }
        compose.runOnIdle { registry = SaveableStateRegistry(saved) { true }; mounted = true }
        compose.waitUntil(15_000) { fake.loads.size == 2 }
        compose.onNodeWithText("Inspection and wipers").assertExists()
        compose.onNodeWithText("too long to keep", substring = true).assertDoesNotExist()
        compose.onNode(hasText("Review changes") and hasClickAction()).performClick()
        compose.onNode(hasText("Save changes") and hasClickAction()).performClick()
        compose.waitUntil(15_000) { fake.updates.size == 1 }
        val sent = fake.updates.single()
        assertEquals("The typed change survives", "Inspection and wipers", sent.description)
        assertEquals("The long notes come back from LubeLogger unchanged", hugeNotes, sent.notes)
    }

    @Test fun typedNotesStopAtTheSaveLimitSoTheirSavedStateIsBounded() {
        val registry = SaveableStateRegistry(null) { true }
        compose.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                    baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                    onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {}, editor = FakeEditor(serverDraft("")))
            }
        }
        compose.onNodeWithText("Notes").performTextInput("x".repeat(6_000))
        compose.onNodeWithText("5000 / 5000").assertExists()
        lateinit var saved: Map<String, List<Any?>>
        compose.runOnIdle { saved = registry.performSave() }
        val bytes = parcelBytes(saved)
        assertTrue("Saved form state is $bytes bytes", bytes < 32 * 1024)
    }

    /** Details dialogs used to lay out all of a note; ~23,000 lines exceed Compose's size limit. */
    @Test fun recordAndReminderDetailsShowHugeNotesUpToABound() {
        var showReminder by mutableStateOf(false)
        val record = ServiceRecord(RecordId(15), car.id, "2026-10-01", "Inspection", 1200, "10.00", hugeNotes, "",
            emptyList(), RecordKind.SERVICE)
        val reminder = MaintenanceReminder(ReminderId(4), car.id, "Rotate tires", "Urgent", "Date", "2026-11-01",
            null, 10, null, hugeNotes)
        compose.setContent {
            if (!showReminder) RecordDetailsDialog(record, useHours = false,
                api = LubeLoggerApi("https://lubelogger.invalid", "synthetic"), onDismiss = {})
            else ReminderDetailsDialog(reminder, useHours = false, dismiss = {})
        }
        compose.onNodeWithText("The rest of these notes is in LubeLogger.", substring = true).assertExists()
        compose.runOnIdle { showReminder = true }
        compose.onNodeWithText("Rotate tires").assertExists()
        compose.onNodeWithText("The rest of these notes is in LubeLogger.", substring = true).assertExists()
    }

    /** Descriptions, station names and vehicle names from LubeLogger have no length limit there; shown in full,
     * 600,000 characters break the layout as long notes did. Rows, titles and lines show the first 300. */
    @Test fun hugeDescriptionsStationsAndVehicleNamesFromLubeLoggerStayOnScreen() {
        val huge = "Synthetic long description ".repeat(600_000 / 27)
        val longCar = car.copy(model = huge)
        val fuel = ServiceRecord(RecordId(16), car.id, "2026-10-02", "Fuel", 1250, "52.10", "", "", emptyList(),
            RecordKind.FUEL, fuelConsumed = 12.5, fuelStation = huge, fuelStationAddress = huge)
        val service = ServiceRecord(RecordId(15), car.id, "2026-10-01", huge, 1200, "10.00", "", "", emptyList(),
            RecordKind.SERVICE)
        val reminder = MaintenanceReminder(ReminderId(4), car.id, huge, "Urgent", "Date", "2026-11-01", null, 10, null, "")
        var step by mutableStateOf(0)
        compose.setContent {
            when (step) {
                0 -> Column {
                    RecordRow(service, useHours = false) {}
                    RecordRow(fuel, useHours = false) {}
                    ReminderRow(reminder, longCar.displayName, useHours = false) {}
                }
                1 -> RecordDetailsDialog(service, useHours = false,
                    api = LubeLoggerApi("https://lubelogger.invalid", "synthetic"), onDismiss = {})
                2 -> RecordDetailsDialog(fuel, useHours = false,
                    api = LubeLoggerApi("https://lubelogger.invalid", "synthetic"), onDismiss = {})
                else -> ReminderDetailsDialog(reminder, useHours = false, dismiss = {})
            }
        }
        for (next in 1..3) {
            compose.onAllNodes(hasText("…", substring = true)).fetchSemanticsNodes().let {
                assertTrue("Step ${next - 1} shows no shortened text", it.isNotEmpty())
            }
            compose.runOnIdle { step = next }
        }
        compose.onAllNodes(hasText("…", substring = true)).fetchSemanticsNodes().let {
            assertTrue("Reminder details show no shortened text", it.isNotEmpty())
        }
    }

    /** The station picker opened with a record's huge station saves little and restores the station. */
    @Test fun theStationPickerOpenedWithAHugeStationSavesLittleAndRestoresIt() {
        val station = "Synthetic long station ".repeat(600_000 / 23)
        val address = "1 Synthetic Road ".repeat(600_000 / 17)
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        var mounted by mutableStateOf(true)
        compose.setContent {
            if (mounted) CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                FuelStationPickerDialog(station, address, "https://lubelogger.invalid", "synthetic",
                    onChoose = {}, onDismiss = {})
            }
        }
        compose.waitForIdle()
        lateinit var saved: Map<String, List<Any?>>
        compose.runOnIdle { saved = registry.performSave() }
        val bytes = parcelBytes(saved)
        assertTrue("Saved picker state is $bytes bytes; must stay under 32 KB", bytes < 32 * 1024)

        compose.runOnIdle { mounted = false }
        compose.runOnIdle { registry = SaveableStateRegistry(saved) { true }; mounted = true }
        compose.onNode(hasText("Manual") and hasClickAction()).performClick()
        compose.onNodeWithText("Synthetic long station", substring = true).assertExists()
        compose.onNodeWithText("only its start is shown", substring = true).assertExists()
        assertTrue("No saved-state marker is shown",
            compose.onAllNodes(hasText("lubelink:", substring = true)).fetchSemanticsNodes().isEmpty())
    }

    /** Add from a reminder whose Notes a new record could not save (they are left out, OD-12). */
    @Test fun addFromAReminderWithHugeNotesLeavesThemOutAndSaysSo() {
        val reminder = MaintenanceReminder(ReminderId(4), car.id, "Rotate tires", "Urgent", "Date", "2026-11-01",
            null, 10, null, hugeNotes)
        compose.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                initialReminder = reminder, onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {},
                editor = FakeEditor(serverDraft("")))
        }
        compose.onNodeWithText("The reminder's notes are longer than", substring = true).assertExists()
        compose.onNodeWithText("Rotate tires").assertExists()
    }

    /** The character limit does not bound lines; 5,000 typed line breaks at double font size. */
    @Test fun fiveThousandTypedLineBreaksAtLargeFontDoNotBreakTheForm() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 2f)) {
                EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                    baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                    onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {}, editor = FakeEditor(serverDraft("")))
            }
        }
        compose.onNodeWithText("Notes").performTextInput("\n".repeat(5_000))
        compose.waitForIdle()
        compose.onNodeWithText("5000 / 5000").assertExists()
        compose.onNodeWithText("Add record").assertExists()
    }

    /** The real path: stopping the Activity sends its saved state to the system over Binder. */
    @Test fun stoppingTheActivityWithAHugeRecordOpenDoesNotExceedTheBinderLimit() {
        val fake = FakeEditor(serverDraft(hugeNotes))
        compose.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {}, existingRecord = ref, editor = fake)
        }
        compose.waitUntil(15_000) { fake.loads.size == 1 }
        compose.onNodeWithText("Edit record").assertExists()
        // On the unbounded form this stop throws TransactionTooLargeException and the process dies.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText("Edit record").assertExists()
    }
}
