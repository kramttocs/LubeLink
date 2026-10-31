package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordWriteIntent
import com.kramttocs.lubelink.core.WriteAction
import com.kramttocs.lubelink.ui.WriteSafety
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Disposable emulator only. Exercises a recovered marker when runtime State is Idle. */
@RunWith(AndroidJUnit4::class)
class WriteSafetyRecoveryUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun successfulReconciliationMakesRecoveredLockDisappearWithoutRestart() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("lubelink_unconfirmed_writes", Context.MODE_PRIVATE)
        assertTrue("Run against a clean disposable emulator", prefs.getString("pending", null) == null)
        val intent = RecordWriteIntent(WriteAction.EDIT, RecordKind.SERVICE)
        try {
            assertTrue(prefs.edit().putString("pending", "Synthetic interrupted write")
                .putString("pending_intent", intent.persistedCode()).commit())
            compose.setContent {
                // Same observable dependency used by LubeLinkApp when drawing its lock dialog.
                val markerRevision = WriteSafety.markerRevision
                val marker = remember(markerRevision) { WriteSafety.unresolved(context) }
                Text(if (marker != null) "Recovered lock" else "Unlocked")
            }
            compose.onNodeWithText("Recovered lock").assertExists()
            compose.runOnIdle { WriteSafety.acknowledge(context, checkedServer = true) }
            compose.waitUntil(10_000) { prefs.getString("pending", null) == null }
            // The marker revision is published on the main thread after the IO commit; wait for
            // the screen itself rather than asserting in the gap. A real regression still fails.
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Unlocked").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Unlocked").assertExists()
        } finally {
            prefs.edit().remove("pending").remove("pending_intent").commit()
        }
    }
}
