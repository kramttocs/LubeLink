package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.EditOutcome
import com.kramttocs.lubelink.core.EditorDraft
import com.kramttocs.lubelink.core.FieldReadiness
import com.kramttocs.lubelink.core.GasStationFieldCheck
import com.kramttocs.lubelink.core.LubeLoggerEditorApi
import com.kramttocs.lubelink.core.LubeLoggerEditorApi.EditableSnapshot
import org.json.JSONArray
import org.json.JSONObject
import com.kramttocs.lubelink.core.RecordEditorGateway
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordRef
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.EditorRecordDialog
import com.kramttocs.lubelink.ui.WriteSafety
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Add/Edit dialog and the real WriteSafety lock, with a fake LubeLogger writer. Synthetic data only; nothing leaves the test device. Each test asserts what the writer
 * actually received (count and draft), not only what the screen shows.
 */
@RunWith(AndroidJUnit4::class)
class EditorFormUiTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", useHours = false)
    private val ready = GasStationFieldCheck(FieldReadiness.READY)

    private class FakeEditor : RecordEditorGateway {
        val adds = CopyOnWriteArrayList<EditorDraft>()
        val updates = CopyOnWriteArrayList<Pair<EditableSnapshot, EditorDraft>>()
        val loads = CopyOnWriteArrayList<Int>()
        var addGate: CompletableDeferred<Unit>? = null
        val loadGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        val serverDrafts = mutableMapOf<Int, EditorDraft>()
        var updateOutcome = EditOutcome.SAVED

        override suspend fun addRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId, draft: EditorDraft): RecordId {
            adds += draft
            addGate?.await()
            return RecordId(101)
        }
        override suspend fun loadEditableRecord(vehicle: Vehicle, record: RecordRef): EditableSnapshot {
            val id = record.id.value
            loads += id
            loadGates[id]?.await()
            // Real record JSON, so the production fingerprint compares it like a LubeLogger record.
            val d = serverDrafts.getValue(id)
            val json = JSONObject().put("id", id).put("vehicleId", record.vehicleId.value).put("date", d.date)
                .put("odometer", d.odometer).put("description", d.description).put("cost", d.cost)
                .put("notes", d.notes).put("tags", d.tags).put("files", JSONArray()).put("extraFields", JSONArray())
            return EditableSnapshot(record, json.toString(), d)
        }
        override suspend fun updateRecord(vehicle: Vehicle, confirmedVehicleId: VehicleId,
                                          snapshot: EditableSnapshot, draft: EditorDraft): EditOutcome {
            updates += snapshot to draft
            return updateOutcome
        }
    }

    private fun serviceRecord(id: Int, description: String) = ServiceRecord(RecordId(id), car.id, "2026-10-01",
        description, 1200, "10.00", "", "", emptyList(), RecordKind.SERVICE)
    private fun serviceDraft(description: String) =
        EditorDraft(RecordKind.SERVICE, "2026-10-01", "1200", description, "10.00", "", "")

    @Before fun cleanLock() {
        assertTrue("Run with no pending real write", WriteSafety.unresolved(context) == null)
        assertTrue("WriteSafety must start idle", WriteSafety.state is WriteSafety.State.Idle)
    }

    @After fun releaseLock() {
        // awaitCondition drives the main thread on the JVM (Robolectric) as well as on the emulator; a sleep loop
        // would leave a JVM run waiting for a write that can never finish.
        compose.awaitCondition(10_000) { WriteSafety.state !is WriteSafety.State.Pending }
        compose.runOnIdle { if (WriteSafety.state !is WriteSafety.State.Idle) WriteSafety.acknowledge(context, checkedServer = true) }
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Idle }
        context.getSharedPreferences("lubelink_unconfirmed_writes", Context.MODE_PRIVATE).edit().clear().commit()
    }

    // The confirm dialog's title and its button both read "Save changes"; target the button.
    private fun saveChangesButton() = compose.onNode(hasText("Save changes") and hasClickAction())

    private fun addDialog(fake: FakeEditor, onDismiss: () -> Unit = {}) {
        compose.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                onGasFieldsNeeded = {}, onDismiss = onDismiss, onSaved = {}, editor = fake)
        }
    }

    @Test fun rapidRepeatedSaveSendsExactlyOneCreate() {
        val fake = FakeEditor().apply { addGate = CompletableDeferred() }
        addDialog(fake)
        compose.onNodeWithText("Odometer / hours").performTextInput("1200")
        compose.onNodeWithText("Description").performTextInput("Synthetic oil change")
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Save").performClick()
        compose.awaitCondition(5_000) { fake.adds.size == 1 }
        fake.addGate!!.complete(Unit)
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Success }
        assertEquals("Repeated taps must create one record", 1, fake.adds.size)
        assertEquals("Synthetic oil change", fake.adds.single().description)
    }

    /** The favourite star (inside the Description field beside the arrow, OD-45) is tapped: the description is
     * stored as a favourite and removed again, the star says which, and the typed form is unchanged. */
    @Test fun theFavouriteStarSavesAndRemovesTheDescriptionAndLeavesTheFormAlone() {
        val store = com.kramttocs.lubelink.core.ServiceDescriptionStore(context, "https://lubelogger.invalid")
        val description = "Synthetic cabin filter"
        store.setFavorite(RecordKind.SERVICE, description, false)
        try {
            val fake = FakeEditor()
            addDialog(fake)
            compose.onNodeWithText("Description").performTextInput(description)
            compose.onNodeWithContentDescription("Save description as favorite").performClick()
            compose.onNodeWithContentDescription("Remove favorite description").assertExists()
            assertTrue("Stored as a favourite", store.isFavorite(RecordKind.SERVICE, description))
            compose.onNodeWithContentDescription("Remove favorite description").performClick()
            compose.onNodeWithContentDescription("Save description as favorite").assertExists()
            assertFalse("Removed again", store.isFavorite(RecordKind.SERVICE, description))
            compose.onNodeWithText(description).assertExists()
            assertEquals("Nothing was sent", 0, fake.adds.size)
        } finally { store.setFavorite(RecordKind.SERVICE, description, false) }
    }

    @Test fun discardAfterTypingAsksFirstAndReopenStartsEmpty() {
        val fake = FakeEditor()
        var open by mutableStateOf(true)
        var session by mutableIntStateOf(0)
        compose.setContent {
            key(session) {
                if (open) EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                    baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                    onGasFieldsNeeded = {}, onDismiss = { open = false }, onSaved = {}, editor = fake)
            }
        }
        compose.onNodeWithText("Description").performTextInput("Typed then discarded")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard record changes?").assertIsDisplayed()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle { assertTrue("Discard must close the form", !open) }
        compose.runOnIdle { session++; open = true }
        compose.onNodeWithText("Typed then discarded").assertDoesNotExist()
        assertEquals("Discard must not write", 0, fake.adds.size)
    }

    @Test fun unchangedEditSendsNothingAndSaysSo() {
        val fake = FakeEditor().apply { serverDrafts[15] = serviceDraft("Inspection") }
        compose.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {},
                existingRecord = serviceRecord(15, "Inspection").ref, editor = fake)
        }
        compose.onNodeWithText("Review changes").performClick()
        compose.onNodeWithText("No changes to save.").assertIsDisplayed()
        assertEquals(0, fake.updates.size)
        assertTrue(WriteSafety.state is WriteSafety.State.Idle)
    }

    @Test fun changedEditShowsBeforeAfterThenSendsOneUpdateForThatRecord() {
        val fake = FakeEditor().apply { serverDrafts[15] = serviceDraft("Inspection") }
        compose.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {},
                existingRecord = serviceRecord(15, "Inspection").ref, editor = fake)
        }
        compose.onNodeWithText("Description").performTextClearance()
        compose.onNodeWithText("Description").performTextInput("Annual inspection")
        compose.onNodeWithText("Review changes").performClick()
        compose.onNodeWithText("Description: Inspection → Annual inspection").assertIsDisplayed()
        saveChangesButton().performClick()
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Success }
        assertEquals(1, fake.updates.size)
        val (snapshot, draft) = fake.updates.single()
        assertEquals("Update must target the record that was opened", RecordRef(car.id, RecordKind.SERVICE, RecordId(15)), snapshot.ref)
        assertEquals("Annual inspection", draft.description)
    }

    /** A8: an Edit the client answers "nothing to save" (no request sent) is never shown as saved, even past the form's
     * own unchanged-edit check. */
    @Test fun anEditTheClientHadNothingToSaveForIsNotReportedAsSaved() {
        val fake = FakeEditor().apply { serverDrafts[15] = serviceDraft("Inspection"); updateOutcome = EditOutcome.NOTHING_TO_SAVE }
        compose.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {},
                existingRecord = serviceRecord(15, "Inspection").ref, editor = fake)
        }
        compose.onNodeWithText("Description").performTextClearance()
        compose.onNodeWithText("Description").performTextInput("Annual inspection")
        compose.onNodeWithText("Review changes").performClick()
        saveChangesButton().performClick()
        compose.awaitCondition(10_000) { WriteSafety.state !is WriteSafety.State.Pending }
        assertEquals(WriteSafety.State.Failed("No changes to save."), WriteSafety.state)
        assertNull("Nothing left to reconcile", WriteSafety.unresolved(context))
    }

    /** OD-75: the Save review states what is kept without promising more; Go back sends nothing. */
    @Test fun theSaveReviewSaysWhatIsKeptAndGoBackSendsNothing() {
        val fake = FakeEditor().apply { serverDrafts[15] = serviceDraft("Inspection") }
        compose.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {},
                existingRecord = serviceRecord(15, "Inspection").ref, editor = fake)
        }
        compose.onNodeWithText("Description").performTextClearance()
        compose.onNodeWithText("Description").performTextInput("Annual inspection")
        compose.onNodeWithText("Review changes").performClick()
        compose.onNodeWithText("Other fields and files are kept as LubeLogger had them just before saving.").assertIsDisplayed()
        compose.onAllNodes(hasText("remain preserved", substring = true)).assertCountEquals(0)
        saveChangesButton().assertIsDisplayed()
        compose.onNodeWithText("Go back").performClick()
        compose.onAllNodes(hasText("These changes will replace values in LubeLogger:")).assertCountEquals(0)
        assertEquals("Go back sends nothing", 0, fake.updates.size)
    }

    @Test fun switchingRecordIgnoresTheSlowLoadOfThePreviousRecord() {
        val fake = FakeEditor().apply {
            serverDrafts[15] = serviceDraft("First record")
            serverDrafts[16] = serviceDraft("Second record")
            loadGates[15] = CompletableDeferred()
        }
        var target by mutableStateOf(serviceRecord(15, "First record"))
        compose.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {}, existingRecord = target.ref, editor = fake)
        }
        compose.awaitCondition(5_000) { 15 in fake.loads }
        compose.runOnIdle { target = serviceRecord(16, "Second record") }
        compose.onNodeWithText("Second record").assertIsDisplayed()
        // The delayed reply for record 15 now arrives; it must not overwrite record 16's form.
        fake.loadGates.getValue(15).complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithText("Second record").assertIsDisplayed()
        compose.onNodeWithText("First record").assertDoesNotExist()
        compose.onNodeWithText("Description").performTextClearance()
        compose.onNodeWithText("Description").performTextInput("Second record edited")
        compose.onNodeWithText("Review changes").performClick()
        saveChangesButton().performClick()
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Success }
        assertEquals(RecordId(16), fake.updates.single().first.ref.id)
    }

    @Test fun typedAddFormSurvivesActivityRecreationWithItsType() {
        val fake = FakeEditor()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {}, editor = fake)
        }
        compose.onNodeWithText("Repair").performClick()
        compose.onNodeWithText("Odometer / hours").performTextInput("1300")
        compose.onNodeWithText("Description").performTextInput("Brake pads")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Brake pads").assertIsDisplayed()
        compose.onNodeWithText("Save").performClick()
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Success }
        val saved = fake.adds.single()
        assertEquals("Restored form must keep its record type", RecordKind.REPAIR, saved.kind)
        assertEquals("Brake pads", saved.description)
    }

    @Test fun editFormSurvivesRecreationOnTheSameRecordAndKeepsTypedChanges() {
        val fake = FakeEditor().apply { serverDrafts[15] = serviceDraft("Inspection") }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {},
                existingRecord = serviceRecord(15, "Inspection").ref, editor = fake)
        }
        compose.onNodeWithText("Description").performTextClearance()
        compose.onNodeWithText("Description").performTextInput("Inspection and wipers")
        restoration.emulateSavedInstanceStateRestore()
        // The restored form reloads the server snapshot but must not overwrite the typed change.
        compose.awaitCondition(5_000) { fake.loads.size >= 2 }
        compose.onNodeWithText("Inspection and wipers").assertIsDisplayed()
        compose.onNodeWithText("Edit record").assertIsDisplayed()
        compose.onNodeWithText("Review changes").performClick()
        saveChangesButton().performClick()
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Success }
        assertEquals("A restored Edit must never become an Add", 0, fake.adds.size)
        val (snapshot, draft) = fake.updates.single()
        assertEquals(RecordRef(car.id, RecordKind.SERVICE, RecordId(15)), snapshot.ref)
        assertEquals("Inspection and wipers", draft.description)
    }

    /** After restoration the ORIGINAL baseline must still guard against a newer server record. */
    @Test fun restoredEditRefusesToOverwriteAServerChangeMadeMeanwhile() {
        val fake = FakeEditor().apply {
            serverDrafts[15] = serviceDraft("Oil change").copy(notes = "original note")
        }
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            EditorRecordDialog(listOf(car), emptyMap(), emptyMap(), initialVehicleId = car.id,
                baseUrl = "https://lubelogger.invalid", editorKey = "synthetic", gasFields = ready,
                onGasFieldsNeeded = {}, onDismiss = {}, onSaved = {},
                existingRecord = serviceRecord(15, "Oil change").ref, editor = fake)
        }
        compose.onNodeWithText("Description").performTextClearance()
        compose.onNodeWithText("Description").performTextInput("Oil and filter")
        // Another client changes only the notes while this app is in the background.
        fake.serverDrafts[15] = serviceDraft("Oil change").copy(notes = "updated by another session")
        restoration.emulateSavedInstanceStateRestore()
        compose.awaitCondition(5_000) { fake.loads.size >= 2 }
        compose.onNodeWithText("Oil and filter").assertIsDisplayed()
        compose.onNodeWithText("This record was changed in LubeLogger", substring = true).assertIsDisplayed()
        compose.onNode(hasText("Review changes") and hasClickAction()).assertIsNotEnabled()
        assertEquals("Nothing may be written over the newer server record", 0, fake.updates.size)
    }
}
