package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.LubeLoggerManagerApi
import com.kramttocs.lubelink.core.LubeLoggerManagerApi.DeleteSnapshot
import com.kramttocs.lubelink.core.RecordDeleteGateway
import com.kramttocs.lubelink.core.RecordId
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordRef
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.Vehicle
import com.kramttocs.lubelink.core.VehicleId
import com.kramttocs.lubelink.ui.DeleteRecordDialog
import com.kramttocs.lubelink.ui.WriteSafety
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The real Delete dialog and the real WriteSafety lock, with a fake LubeLogger Manager client. Synthetic data only;
 * nothing leaves the emulator. Each test checks what the fake was asked to delete, not only what the screen shows.
 */
@RunWith(AndroidJUnit4::class)
class DeleteRecordUiTest {
    @get:Rule val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val car = Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "", useHours = false)

    private class FakeManager : RecordDeleteGateway {
        val prepared = CopyOnWriteArrayList<RecordRef>()
        val deleted = CopyOnWriteArrayList<RecordRef>()
        val prepareGates = mutableMapOf<Int, CompletableDeferred<Unit>>()
        var prepareFailure: String? = null
        override suspend fun prepare(vehicle: Vehicle, displayed: ServiceRecord): DeleteSnapshot {
            prepared += displayed.ref
            prepareGates[displayed.id.value]?.await()
            prepareFailure?.let { throw LubeLoggerManagerApi.ManagerException(it) }
            return DeleteSnapshot(vehicle, displayed.ref, "signature-${displayed.id.value}")
        }
        override suspend fun delete(snapshot: DeleteSnapshot) { deleted += snapshot.ref }
    }

    private fun record(id: Int, description: String) = ServiceRecord(RecordId(id), car.id, "2026-10-01",
        description, 1200, "45.50", "", "", emptyList(), RecordKind.SERVICE)

    @Before fun cleanLock() {
        assertTrue("Run on a disposable emulator with no pending real write", WriteSafety.unresolved(context) == null)
        assertTrue("WriteSafety must start idle", WriteSafety.state is WriteSafety.State.Idle)
    }

    @After fun releaseLock() {
        // awaitCondition drives the main thread on the JVM (Robolectric) as well as on the emulator; a sleep loop
        // would leave a JVM run waiting for a write that can never finish.
        compose.awaitCondition(10_000) { WriteSafety.state !is WriteSafety.State.Pending }
        compose.runOnIdle { if (WriteSafety.state !is WriteSafety.State.Idle) WriteSafety.acknowledge(context, checkedServer = true) }
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Idle }
        context.getSharedPreferences(StoredFormat.UNCONFIRMED_WRITE.fileName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun show(fake: FakeManager, shown: () -> ServiceRecord, onDismiss: () -> Unit = {}) {
        compose.setContent {
            DeleteRecordDialog(car, shown(), "2015 Synthetic Wagon", "https://lubelogger.invalid", "synthetic-manager",
                onDismiss = onDismiss, manager = fake)
        }
    }

    @Test fun cancelSendsNothing() {
        val fake = FakeManager()
        var dismissed = false
        show(fake, { record(15, "Synthetic brake service") }) { dismissed = true }
        compose.awaitCondition(5_000) { fake.prepared.size == 1 }
        compose.onNodeWithText("Delete record").assertIsEnabled()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertTrue(dismissed)
        assertEquals("Cancel must not delete", 0, fake.deleted.size)
    }

    @Test fun repeatedDeleteTapsSendOneDelete() {
        val fake = FakeManager()
        // The dialog is left open (onDismiss does nothing), so every tap reaches the real button and write lock.
        show(fake, { record(15, "Synthetic brake service") })
        compose.awaitCondition(5_000) { fake.prepared.size == 1 }
        repeat(3) { runCatching { compose.onNodeWithText("Delete record").performClick() } }
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Success }
        assertEquals(listOf(RecordRef(car.id, RecordKind.SERVICE, RecordId(15))), fake.deleted.toList())
    }

    @Test fun aRecordThatChangedCannotBeDeleted() {
        val fake = FakeManager().apply { prepareFailure = "Record changed since it was displayed. Refresh before deleting." }
        show(fake, { record(15, "Synthetic brake service") })
        compose.awaitCondition(5_000) { fake.prepared.size == 1 }
        compose.onNodeWithText("Record changed since it was displayed. Refresh before deleting.").assertExists()
        compose.onNodeWithText("Delete record").assertIsNotEnabled()
        assertEquals(0, fake.deleted.size)
    }

    @Test fun aLateCheckForThePreviousRecordNeverArmsDeleteForTheNewOne() {
        val fake = FakeManager().apply { prepareGates[15] = CompletableDeferred() }
        var shown by mutableStateOf(record(15, "Synthetic brake service"))
        show(fake, { shown })
        compose.awaitCondition(5_000) { fake.prepared.size == 1 }
        shown = record(16, "Synthetic oil change")
        compose.awaitCondition(5_000) { fake.prepared.size == 2 }
        fake.prepareGates.getValue(15).complete(Unit) // the old check finishes after the switch
        compose.waitForIdle()
        compose.onNodeWithText("Delete record").performClick()
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Success }
        assertEquals("Only the record on screen may be deleted",
            listOf(RecordRef(car.id, RecordKind.SERVICE, RecordId(16))), fake.deleted.toList())
    }
}
