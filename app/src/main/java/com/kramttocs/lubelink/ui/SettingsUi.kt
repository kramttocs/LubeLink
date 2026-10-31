package com.kramttocs.lubelink.ui

import com.kramttocs.lubelink.core.AlertLevel
import com.kramttocs.lubelink.core.CornerStyle
import com.kramttocs.lubelink.core.VehicleId
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.kramttocs.lubelink.core.ReminderNotificationSettings
import com.kramttocs.lubelink.core.ReminderNotificationScheduler
import java.time.Instant
import java.time.ZoneId
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.Vehicle
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

/** Settings home is deliberately short; vehicle-by-vehicle controls live one level deeper. */
private enum class SettingsPage(val title: String) {
    HOME("Settings"), GARAGE("Garage & personalization"),
    NICKNAMES("Vehicle nicknames"), VISIBILITY("Vehicle visibility"),
    RECORDS("Records & display"), NOTIFICATIONS("Maintenance notifications"),
    STATIONS("Gas station search"), FIELDS("Record Fields"), CONNECTION("Connection")
}

@Composable
private fun SettingsNavigationRow(icon: ImageVector, title: String, description: String,
                                  onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = Gold, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun SettingsScreen(settings: SecureConfig, data: GarageData, includeFuel: Boolean,
                           nicknames: Map<VehicleId, String>, onNicknameChange: (VehicleId, String) -> Unit,
                           onFuelChange: (Boolean) -> Unit,
                           garageLayout: SecureConfig.GarageLayout,
                           onGarageLayoutChanged: (SecureConfig.GarageLayout) -> Unit,
                           cornerStyle: CornerStyle, onCornerStyleChanged: (CornerStyle) -> Unit,
                           hiddenVehicleIds: Set<VehicleId>, onVehicleVisibilityChanged: (VehicleId, Boolean) -> Unit,
                           newestFirst: Boolean, onHistorySortChanged: (Boolean) -> Unit,
                           suggestReminders: Boolean, onSuggestRemindersChanged: (Boolean) -> Unit,
                           onStartupChanged: (SecureConfig.Startup) -> Unit,
                           onFavoriteChanged: (VehicleId?) -> Unit, onRefreshImages: () -> Unit,
                           editorEnabled: Boolean, managerEnabled: Boolean,
                           onEditorKeyChanged: () -> Unit, onManagerKeyChanged: () -> Unit,
                           gasFields: com.kramttocs.lubelink.core.GasStationFieldCheck,
                           gasFieldError: String?, gasFieldChecking: Boolean,
                           onCheckGasFields: () -> Unit,
                           onDisconnect: () -> Unit) {
    val settingsContext = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val keyChecks = LocalKeyChecks.current
    var page by remember { mutableStateOf(SettingsPage.HOME) }
    var startup by remember { mutableStateOf(settings.startup) }
    var favorite by remember { mutableStateOf(settings.favoriteVehicleId) }
    var editingNicknameVehicleId by remember { mutableStateOf<VehicleId?>(null) }
    var editorDialog by remember { mutableStateOf(false) }
    var managerDialog by remember { mutableStateOf(false) }
    var confirmRemoveManager by remember { mutableStateOf(false) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var geoapifyDialog by remember { mutableStateOf(false) }
    var geoapifyRevision by remember { mutableIntStateOf(0) }
    // The saved-key test and its result belong to the key it checked and to this page: replacing or removing the key,
    // or leaving the page, cancels a running test and clears what it showed.
    val geoTest = rememberLatestCall(geoapifyRevision, page)
    var geoTestStatus by remember(geoapifyRevision, page) { mutableStateOf<String?>(null) }
    var geoTestFailed by remember(geoapifyRevision, page) { mutableStateOf(false) }
    var confirmRemoveGeoapify by remember { mutableStateOf(false) }
    var favoriteDialog by remember { mutableStateOf(false) }
    var showGasFieldSetup by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(page) {
        if (page == SettingsPage.FIELDS &&
            gasFields.readiness == com.kramttocs.lubelink.core.FieldReadiness.UNKNOWN) onCheckGasFields()
    }

    BackHandler(enabled = page != SettingsPage.HOME) {
        page = when (page) {
            SettingsPage.NICKNAMES, SettingsPage.VISIBILITY -> SettingsPage.GARAGE
            else -> SettingsPage.HOME
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = ScreenEndPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (page != SettingsPage.HOME) {
                    IconButton(onClick = {
                        page = if (page == SettingsPage.NICKNAMES || page == SettingsPage.VISIBILITY)
                            SettingsPage.GARAGE else SettingsPage.HOME
                    }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back") }
                }
                Text(page.title, style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.weight(1f))
            }
        }
        when (page) {
            SettingsPage.HOME -> {
                item {
                    SettingsNavigationRow(Icons.Outlined.DirectionsCar,
                        "Garage & personalization", "Layout, startup, nicknames, hidden vehicles") {
                        page = SettingsPage.GARAGE
                    }
                }
                item {
                    SettingsNavigationRow(Icons.Outlined.Tune,
                        "Records & display", "History order and fuel visibility") {
                        page = SettingsPage.RECORDS
                    }
                }
                item {
                    SettingsNavigationRow(Icons.Outlined.NotificationsNone,
                        "Maintenance notifications", "Background checks and vehicle alerts") {
                        page = SettingsPage.NOTIFICATIONS
                    }
                }
                item {
                    SettingsNavigationRow(Icons.Outlined.Tune,
                        "Record Fields", "Gas Station field setup and verification") {
                        page = SettingsPage.FIELDS
                    }
                }
                item {
                    SettingsNavigationRow(Icons.Outlined.LocalGasStation,
                        "Gas station search", "Optional Geoapify API key and Overpass fallback") {
                        page = SettingsPage.STATIONS
                    }
                }
                item {
                    SettingsNavigationRow(Icons.Outlined.Settings,
                        "Connection", "Server, Editor key, optional Manager key") {
                        page = SettingsPage.CONNECTION
                    }
                }
                item {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    SettingsNavigationRow(Icons.AutoMirrored.Outlined.OpenInNew,
                        "Open LubeLogger Settings", "Advanced settings in the web interface") {
                        // Use an ordinary browser login session; the API key is not included in the URL.
                        uriHandler.openUri(settings.baseUrl.trimEnd('/') + "/Home?tab=settings")
                    }
                }
                item {
                    Text("LubeLink v${appVersion()}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    // App-wide limitation belongs under the app version, not in Connection.
                    // Keep it legible and quiet rather than displaying a warning card.
                    Text("Saving a service record doesn't automatically complete or advance reminders. " +
                        "Manage reminders in LubeLogger.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                }
            }
            SettingsPage.GARAGE -> {
                item { Text("Startup view", style = MaterialTheme.typography.titleMedium) }
                items(SecureConfig.Startup.entries) { option ->
                    val label = when (option) {
                        SecureConfig.Startup.LAST -> "Last used vehicle"
                        SecureConfig.Startup.GARAGE -> "Garage / all vehicles"
                        SecureConfig.Startup.FAVORITE -> "Favorite vehicle"
                    }
                    Row(Modifier.fillMaxWidth().clip(panelShape)
                        .background(Panel).selectable(
                            selected = startup == option, role = Role.RadioButton,
                            onClick = { startup = option; onStartupChanged(option) })
                        .padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        DecorativeGlyph(if (startup == option) "●" else "○")
                        Spacer(Modifier.width(12.dp))
                        Text(label)
                    }
                }
                if (startup == SecureConfig.Startup.FAVORITE) item {
                    OutlinedButton(onClick = { favoriteDialog = true },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(data.vehicles.firstOrNull { it.id == favorite }?.let {
                            "Favorite: ${MaintenanceRules.vehicleContextLabel(it, nicknames[it.id])}"
                        } ?: "Choose favorite vehicle")
                    }
                }
                item {
                    Spacer(Modifier.height(2.dp))
                    Text("Garage layout", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = garageLayout == SecureConfig.GarageLayout.GRID,
                            onClick = { onGarageLayoutChanged(SecureConfig.GarageLayout.GRID) },
                            label = { Text("Grid · 2 across") })
                        FilterChip(selected = garageLayout == SecureConfig.GarageLayout.LIST,
                            onClick = { onGarageLayoutChanged(SecureConfig.GarageLayout.LIST) },
                            label = { Text("List") })
                    }
                }
                item {
                    Spacer(Modifier.height(2.dp))
                    Text("Corners", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = cornerStyle == CornerStyle.ROUNDED,
                            onClick = { onCornerStyleChanged(CornerStyle.ROUNDED) }, label = { Text("Rounded") })
                        FilterChip(selected = cornerStyle == CornerStyle.SQUARE,
                            onClick = { onCornerStyleChanged(CornerStyle.SQUARE) }, label = { Text("Square") })
                    }
                    Text("Buttons, fields, cards and dialogs. Only the corners change.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp))
                }
                item {
                    SettingsNavigationRow(Icons.Outlined.Edit, "Vehicle nicknames",
                        "${nicknames.size} custom name${if (nicknames.size == 1) "" else "s"} on this device") {
                        page = SettingsPage.NICKNAMES
                    }
                }
                item {
                    SettingsNavigationRow(Icons.Outlined.Visibility, "Vehicle visibility",
                        "${hiddenVehicleIds.size} hidden · No changes to LubeLogger") {
                        page = SettingsPage.VISIBILITY
                    }
                }
            }
            SettingsPage.NICKNAMES -> {
                item { Text("Nicknames are stored on this device, not in LubeLogger.",
                    style = MaterialTheme.typography.bodySmall) }
                items(data.vehicles, key = { "nickname-${it.id}" }) { vehicle ->
                    Row(Modifier.fillMaxWidth().clickable { editingNicknameVehicleId = vehicle.id }
                        .padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(nicknames[vehicle.id]?.takeIf { it.isNotBlank() } ?: vehicle.displayName,
                                fontWeight = FontWeight.Medium)
                            Text(if (nicknames[vehicle.id].isNullOrBlank()) "Set nickname" else vehicle.displayName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Outlined.Edit, contentDescription = "Edit nickname", tint = Gold)
                    }
                    HorizontalDivider()
                }
            }
            SettingsPage.VISIBILITY -> {
                item {
                    Text("Hidden vehicles disappear from LubeLink's Garage, all-vehicle views and record pickers. " +
                        "They remain unchanged in LubeLogger and can always be shown again here.",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (hiddenVehicleIds.isNotEmpty()) item {
                    TextButton(onClick = {
                        data.vehicles.filter { it.id in hiddenVehicleIds }.forEach {
                            onVehicleVisibilityChanged(it.id, false)
                        }
                    }) { Text("Show all vehicles") }
                }
                items(data.vehicles, key = { "visibility-${it.id}" }) { vehicle ->
                    val visible = vehicle.id !in hiddenVehicleIds
                    Row(Modifier.fillMaxWidth().toggleable(visible, role = Role.Switch,
                        onValueChange = { onVehicleVisibilityChanged(vehicle.id, !it) })
                        .padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(if (visible) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                            contentDescription = null, tint = if (visible) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(Modifier.weight(1f)) {
                            Text(MaintenanceRules.vehicleTitle(vehicle, nicknames[vehicle.id]),
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (!nicknames[vehicle.id].isNullOrBlank()) {
                                Text(vehicle.displayName, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(checked = visible, onCheckedChange = null)
                    }
                    HorizontalDivider()
                }
            }
            SettingsPage.NOTIFICATIONS -> {
                item {
                    MaintenanceNotificationSettings(settings, data)
                }
                // OD-59: the common-reminder suggestions on Reminders (OD-52) can be turned off.
                item {
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth().toggleable(suggestReminders, role = Role.Switch,
                        onValueChange = onSuggestRemindersChanged).padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Suggest common reminders")
                            Text("For a vehicle with no reminders, on the Reminders page",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = suggestReminders, onCheckedChange = null)
                    }
                }
            }
            SettingsPage.RECORDS -> {
                item {
                    Row(Modifier.fillMaxWidth().toggleable(includeFuel, role = Role.Switch,
                        onValueChange = onFuelChange).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("Show fuel in maintenance history", modifier = Modifier.weight(1f))
                        Switch(checked = includeFuel, onCheckedChange = null)
                    }
                    HorizontalDivider()
                }
                item {
                    Row(Modifier.fillMaxWidth().toggleable(newestFirst, role = Role.Switch,
                        onValueChange = onHistorySortChanged).padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Newest history records first")
                            Text("Applies to the History tab", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = newestFirst, onCheckedChange = null)
                    }
                }
            }
            SettingsPage.FIELDS -> {
                item {
                    Text("Check the optional LubeLogger fields used by LubeLink.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = Panel),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Outlined.LocalGasStation, contentDescription = null, tint = Gold)
                                Text("Gas Stations", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                Text(if (gasFieldChecking) "Checking…" else when (gasFields.readiness) {
                                    com.kramttocs.lubelink.core.FieldReadiness.READY -> "Ready"
                                    com.kramttocs.lubelink.core.FieldReadiness.MISSING -> "Setup needed"
                                    else -> "Not checked"
                                }, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (gasFields.readiness == com.kramttocs.lubelink.core.FieldReadiness.MISSING) {
                                Text("The station picker is hidden until its optional Text fields are configured.",
                                    style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text("Fuel records can store station names and addresses in LubeLogger.",
                                    style = MaterialTheme.typography.bodySmall)
                            }
                            if (gasFieldError != null) Text(gasFieldError,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { showGasFieldSetup = !showGasFieldSetup }) {
                                Text(if (showGasFieldSetup) "Hide field setup" else "View field setup")
                                Icon(Icons.Outlined.ChevronRight, contentDescription = null,
                                    modifier = Modifier.rotate(if (showGasFieldSetup) -90f else 90f))
                            }
                            if (showGasFieldSetup) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                                Text("Record type: Fuel (GasRecord)",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                com.kramttocs.lubelink.core.RecordFieldRules.requiredGasFields.forEach { name ->
                                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                            Text(name, modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold)
                                            val configured = gasFields.readiness == com.kramttocs.lubelink.core.FieldReadiness.READY ||
                                                (gasFields.readiness == com.kramttocs.lubelink.core.FieldReadiness.MISSING && name !in gasFields.missing)
                                            val stateLabel = when {
                                                configured -> "Configured"
                                                gasFields.readiness == com.kramttocs.lubelink.core.FieldReadiness.MISSING -> "Missing / invalid"
                                                else -> "Not verified"
                                            }
                                            Text(stateLabel, style = MaterialTheme.typography.labelSmall,
                                                color = if (configured) Gold else MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Text("Text · Optional", style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                                Text("In LubeLogger (Root), open Settings → Manage Extra Fields and add these fields to Fuel.",
                                    style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = {
                                    val clipboard = settingsContext.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(
                                        "LubeLink extra-field names",
                                        com.kramttocs.lubelink.core.RecordFieldRules.requiredGasFields.joinToString("\n")))
                                }, modifier = Modifier.fillMaxWidth()) { Text("Copy field names") }
                            }
                        }
                    }
                }
                item {
                    OutlinedButton(onClick = onCheckGasFields,
                        enabled = !gasFieldChecking,
                        modifier = Modifier.fillMaxWidth()) { Text("Check configuration") }
                }
                item {
                    Text("Service, Repair and Upgrade Places support is planned for a future update.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SettingsPage.STATIONS -> {
                item {
                    val isConfigured = remember(geoapifyRevision) {
                        !settings.readGeoapifyKey().isNullOrBlank()
                    }
                    Text("Geoapify Places API", style = MaterialTheme.typography.titleMedium)
                    Text(if (isConfigured) "Geoapify key saved on this device. Searches use Geoapify first. " +
                        "If it fails, LubeLink tries Overpass."
                         else "No Geoapify key configured. Searches continue using public Overpass servers.",
                        style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(onClick = { geoapifyDialog = true },
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (isConfigured) "Replace Geoapify key" else "Add Geoapify key")
                    }
                    if (isConfigured) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(enabled = !geoTest.busy, onClick = {
                            geoTestStatus = null
                            geoTest.run({
                                val saved = settings.readGeoapifyKey()
                                    ?: throw IllegalStateException("No Geoapify key configured")
                                keyChecks.testGeoapifyKey(saved)
                            }, onSuccess = {
                                geoTestFailed = false
                                geoTestStatus = "Connection successful. Places API is accessible."
                            }, onFailure = {
                                geoTestFailed = true
                                geoTestStatus = "Connection failed. Check your Geoapify key and restrictions."
                            })
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (geoTest.busy) "Testing…" else "Test connection")
                        }
                        geoTestStatus?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall,
                                color = if (geoTestFailed) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(6.dp))
                        TextButton(onClick = { confirmRemoveGeoapify = true }) { Text("Remove Geoapify key") }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Optional: create your own key at geoapify.com. Location-based searches " +
                        "send coordinates to Geoapify when enabled, or Overpass otherwise. " +
                        "OpenFreeMap supplies map tiles for the viewed area; Android's device geocoder " +
                        "may look up addresses using your device's geocoding service. " +
                        "Your LubeLogger Editor key is never sent to mapping providers.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    Text("Station names and addresses chosen for fuel records are saved in LubeLogger " +
                        "and don't require a live map lookup to view later.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            SettingsPage.CONNECTION -> {
                item {
                    Text("LubeLogger server", style = MaterialTheme.typography.titleMedium)
                    Text(settings.baseUrl, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(if (editorEnabled) "One Editor key handles browsing and record editing."
                         else "Add an Editor key to enable record editing.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { editorDialog = true }) {
                        Text(if (editorEnabled) "Replace Editor key" else "Use Editor key")
                    }
                }
                item {
                    HorizontalDivider()
                    Text("Record deletion", style = MaterialTheme.typography.titleMedium)
                    Text(if (managerEnabled) "Manager key saved. Delete appears under ⋮ on record details."
                         else "Optional Manager key required for deletion. Your Editor key remains in use for everything else.",
                        style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { managerDialog = true }, enabled = editorEnabled) {
                        Text(if (managerEnabled) "Replace Manager key" else "Add Manager key")
                    }
                    if (managerEnabled) TextButton(onClick = { confirmRemoveManager = true }) {
                        Text("Remove Manager key")
                    }
                }
                item {
                    HorizontalDivider()
                    OutlinedButton(onClick = onRefreshImages) {
                        Text("Re-download vehicle images")
                    }
                    Text("Use this after changing vehicle photos in LubeLogger.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                item {
                    HorizontalDivider()
                    OutlinedButton(onClick = { confirmDisconnect = true }) {
                        Text("Disconnect and change server/key")
                    }
                }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
    if (confirmDisconnect) DisconnectConfirmDialog(
        onConfirm = { confirmDisconnect = false; onDisconnect() },
        onDismiss = { confirmDisconnect = false })
    if (managerDialog) ManagerKeyDialog(settings,
        onSaved = { managerDialog = false; onManagerKeyChanged() },
        onDismiss = { managerDialog = false })
    if (confirmRemoveManager) AlertDialog(
        onDismissRequest = { confirmRemoveManager = false },
        title = { LubeDialogTitle("Remove Manager key?") },
        text = { DialogBody { Text("Delete will be hidden. Editor access and records are unchanged.") } },
        dismissButton = { TextButton(onClick = { confirmRemoveManager = false }) { Text("Cancel") } },
        confirmButton = { TextButton(onClick = {
            settings.removeManagerKey(); confirmRemoveManager = false; onManagerKeyChanged()
        }) { Text("Remove key") } }
    )
    if (favoriteDialog) {
        AlertDialog(onDismissRequest = { favoriteDialog = false },
            title = { LubeDialogTitle("Favorite vehicle") },
            text = {
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(data.vehicles.filterNot { it.id in hiddenVehicleIds }) { vehicle ->
                        TextButton(onClick = {
                            favorite = vehicle.id
                            onFavoriteChanged(vehicle.id)
                            favoriteDialog = false
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(MaintenanceRules.vehicleTitle(vehicle, nicknames[vehicle.id]))
                        }
                    }
                }
            }, confirmButton = {
                TextButton(onClick = { favoriteDialog = false }) { Text("Close") }
            })
    }
    if (geoapifyDialog) {
        GeoapifyKeyDialog(settings,
            onSaved = { geoapifyRevision++; geoapifyDialog = false },
            onDismiss = { geoapifyDialog = false })
    }
    if (confirmRemoveGeoapify) {
        AlertDialog(onDismissRequest = { confirmRemoveGeoapify = false },
            title = { LubeDialogTitle("Remove Geoapify key?") },
            text = { DialogBody { Text("Gas station searches will use public Overpass servers. " +
                "Existing favorites and saved fuel records stay unchanged.") } },
            dismissButton = { TextButton(onClick = { confirmRemoveGeoapify = false }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = {
                settings.removeGeoapifyKey(); geoapifyRevision++
                confirmRemoveGeoapify = false
            }) { Text("Remove") } })
    }
    if (editorDialog) {
        EditorKeyDialog(settings, onSaved = { onEditorKeyChanged(); editorDialog = false },
            onDismiss = { editorDialog = false })
    }
    if (editingNicknameVehicleId != null) {
        data.vehicles.firstOrNull { it.id == editingNicknameVehicleId }?.let { vehicle ->
            NicknameDialog(vehicle, nicknames[vehicle.id].orEmpty(),
                save = { onNicknameChange(vehicle.id, it) },
                dismiss = { editingNicknameVehicleId = null })
        }
    }
}


/** Background checks use the same API key as this phone, with vehicle-specific opt-outs. */
@Composable
private fun MaintenanceNotificationSettings(config: SecureConfig, garage: GarageData) {
    val context = LocalContext.current.applicationContext
    val prefs = remember(context) { ReminderNotificationSettings(context) }
    val scope = prefs.accountScope(config)
    var refresh by remember { mutableIntStateOf(0) }
    var checkRequestedAt by remember { mutableLongStateOf(0L) }
    val revision = refresh // observe changes to the SharedPreferences-backed switches
    val canPost = ReminderNotificationScheduler.canPost(context)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        refresh++ // Preserve the opt-in; surface permission status if the user declines.
        if (granted) ReminderNotificationScheduler.checkNow(context)
    }
    if (scope == null) {
        Text("Connect to LubeLogger first to configure notifications.")
        return
    }
    val active = remember(scope, revision) { prefs.enabled(scope) }
    val level = remember(scope, revision) { prefs.threshold(scope) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Maintenance notifications are optional and stay on this phone. " +
            "LubeLink checks your account approximately daily; Android can delay background work.",
            style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().toggleable(active, role = Role.Switch, onValueChange = { checked ->
            // Applied to the in-memory preferences at once (Android writes them to disk shortly after);
            // never waits for a reminder check that is posting.
            prefs.setEnabled(scope, checked)
            if (!checked) ReminderNotificationScheduler.revoke(context)
            else {
                ReminderNotificationScheduler.synchronize(context, config)
                ReminderNotificationScheduler.checkNow(context)
                if (Build.VERSION.SDK_INT >= 33 && !ReminderNotificationScheduler.appCanPost(context)) {
                    permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
            refresh++
        }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Enable maintenance alerts", modifier = Modifier.weight(1f))
            Switch(checked = active, onCheckedChange = null)
        }
        if (active) {
            if (!ReminderNotificationScheduler.appCanPost(context)) Text("Android notification permission is disabled. Enable notifications " +
                "for LubeLink in your phone's App settings.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            else if (!canPost) Text("The \"Maintenance reminders\" notification category is turned off for " +
                "LubeLink in your phone's App settings, so alerts can't be shown. Reminders still due will alert " +
                "at the next check after it's turned back on.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            Text("Notify me starting at", style = MaterialTheme.typography.titleMedium)
            listOf(
                Triple(AlertLevel.DUE_SOON, "Urgent", "Due soon or already overdue"),
                Triple(AlertLevel.DUE, "Very urgent", "Very urgent or already overdue"),
                Triple(AlertLevel.PAST_DUE, "Past due", "Only after maintenance is overdue")
            ).forEach { (value, label, explanation) ->
                Row(Modifier.fillMaxWidth().selectable(level == value, role = Role.RadioButton,
                    onClick = { prefs.setThreshold(scope, value); refresh++ })
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    DecorativeGlyph(if (level == value) "●" else "○")
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(label)
                        Text(explanation, style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Text("More urgent reminders are always included.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Vehicles", style = MaterialTheme.typography.titleMedium)
            Text("Only vehicles permitted by this LubeLogger account appear here. " +
                "Vehicles hidden from LubeLink cannot send notifications.",
                style = MaterialTheme.typography.bodySmall)
            garage.vehicles.forEach { vehicle ->
                val hidden = config.isVehicleHidden(vehicle.id)
                val allowed = !hidden && prefs.vehicleEnabled(scope, vehicle.id, false)
                Row(Modifier.fillMaxWidth().toggleable(allowed, enabled = !hidden,
                    role = Role.Switch, onValueChange = {
                        prefs.setVehicleEnabled(scope, vehicle.id, it); refresh++
                    }).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(config.nicknameFor(vehicle.id).ifBlank { vehicle.displayName },
                        modifier = Modifier.weight(1f), maxLines = 2)
                    Switch(checked = allowed, onCheckedChange = null, enabled = !hidden)
                }
            }
            val last = prefs.lastSuccess(scope)
            Text(if (last > 0) "Last successful check: " + java.time.format.DateTimeFormatter
                .ofPattern("MMM d, h:mm a").withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(last))
                else "Last successful check: Not yet", style = MaterialTheme.typography.bodySmall)
            val error = prefs.lastError(scope)
            if (error.isNotEmpty()) Text("Last check: $error", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                ReminderNotificationScheduler.checkNow(context)
                checkRequestedAt = System.currentTimeMillis()
                refresh++
            }, modifier = Modifier.fillMaxWidth()) { Text("Check reminders now") }
            if (checkRequestedAt > 0L && prefs.lastCompleted(scope) < checkRequestedAt)
                Text("Checking reminders…", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                if (checkRequestedAt > 0L && prefs.lastCompleted(scope) >= checkRequestedAt)
                    checkRequestedAt = 0L
                refresh++
            }, modifier = Modifier.fillMaxWidth()) {
                Text("Refresh check status")
            }
            Text("Checks are read-only. A notification appears for newly urgent or worsening " +
                "reminders; unchanged reminders do not create repeated alerts. " +
                "This is a best-effort pilot, not guaranteed daily delivery.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** An authenticated GET confirms reachability only, not that the key has Edit permission. */
@Composable
internal fun EditorKeyDialog(settings: SecureConfig, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val checks = LocalKeyChecks.current
    val check = rememberLatestCall()
    val busy = check.busy
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { LubeDialogTitle("Editor API key") },
        text = {
            DialogBody(spacing = 10.dp) {
                Text("Enter one Editor API key for browsing and record editing.")
                // An edited key makes a check of the previous text stale: it never saves.
                OutlinedTextField(value = key, onValueChange = { key = it.trim(); error = null; check.cancel() },
                    label = { Text("Editor API key") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth())
                Text("Replacing the Editor key removes the Manager key and may hide local station favorites, " +
                    "reset alert preferences, and reset the Map/List choice. Existing LubeLogger records are unchanged.",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = { Button(enabled = !busy && key.isNotBlank(), onClick = {
            val checked = key
            check.run({ checks.verifyEditorKey(settings.baseUrl, checked) },
                onSuccess = { settings.saveApiKey(checked); onSaved() },
                onFailure = { error = safeMessage(it) })
        }) { Text(if (busy) "Checking…" else "Verify and save") } }
    )
}

/** User-supplied key only; no credential in the APK, logs or LubeLogger requests. */
@Composable
internal fun GeoapifyKeyDialog(settings: SecureConfig, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val checks = LocalKeyChecks.current
    val check = rememberLatestCall()
    val busy = check.busy
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { LubeDialogTitle("Geoapify API key") },
        text = {
            DialogBody(spacing = 10.dp) {
                Text("Enter the Places API key from your Geoapify account. " +
                    "This is separate from the LubeLogger Editor key.")
                OutlinedTextField(value = key, onValueChange = {
                    key = it.trim().take(512); status = null; failed = false; check.cancel()
                },
                    label = { Text("Geoapify API key") },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false),
                    trailingIcon = {
                        IconButton(onClick = { visible = !visible }) {
                            Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                contentDescription = if (visible) "Hide key" else "Show key")
                        }
                    },
                    modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = {
                    status = null
                    val tested = key
                    check.run({ checks.testGeoapifyKey(tested) },
                        onSuccess = { failed = false; status = "Connection successful. Key works with the Places API." },
                        onFailure = {
                            failed = true
                            status = "Geoapify verification failed. Check the key, restrictions and connection."
                        })
                }, enabled = key.isNotBlank() && key.none { it.isWhitespace() } && !busy) {
                    if (busy) androidx.compose.material3.CircularProgressIndicator(
                        Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Test connection")
                }
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                    color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("Saving a key is optional; public Overpass remains available. " +
                    "Geoapify may fall back to Overpass when unavailable.",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = { Button(onClick = {
            settings.saveGeoapifyKey(key); onSaved()
        }, enabled = key.isNotBlank() && key.none { it.isWhitespace() } && !busy) {
            Text("Save key")
        } }
    )
}


/** GET verifies account identity; Manager permission is enforced by LubeLogger on DELETE. */
@Composable
internal fun ManagerKeyDialog(settings: SecureConfig, onSaved: () -> Unit, onDismiss: () -> Unit) {
    val checks = LocalKeyChecks.current
    val check = rememberLatestCall()
    val busy = check.busy
    var key by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { LubeDialogTitle("Manager API key") },
        text = {
            DialogBody(spacing = 10.dp) {
                Text("Create a Manager key for the same LubeLogger user. Used only to delete records.")
                OutlinedTextField(value = key, onValueChange = {
                    key = it.trim().take(512); error = null; check.cancel()
                }, label = { Text("Manager API key") },
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = { IconButton(onClick = { visible = !visible }) {
                        Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = if (visible) "Hide key" else "Show key")
                    } },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Authentication is checked now; deletion permission is enforced by LubeLogger when used.",
                    style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
        confirmButton = { Button(enabled = !busy && key.isNotBlank() && key.none { it.isWhitespace() },
            onClick = {
                val checked = key
                check.run({
                    val editorKey = settings.readApiKey() ?: throw IllegalStateException("Editor key missing")
                    checks.verifyManagerKey(settings.baseUrl, checked, editorKey)
                }, onSuccess = { settings.saveManagerKey(checked); onSaved() },
                    onFailure = { error = safeMessage(it) })
            }) { Text(if (busy) "Checking…" else "Verify and save") } }
    )
}

/** The installed app's version, from its package (app/build.gradle.kts versionName): never written twice. */
@Composable
private fun appVersion(): String {
    val context = LocalContext.current
    return remember(context) {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
    }
}
