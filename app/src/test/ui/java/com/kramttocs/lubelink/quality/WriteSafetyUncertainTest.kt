package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.EditorDraft
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.LubeLoggerEditorApi
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordWriteIntent
import com.kramttocs.lubelink.core.StoredFormat
import com.kramttocs.lubelink.core.WriteAction
import com.kramttocs.lubelink.ui.WriteSafety
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlinx.coroutines.runBlocking

/** When a write client reports the outcome as uncertain (for example a read-back LubeLink cannot read), the
 * real WriteSafety lock shows "uncertain", keeps its durable marker and refuses the next write until the user has
 * checked LubeLogger. The same, end to end, for the real Editor client's Fuel Add with no station whose
 * read-back has no usable custom-field list. Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class WriteSafetyUncertainTest {
    @get:Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val intent = RecordWriteIntent(WriteAction.EDIT, RecordKind.SERVICE)

    @Before fun idle() {
        // Empty content, so each wait for idle returns at once instead of after its time limit (about 2 s each).
        compose.setContent { }
        assertTrue("Run with no pending write", WriteSafety.unresolved(context) == null &&
            WriteSafety.state is WriteSafety.State.Idle)
    }

    @After fun unlock() {
        compose.awaitCondition(10_000) { WriteSafety.state !is WriteSafety.State.Pending }
        compose.runOnIdle { if (WriteSafety.state !is WriteSafety.State.Idle) WriteSafety.acknowledge(context, checkedServer = true) }
        compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Idle }
        context.getSharedPreferences(StoredFormat.UNCONFIRMED_WRITE.fileName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun anUncertainReadBackKeepsTheLock() {
        val reason = "Edit acknowledged, but LubeLink could not read the saved record back. Verify in LubeLogger."
        var sent = 0
        compose.runOnIdle {
            assertTrue(WriteSafety.start(context, "Edit Service for synthetic car", intent) {
                sent++
                throw LubeLoggerEditorApi.WriteUncertainException(reason)
            })
        }
        compose.awaitCondition(10_000) { WriteSafety.state !is WriteSafety.State.Pending }
        assertEquals(WriteSafety.State.Uncertain(reason), WriteSafety.state)
        assertEquals("The durable marker stays", "Edit Service for synthetic car", WriteSafety.unresolved(context))
        compose.runOnIdle {
            assertFalse("A new write is refused while uncertain",
                WriteSafety.start(context, "Another edit", intent) { sent++ })
        }
        assertEquals("The operation ran once and nothing else was sent", 1, sent)
    }

    @Test fun aFuelAddWhoseCustomFieldsCannotBeReadKeepsTheLock() {
        val fuelIntent = RecordWriteIntent(WriteAction.ADD, RecordKind.FUEL)
        val draft = EditorDraft(RecordKind.FUEL, "2026-10-20", "48300", "", "45.67", "", "", gallons = "12.5")
        // The read-back matches every value sent; only its custom-field list is unusable.
        fun readBack(extras: String) = "[{\"id\":${ScreenshotServer.ADDED_ID},\"vehicleId\":1,\"date\":\"2026-10-20\"," +
            "\"odometer\":48300,\"cost\":45.67,\"notes\":\"\",\"tags\":\"\",\"fuelConsumed\":12.5,\"fuelEconomy\":0," +
            "\"isFillToFull\":true,\"missedFuelUp\":false,\"startingSoc\":0,\"endingSoc\":0,\"files\":[]$extras}]"
        for ((label, extras) in listOf("an object" to ",\"extraFields\":{}", "null" to ",\"extraFields\":null",
                "a number" to ",\"extraFields\":7", "no list at all" to "",
                "a station entry without its value" to ",\"extraFields\":[{\"name\":\"LubeLink Gas Station\"}]")) {
            ScreenshotServer(override = { path ->
                if (path.startsWith("/api/vehicle/gasrecords?") && "id=${ScreenshotServer.ADDED_ID}" in path) readBack(extras) else null
            }, acceptAdds = true).use { server ->
                val car = runBlocking { LubeLoggerApi(server.baseUrl, ScreenshotGarage.EDITOR_KEY).loadGarage() }
                    .vehicles.first { it.id.value == 1 }
                val editor = LubeLoggerEditorApi(server.baseUrl, ScreenshotGarage.EDITOR_KEY)
                var sent = 0
                var outcome = "not finished"
                compose.runOnIdle {
                    assertTrue(WriteSafety.start(context, "Add Fuel for synthetic car", fuelIntent) {
                        sent++
                        try { editor.addRecord(car, car.id, draft); outcome = "confirmed as saved" }
                        catch (e: Exception) { outcome = e.javaClass.simpleName; throw e }
                    })
                }
                compose.awaitCondition(10_000) { outcome != "not finished" }
                assertEquals("Read-back with $label: the client's outcome", "WriteUncertainException", outcome)
                compose.awaitCondition(10_000) { WriteSafety.state !is WriteSafety.State.Pending }
                assertTrue("Read-back with $label: expected uncertain, got ${WriteSafety.state}",
                    WriteSafety.state is WriteSafety.State.Uncertain)
                assertEquals("Read-back with $label: the durable marker stays", "Add Fuel for synthetic car",
                    WriteSafety.unresolved(context))
                compose.runOnIdle {
                    assertFalse("Read-back with $label: a new write is refused while uncertain",
                        WriteSafety.start(context, "Another add", fuelIntent) { sent++ })
                }
                assertEquals("Read-back with $label: one Add reached the server", 1, server.posts.get())
                assertEquals(1, sent)
                compose.runOnIdle { WriteSafety.acknowledge(context, checkedServer = true) }
                compose.awaitCondition(10_000) { WriteSafety.state is WriteSafety.State.Idle }
            }
        }
    }
}
