package com.kramttocs.lubelink.quality

import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kramttocs.lubelink.core.ReminderNotificationSettings
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.ui.LubeLinkApp
import com.kramttocs.lubelink.ui.LubeTheme
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Settings → Maintenance notifications → "Check reminders now" runs the real reminder check against the
 * synthetic server; when LubeLogger sends reminders LubeLink cannot read, "Refresh check status" shows that reason and
 * no successful check is claimed. Whole app. Runs on the JVM and the emulator. */
@RunWith(AndroidJUnit4::class)
class CheckNowUiTest {
    @get:org.junit.Rule val compose = createComposeRule()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val settings = SecureConfig(context)
    private val alerts = ReminderNotificationSettings(context)
    private val server = ScreenshotServer(override = { path -> if (path.startsWith("/api/vehicle/reminders/all")) "[{}]" else null })
    private val driver = TourDriver(compose) {}
    private var scope: String? = null

    @After fun cleanUp() {
        scope?.let { alerts.setEnabled(it, false) }
        settings.disconnect()
        server.close()
    }

    private fun scrollTo(text: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text))
        compose.waitForIdle()
    }

    private fun shows(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    /** On the JVM, WorkManager's test driver stands in for the job scheduler: it releases the queued check's network
     * constraint. On the emulator WorkManager runs the check itself, and there is no test driver. */
    private fun releaseCheckOnTheJvm() {
        val testDriver = androidx.work.testing.WorkManagerTestInitHelper.getTestDriver(context) ?: return
        val queued = androidx.work.WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("lubelink_reminder_check_now").get()
        queued.forEach { testDriver.setAllConstraintsMet(it.id) }
    }

    @Test fun checkNowShowsWhyTheCheckFailed() {
        ScreenshotGarage.startFresh(context)
        settings.baseUrl = server.baseUrl
        settings.saveApiKey(ScreenshotGarage.EDITOR_KEY)
        scope = alerts.accountScope(settings)!!.also { alerts.setEnabled(it, true) }
        compose.setContent { LubeTheme { Surface(Modifier.fillMaxSize()) { LubeLinkApp(settings) } } }
        driver.waitFor("2019 Honda CR-V")
        driver.tap("Settings")
        driver.tap("Maintenance notifications")
        scrollTo("Check reminders now")
        assertTrue(shows("Last successful check: Not yet"))
        driver.tap("Check reminders now")
        releaseCheckOnTheJvm()
        val reason = "Last check: LubeLogger sent data LubeLink could not read"
        compose.awaitCondition(60_000) {
            if (!shows(reason)) {
                // The button sits below "Check reminders now": bring it into view, or the tap lands off screen.
                scrollTo("Refresh check status")
                compose.onAllNodes(hasText("Refresh check status")).onFirst().performClick()
            }
            shows(reason)
        }
        assertTrue("The check is no longer shown as running", !shows("Checking reminders…"))
        assertTrue("No successful check is claimed", shows("Last successful check: Not yet"))
    }
}
