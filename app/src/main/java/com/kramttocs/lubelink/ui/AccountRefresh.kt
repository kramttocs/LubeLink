package com.kramttocs.lubelink.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.kramttocs.lubelink.core.VehicleId
import kotlinx.coroutines.CancellationException

/** One pull-to-refresh of the screen that was showing when it was requested. */
internal data class RefreshRequest(val tab: Tab, val vehicleId: VehicleId?, val includeFuel: Boolean, val generation: Int)

/**
 * Runs pull-to-refresh [request] as an effect of [account]. A credential change replaces the
 * account, which disposes this effect and cancels a request still in flight, so nothing fetched with
 * a previous key is ever published. [history] must be the holder for the same account
 * (keyed to [AccountDataState.historySource]). Refresh is read-only and never calls the Editor API.
 * [onDone] receives a failure message, or null, when the request finishes; displayed data is kept on
 * failure.
 */
@Composable
internal fun AccountRefreshEffect(account: AccountDataState, history: VehicleHistoryState, nav: NavigationState,
                                  request: RefreshRequest?, includeFuelNow: Boolean, onDone: (failure: String?) -> Unit) {
    val fuelNow by rememberUpdatedState(includeFuelNow)
    val done by rememberUpdatedState(onDone)
    LaunchedEffect(account, request) {
        val r = request ?: return@LaunchedEffect
        val failure = try {
            account.refreshGarage()
            nav.selectedId?.let { if (it !in account.visibleVehicleIds) nav.selectedId = null }
            when (r.tab) {
                Tab.HOME, Tab.HISTORY -> if (r.vehicleId != null) {
                    // The user may have moved to another vehicle or Fuel filter during the Garage refresh.
                    // Then this refresh is abandoned: it must not take a ticket, which would make the
                    // current vehicle's own load stale. Its own keyed load shows its History.
                    fun stillCurrent() = nav.selectedId == r.vehicleId && fuelNow == r.includeFuel
                    if (stillCurrent()) {
                        // Keep showing the current History while refreshing; a newer load wins.
                        val ticket = history.begin(clear = false)
                        try {
                            val refreshed = account.historySource.loadHistory(r.vehicleId, r.includeFuel)
                            if (stillCurrent()) history.publish(ticket, refreshed)
                        } finally { history.settle(ticket) }
                    }
                } else if (r.tab == Tab.HISTORY) {
                    account.refreshCalendar(r.includeFuel, r.generation) { fuelNow == r.includeFuel }
                }
                Tab.CALENDAR -> account.refreshCalendar(r.includeFuel, r.generation) { fuelNow == r.includeFuel }
                Tab.REMINDERS, Tab.SETTINGS, Tab.REPORTS -> Unit
            }
            // Another tab may need a fresh aggregate the next time it is opened.
            if (r.tab != Tab.CALENDAR && !(r.tab == Tab.HISTORY && r.vehicleId == null)) account.invalidateCalendar()
            null
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { "Refresh failed: ${safeMessage(e)}" }
        done(failure)
    }
}
