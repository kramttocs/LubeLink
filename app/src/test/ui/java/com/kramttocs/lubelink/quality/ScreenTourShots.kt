package com.kramttocs.lubelink.quality

import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick

/**
 * The screen tour's one shot list: every screen state worth looking at, with the taps
 * that reach it from the one before. Two renderers draw it: `ScreenshotWalkthroughTest` on the emulator (full CI runs),
 * and `ScreenTour` on the JVM (run locally before a push that changes a screen).
 *
 * Every shot is drawn by both, the Add/Edit dialog included: on the JVM, `JvmDialogWindowShadow` lets a dialog with
 * a text field become idle. [leave] returns to where the next shot starts.
 */
data class TourShot(
    val name: String,
    val reach: TourDriver.() -> Unit,
    val leave: TourDriver.() -> Unit = {}
)

/** The taps a shot may use, the same on the JVM and the emulator. Only emulator-only shots use [back]. */
open class TourDriver(private val compose: ComposeTestRule, private val systemBack: () -> Unit) {
    fun waitFor(text: String, substring: Boolean = false) =
        compose.awaitCondition(20_000) { compose.onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty() }
    fun waitForDescription(description: String) =
        compose.awaitCondition(20_000) { compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() }
    fun tap(text: String) { waitFor(text); compose.onAllNodes(hasText(text)).onFirst().performClick(); compose.waitForIdle() }
    fun tapDescription(description: String) {
        waitForDescription(description)
        compose.onAllNodes(hasContentDescription(description)).onFirst().performClick()
        compose.waitForIdle()
    }
    /** Waits until a loading message has gone, so the picture shows the loaded screen. */
    fun waitForGone(text: String) =
        compose.awaitCondition(20_000) { compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isEmpty() }
    /** Like [waitForDescription], but a picture that never arrives (the app keeps its placeholder) is not a failure. */
    fun tryWaitForDescription(description: String) =
        runCatching { compose.awaitCondition(10_000) { compose.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().isNotEmpty() } }
    fun tapIfPresent(text: String) {
        if (compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()) tap(text)
    }
    /** Scrolls the screen's list until the item described [description] is in view. */
    fun scrollToDescription(description: String) {
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasContentDescription(description))
        compose.waitForIdle()
    }
    /** Waits until [text] is in the screen's list and scrolls it into view: a lazy list only builds the rows on
     * screen, and the emulator's screen is shorter than the JVM's (system bars). */
    fun scrollToText(text: String) = compose.awaitCondition(20_000) {
        runCatching { compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText(text)) }.isSuccess
    }
    fun back() { systemBack(); compose.waitForIdle() }
    /** Replaces the text of the editable field now showing [current]. */
    fun replaceText(current: String, text: String) {
        waitFor(current)
        compose.onAllNodes(hasText(current) and hasSetTextAction()).onFirst().performTextReplacement(text)
        compose.waitForIdle()
    }
    /** Taps [text] in the window that also shows [marker], when several open dialogs have the same button (each
     * dialog is its own window, its own semantics root). A button tap, unlike a Back key on the emulator, cannot reach
     * a window that is still closing (shots 11 and 12 once kept the Add record form on top). */
    fun tapInWindowWith(marker: String, text: String) {
        waitFor(marker)
        fun shows(node: SemanticsNode): Boolean =
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == marker } == true || node.children.any(::shows)
        val buttons = compose.onAllNodes(hasText(text))
        val index = buttons.fetchSemanticsNodes().indexOfFirst { node ->
            var top = node
            while (true) top = top.parent ?: break
            shows(top)
        }
        check(index >= 0) { "No \"$text\" in the window showing \"$marker\"" }
        buttons[index].performClick()
        compose.waitForIdle()
    }
    /** Lets loads finish for a screen with no loading message to wait on. */
    fun settle(ms: Long = 2_000) {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) { compose.waitForIdle(); passTime(50) }
    }
}

object ScreenTourShots {
    /** Before a server is connected. */
    val setup = listOf(TourShot("00-setup", reach = { waitFor("Connect") }))

