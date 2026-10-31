package com.kramttocs.lubelink.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.ReminderId
import com.kramttocs.lubelink.core.VehicleId

internal enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Overview", Icons.Outlined.Home),
    HISTORY("History", Icons.Outlined.History),
    CALENDAR("Calendar", Icons.Outlined.DateRange),
    REMINDERS("Reminders", Icons.Outlined.NotificationsNone),
    SETTINGS("Settings", Icons.Outlined.Settings),
    REPORTS("Reports", Icons.Outlined.History)
}

/**
 * Which tab and vehicle context is showing, and where Back returns to. The tab, the selected
 * vehicle (null = All vehicles) and whether the startup context was applied survive process death;
 * the Back trail does not.
 */
internal class NavigationState(
    tabState: MutableState<Tab>,
    selectedState: MutableState<VehicleId?>,
    initializedState: MutableState<Boolean>
) {
    var tab by tabState
    var selectedId by selectedState
    private var contextInitialized by initializedState

    /** Back from a vehicle opened from another tab returns there. */
    var returnFromVehicle by mutableStateOf<Pair<Tab, VehicleId?>?>(null)
        private set
    private var reportReturn by mutableStateOf<Pair<Tab, VehicleId?>?>(null)
    var reportStartingVehicleId by mutableStateOf<VehicleId?>(null)
        private set
    /** A reminder to highlight after opening the app from a notification. */
    var highlightedReminderId by mutableStateOf<ReminderId?>(null)

    /**
     * After a Garage load: keep the current (possibly restored) selection while it is still visible;
     * on the very first load apply the startup preference instead. Refresh never jumps from All
     * vehicles back to the last vehicle.
     */
    fun reconcileSelection(visible: Collection<VehicleId>, startupChoice: () -> VehicleId?) {
        selectedId = if (contextInitialized) selectedId?.takeIf { it in visible }
            else startupChoice()?.takeIf { it in visible }
        contextInitialized = true
    }

    fun goToGarage() {
        selectedId = null
        returnFromVehicle = null
        tab = Tab.HOME
    }

    fun selectFromGarage(vehicleId: VehicleId) {
        selectedId = vehicleId
        returnFromVehicle = null
    }

    /** Open a vehicle's Overview from another screen; Back returns to that screen. */
    fun openVehicle(vehicleId: VehicleId) {
        returnFromVehicle = tab to selectedId
        selectedId = vehicleId
        tab = Tab.HOME
    }

    fun switchTab(destination: Tab) {
        if (tab == Tab.REPORTS) reportReturn = null
        tab = destination
        returnFromVehicle = null
    }

    fun openReports(startingVehicle: VehicleId?) {
        reportStartingVehicleId = startingVehicle
        reportReturn = tab to selectedId
        tab = Tab.REPORTS
    }

    fun closeReports() {
        val prior = reportReturn
        tab = prior?.first ?: Tab.HOME
        selectedId = prior?.second
        reportReturn = null
    }

    /** Back from an opened vehicle. Returns false when there is nowhere specific to return to. */
    fun backFromVehicle(): Boolean {
        val (previousTab, previousId) = returnFromVehicle ?: return false
        selectedId = previousId
        tab = previousTab
        returnFromVehicle = null
        return true
    }

    /** Ordinary Back: another tab goes to Overview; a vehicle's Overview goes to the Garage. */
    val canGoBackToGarage: Boolean get() = tab != Tab.HOME || selectedId != null
    fun backTowardGarage() {
        if (tab != Tab.HOME) tab = Tab.HOME else selectedId = null
    }

    fun showReminderFromNotification(matched: MaintenanceReminder?) {
        selectedId = matched?.vehicleId
        highlightedReminderId = matched?.id
        tab = Tab.REMINDERS
        returnFromVehicle = null
    }

    fun forgetVehicle(vehicleId: VehicleId) {
        if (selectedId == vehicleId) selectedId = null
    }
}

@Composable
internal fun rememberNavigationState(): NavigationState {
    val tab = rememberSaveable { mutableStateOf(Tab.HOME) }
    val selected = rememberSaveable(stateSaver = OptionalVehicleIdSaver) { mutableStateOf<VehicleId?>(null) }
    val initialized = rememberSaveable { mutableStateOf(false) }
    return remember { NavigationState(tab, selected, initialized) }
}
