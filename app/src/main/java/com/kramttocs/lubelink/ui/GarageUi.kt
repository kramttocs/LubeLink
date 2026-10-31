package com.kramttocs.lubelink.ui

import com.kramttocs.lubelink.core.VehicleId
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Add
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.GarageSection
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.Vehicle
import androidx.compose.ui.platform.LocalDensity

/** Full-vehicle Garage: List is the familiar default, Grid shows exactly two cards across. */
@Composable
internal fun GarageScreen(data: GarageData, api: LubeLoggerApi, refreshGeneration: Int,
                         nicknames: Map<VehicleId, String>, layout: SecureConfig.GarageLayout,
                         onManageVisibility: () -> Unit, onSelect: (VehicleId) -> Unit,
                         onReports: () -> Unit, onAddVehicle: () -> Unit) {
    // Like History, keep the title/action row outside either scrolling vehicle layout.
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        GarageHeading(onReports)
        Spacer(Modifier.height(10.dp))
        if (data.vehicles.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("No vehicles visible", style = MaterialTheme.typography.titleLarge)
                Text("All vehicles are hidden in LubeLink. They are still stored in LubeLogger.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onManageVisibility) { Text("Manage vehicle visibility in Settings") }
                AddVehicleTile(onAddVehicle, Modifier.height(GRID_TILE_HEIGHT))
            }
        } else if (layout == SecureConfig.GarageLayout.GRID) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = ScreenEndPadding,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(data.vehicles, key = { it.id.value }) { vehicle ->
                    GarageGridCard(vehicle, data, api, refreshGeneration, nicknames[vehicle.id], onSelect)
                }
                item(key = "add-vehicle") { AddVehicleTile(onAddVehicle, Modifier.fillMaxHeight().heightIn(min = GRID_TILE_HEIGHT)) }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = ScreenEndPadding,
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(data.vehicles, key = { it.id.value }) { vehicle ->
                    GarageListCard(vehicle, data, api, refreshGeneration, nicknames[vehicle.id], onSelect)
                }
                item(key = "add-vehicle") { AddVehicleTile(onAddVehicle, Modifier.height(LIST_TILE_HEIGHT)) }
            }
        }
    }
}

/** LubeLogger's own "add" green (its add-new-vehicle tile). */
private val AddGreen = Color(0xFF28A745)
private val GRID_TILE_HEIGHT = 145.dp
private val LIST_TILE_HEIGHT = 96.dp

/**
 * OD-38: the last Garage tile, styled like LubeLogger's own: a dashed card with a large green "+". LubeLogger's API
 * cannot create a vehicle, and its add-vehicle form has no address of its own (it is a pop-up on the main page,
 * checked on LubeLogger 1.7.4), so the tile opens LubeLogger's main page in the browser, where its "+" opens the form.
 * The new vehicle shows here after the next refresh.
 */
@Composable
internal fun AddVehicleTile(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val outline = MaterialTheme.colorScheme.outline
    val shape = panelShape
    val radius = LocalCorners.current.panelRadius
    Box(modifier.fillMaxWidth().clip(shape)
        .drawBehind {
            drawRoundRect(outline, cornerRadius = CornerRadius(radius.toPx()), style = Stroke(width = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))))
        }
        .clickable(onClick = onClick)
        .semantics(mergeDescendants = true) { contentDescription = "Add a vehicle in LubeLogger" },
        contentAlignment = Alignment.Center) {
        Icon(Icons.Outlined.Add, contentDescription = null, tint = AddGreen, modifier = Modifier.size(64.dp))
    }
}

@Composable
private fun GarageHeading(onReports: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween) {
        // The 48dp Reports button must not push Garage below the other page titles.
        // Align the title to the row's top, matching ContextHeader on History/Calendar.
        Text("Garage", style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.weight(1f).align(Alignment.Top))
        IconButton(onClick = onReports) {
            Icon(Icons.Outlined.BarChart, contentDescription = "Garage reports", tint = Gold)
        }
    }
}

/** Shared data/wording across layouts, so switching layout changes presentation only. */
@Composable
private fun GarageMaintenanceSummary(vehicle: Vehicle, data: GarageData, maxLines: Int = 1) {
    val summary = data.summaries[vehicle.id]
    val count = (summary?.urgentCount ?: 0) + (summary?.overdueCount ?: 0)
    val next = MaintenanceRules.orderedReminders(
        data.reminders.filter { it.vehicleId == vehicle.id }
    ).firstOrNull()
    val message = when {
        GarageSection.REMINDERS in data.missing -> "Reminders unavailable"
        count > 0 -> "$count maintenance ${if (count == 1) "alert" else "alerts"}"
        next != null -> "Next: ${MaintenanceRules.lineForDisplay(next.description)}"
        else -> "No reminders configured"
    }
    Text(message, color = if (count > 0) Gold else MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall, maxLines = maxLines,
        overflow = TextOverflow.Ellipsis)
}

@Composable
private fun GarageListCard(vehicle: Vehicle, data: GarageData, api: LubeLoggerApi,
                           refreshGeneration: Int, nickname: String?, onSelect: (VehicleId) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onSelect(vehicle.id) },
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            VehiclePicture(vehicle, api, refreshGeneration,
                Modifier.width(146.dp).height(92.dp), fixedFrame = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(MaintenanceRules.vehicleTitle(vehicle, nickname),
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                MaintenanceRules.vehicleSubtitle(vehicle, nickname)?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(MaintenanceRules.mileageLabel(data.summaries[vehicle.id]?.lastOdometer, vehicle.useHours),
                    style = MaterialTheme.typography.bodySmall)
                GarageMaintenanceSummary(vehicle, data)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = Gold)
        }
    }
}

@Composable
private fun GarageGridCard(vehicle: Vehicle, data: GarageData, api: LubeLoggerApi,
                           refreshGeneration: Int, nickname: String?, onSelect: (VehicleId) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().height(MaintenanceRules.garageGridHeightDp(LocalDensity.current.fontScale).dp)
        .clickable { onSelect(vehicle.id) },
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 5.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)) {
            // Identical image frames across the Garage, with Fit preserving lettering/artwork.
            // The original bitmap proportions never control the card's overall height.
            VehiclePicture(vehicle, api, refreshGeneration,
                Modifier.fillMaxWidth().height(91.dp), fixedFrame = true)
            val subtitle = MaintenanceRules.vehicleSubtitle(vehicle, nickname)
            Text(MaintenanceRules.vehicleTitle(vehicle, nickname),
                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                // Subtitle already contains canonical identity. A nickname uses one line.
                maxLines = if (subtitle == null) 2 else 1, overflow = TextOverflow.Ellipsis)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(MaintenanceRules.mileageLabel(data.summaries[vehicle.id]?.lastOdometer, vehicle.useHours),
                style = MaterialTheme.typography.labelSmall)
            Spacer(Modifier.weight(1f))
            // Compact grid: leave the full reminder text to the vehicle/reminder screen.
            GarageMaintenanceSummary(vehicle, data, maxLines = 1)
        }
    }
}