    /** Connected to [ScreenshotGarage]'s server. */
    val connected = listOf(
        TourShot("01-garage", reach = {
            waitFor("2019 Honda CR-V")
            ScreenshotGarage.cars.forEach { tryWaitForDescription("${it.year} ${it.make} ${it.model} picture") }
        }),
        TourShot("01b-garage-add-vehicle", reach = { scrollToDescription("Add a vehicle in LubeLogger") }),
        TourShot("02-garage-reports",
            reach = { tapDescription("Garage reports"); waitFor("Total recorded expenses"); waitForGone("Calculating") },
            leave = { tap("Overview") }),
        TourShot("03-vehicle", reach = { tap("2019 Honda CR-V"); waitFor("Add record"); settle() }),
        TourShot("03b-vehicle-details", reach = { tapDescription("Vehicle details"); waitFor("From LubeLogger; edit them there.") },
            leave = { tap("Close") }),
        // OD-52: a vehicle's Reports, scrolled to its tiles and odometer chart.
        TourShot("03c-vehicle-reports",
            reach = { tapDescription("Vehicle reports"); waitFor("Total recorded expenses"); waitForGone("Calculating")
                scrollToText("Odometer in this period") },
            leave = { tap("Overview"); waitFor("Add record") }),
        TourShot("04-history", reach = { tap("History"); waitFor("Oil and filter change") }),
        TourShot("05-record-details", reach = { tap("Oil and filter change"); waitFor("Close") }),
        TourShot("06-delete-confirmation",
            reach = { tapDescription("More record actions"); tap("Delete record"); waitFor("Cancel"); waitForGone("Checking record") },
            leave = { tap("Cancel"); tapIfPresent("Close") }),
        // OD-75: Edit's Save review, with what it says is kept; leaving discards the change, nothing is sent.
        TourShot("06b-edit-save-review",
            reach = { tap("Oil and filter change"); waitFor("Close"); tap("Edit record"); waitFor("Description")
                replaceText("Oil and filter change", "Oil and filter change, new drain plug gasket")
                tap("Review changes"); waitFor("Other fields and files are kept as LubeLogger had them just before saving.") },
            leave = { tap("Go back"); waitForGone("These changes will replace values in LubeLogger:")
                tapInWindowWith("Description", "Cancel"); tap("Discard"); waitForGone("Description"); tapIfPresent("Close") }),
        TourShot("07-add-service", reach = { tap("Add record"); waitFor("Description") },
            leave = { tapInWindowWith("Description", "Cancel"); waitForGone("Cost (0 if untracked)") }),
        TourShot("08-history-fuel", reach = { tap("Fuel"); waitFor("29.4", substring = true) }),
        TourShot("09-add-fuel", reach = { tap("Add record"); waitFor("Gas station (optional)") }),
        TourShot("10-station-picker",
            reach = { tapDescription("Find or choose gas station"); waitFor("Manual") }),
        // The renderers provide ScreenshotGarage.stationLookups and the List view.
        TourShot("10b-station-nearby",
            reach = { tap("Nearby"); waitFor("Synthetic Corner Garage") }),
        TourShot("10c-favorite-station",
            reach = { tapDescription("Save as favorite"); waitFor("Save favorite station") },
            // Three dialogs here each have a "Cancel": close each by its own, and wait until it has gone.
            leave = { tapInWindowWith("Save favorite station", "Cancel"); waitForGone("Save favorite station")
                tapDescription("Back to station picker"); waitForGone("Nearby gas stations")
                tapInWindowWith("Manual", "Cancel"); waitForGone("Manual")
                tapInWindowWith("Gas station (optional)", "Cancel"); waitForGone("Gas station (optional)") }),
        TourShot("11-calendar", reach = { tap("Calendar"); waitForDescription("Previous month"); waitForGone("Loading") }),
        TourShot("12-reminders", reach = { tap("Reminders"); waitFor("Tire rotation") }),
        TourShot("13-reminder-details", reach = { tap("Tire rotation"); waitFor("Close") }, leave = { tap("Close") }),
        TourShot("14-settings", reach = { tap("Settings"); waitFor("Record Fields") }),
        // OD-53: the corner choice, and the page drawn in Square; leaving restores the default.
        TourShot("15-settings-square-corners",
            reach = { tap("Garage & personalization"); scrollToText("Square"); tap("Square"); waitFor("Only the corners change.", substring = true) },
            leave = { tap("Rounded") }),
        // OD-52 and OD-59: the Kubota has no reminders in the tour's garage (ScreenshotGarage.withoutReminders),
        // so its Reminders page suggests common ones; then the switch that turns them off.
        TourShot("16-reminders-suggested", reach = {
            tap("Overview"); tap("Garage"); tap("2012 Kubota L3301"); waitFor("Add record")
            tap("Reminders"); waitFor("Common reminders to set up")
        }),
        TourShot("17-settings-suggest-reminders", reach = {
            tap("Settings"); tap("Maintenance notifications"); scrollToText("Suggest common reminders")
        })
    )
}
