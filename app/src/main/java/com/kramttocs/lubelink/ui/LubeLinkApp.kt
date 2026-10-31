package com.kramttocs.lubelink.ui

import androidx.compose.ui.text.style.TextOverflow

import androidx.compose.ui.platform.LocalDensity



import androidx.compose.material3.LocalTextStyle



import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.R
import com.kramttocs.lubelink.ReminderDestination
import com.kramttocs.lubelink.core.CornerStyle
import com.kramttocs.lubelink.core.HistoryFilter
import com.kramttocs.lubelink.core.ImageDiskCache
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.MaintenanceReminder
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.RecordKind
import com.kramttocs.lubelink.core.RecordsLoad
import com.kramttocs.lubelink.core.ReminderNotificationScheduler
import com.kramttocs.lubelink.core.ReminderRecordRules
import com.kramttocs.lubelink.core.SecureConfig
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.VehicleId
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.launch

/** Typed notification metadata; cosmetic success icon does not inspect translated text. */
private data class RecordSuccessVisuals(
    override val message: String,
    override val actionLabel: String? = null,
    override val withDismissAction: Boolean = false,
    override val duration: SnackbarDuration = SnackbarDuration.Short
) : SnackbarVisuals

/** The vehicle a fresh launch opens on, from the startup preference. */
private fun startupVehicle(settings: SecureConfig): VehicleId? = when (settings.startup) {
    SecureConfig.Startup.GARAGE -> null
    SecureConfig.Startup.FAVORITE -> settings.favoriteVehicleId
    SecureConfig.Startup.LAST -> settings.lastVehicleId
}

/**
 * The app shell: wires screens to their state holders. Data read from LubeLogger lives in
 * [AccountDataState] (one per credential), navigation in [NavigationState], open forms in
 * [EditorSessionState], the selected vehicle's History in [VehicleHistoryState], and write outcomes
 * in [WriteSafetyHost]. This function owns only local display preferences and the wiring.
 */
@Composable
fun LubeLinkApp(settings: SecureConfig, destination: ReminderDestination? = null,
                onDestinationHandled: () -> Unit = {}) {
    // The corner style (OD-53) applies to every screen at once, Setup included.
    var cornerStyle by remember { mutableStateOf(settings.cornerStyle) }
    CornerTheme(cornerStyle) {
        AppShell(settings, destination, onDestinationHandled, cornerStyle) { cornerStyle = it; settings.cornerStyle = it }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShell(settings: SecureConfig, destination: ReminderDestination?, onDestinationHandled: () -> Unit,
                     cornerStyle: CornerStyle, onCornerStyleChanged: (CornerStyle) -> Unit) {
    var connected by remember { mutableStateOf(!settings.readApiKey().isNullOrBlank()) }
    if (!connected) {
        SetupScreen(settings) { connected = true }
        return
    }
    // Vehicle art is saved only in app-private, non-backed-up cacheDir.
    val appContext = LocalContext.current.applicationContext
    val uriHandler = LocalUriHandler.current
    val imageDiskCache = remember(appContext) {
        ImageDiskCache(File(appContext.cacheDir, "lubelink_vehicle_images"))
    }
    // The key is never displayed, logged, or transmitted outside HTTPS to the chosen server.
    var credentialGeneration by remember { mutableIntStateOf(0) }
    val currentApiKey = remember(credentialGeneration) { settings.readApiKey().orEmpty() }
    val api = remember(credentialGeneration) { LubeLoggerApi(settings.baseUrl, currentApiKey, imageDiskCache) }
    val account = rememberAccountData(api, settings)
    val nav = rememberNavigationState()
    val editor = rememberEditorSession()

    // Local display preferences.
    var showFuel by remember { mutableStateOf(settings.includeFuel) }
    var garageLayout by remember { mutableStateOf(settings.garageLayout) }
    var newestFirst by remember { mutableStateOf(settings.historyNewestFirst) }
    var suggestReminders by remember { mutableStateOf(settings.suggestReminders) }
    var historyFilter by remember { mutableStateOf(HistoryFilter.ALL) }
    var calendarMonth by remember { mutableStateOf(YearMonth.now()) }
    var calendarDay by remember { mutableStateOf(LocalDate.now()) }
    // Refreshing records should not force multi-megabyte artwork downloads.
    var imageRefresh by remember { mutableIntStateOf(0) }
    var editorEnabled by remember { mutableStateOf(settings.isEditorKeyConfigured()) }
    var managerEnabled by remember { mutableStateOf(settings.isManagerKeyConfigured()) }
    var managerGeneration by remember { mutableIntStateOf(0) }
    val currentManagerKey = remember(managerGeneration, credentialGeneration) { settings.readManagerKey().orEmpty() }
    // Bumped after every confirmed write and by retry; all record data reloads for it.
    var reload by remember { mutableIntStateOf(0) }
    var reportRefreshToken by remember { mutableIntStateOf(0) }
    var reportRefreshing by remember { mutableStateOf(false) }
    val pullRefreshState = rememberPullToRefreshState()
    val refreshSnackbar = remember { SnackbarHostState() }
    val refreshScope = rememberCoroutineScope()

    LaunchedEffect(account, reload) {
        account.loadGarage()
            .onSuccess { nav.reconcileSelection(account.visibleVehicleIds) { startupVehicle(settings) } }
            .onFailure { e ->
                val message = "Refresh failed: ${safeMessage(e as Exception)}"
                if (account.garage != null) refreshScope.launch { refreshSnackbar.showSnackbar(message) }
            }
    }
    val garageReady = account.garage != null
    val vehicleHistory = rememberVehicleHistory(account.historySource, nav.selectedId, showFuel, garageReady, generation = reload)
    // A pending pull-to-refresh belongs to this account; a key change discards it.
    var refreshRequest by remember(account) { mutableStateOf<RefreshRequest?>(null) }
    val pullRefreshing = refreshRequest != null
    AccountRefreshEffect(account, vehicleHistory, nav, refreshRequest, showFuel) { failure ->
        refreshRequest = null
        // Snackbar display may suspend for seconds; the spinner has already stopped.
        if (failure != null) refreshScope.launch { refreshSnackbar.showSnackbar(failure) }
    }
    LaunchedEffect(account, nav.tab, nav.selectedId, reload, showFuel, garageReady, pullRefreshing) {
        if (!pullRefreshing && (nav.tab == Tab.CALENDAR || (nav.tab == Tab.HISTORY && nav.selectedId == null)))
            account.loadCalendarIfStale(showFuel, reload)
    }
    // Reusable descriptions on first editor use and after a save. Garage readiness is a key because a
    // form restored after process death is open before the Garage reloads.
    LaunchedEffect(account, editor.isOpen, reload, garageReady) {
        if (editor.isOpen) account.loadDescriptionsIfStale(reload)
    }
    // A filter belongs to one vehicle context, not another vehicle's History.
    LaunchedEffect(nav.selectedId) { historyFilter = HistoryFilter.ALL }
    val visibleGarage = account.visibleGarage
    LaunchedEffect(destination, visibleGarage) {
        if (destination != null && visibleGarage != null) {
            nav.showReminderFromNotification(visibleGarage.reminders.firstOrNull {
                it.id == destination.reminderId && it.vehicleId == destination.vehicleId
            })
            onDestinationHandled()
        }
    }
    val selected = visibleGarage?.vehicles?.firstOrNull { it.id == nav.selectedId }
    val allVehicles = selected == null
    fun isVisible(vehicleId: VehicleId) = visibleGarage?.vehicles?.any { it.id == vehicleId } == true

    val beginAdd: (RecordKind) -> Unit = { kind -> editor.beginAdd(if (allVehicles) null else nav.selectedId, kind) }
    val beginEdit: (ServiceRecord) -> Unit = { record -> if (isVisible(record.vehicleId)) editor.beginEdit(record) }
    val beginCopy: (ServiceRecord) -> Unit = { record ->
        if (editorEnabled && record.id.isValid && record.vehicleId.isValid && isVisible(record.vehicleId))
            editor.beginCopy(record)
    }
    // Deletion must never cross a hidden/unknown vehicle boundary.
    val beginDelete: (ServiceRecord) -> Unit = { record ->
        if (managerEnabled && currentManagerKey.isNotBlank() && isVisible(record.vehicleId)) editor.deleteTarget = record
    }
    val beginFromReminder: (MaintenanceReminder) -> Unit = { reminder ->
        if (editorEnabled && visibleGarage != null && ReminderRecordRules.canCreate(reminder, visibleGarage.vehicles))
            editor.beginFromReminder(reminder)
    }
    val openVehicle: (VehicleId) -> Unit = { vehicleId ->
        if (isVisible(vehicleId)) {
            nav.openVehicle(vehicleId)
            settings.lastVehicleId = vehicleId
        }
    }
    val closeReports: () -> Unit = {
        reportRefreshing = false
        nav.closeReports()
    }
    val refreshCurrentScreen: () -> Unit = {
        if (!pullRefreshing && !editor.isOpen && account.garage != null && nav.tab != Tab.REPORTS)
            refreshRequest = RefreshRequest(nav.tab, nav.selectedId, showFuel, reload)
    }
    // Also reachable from the connection-problem screen, so a key that stopped working can be replaced.
    val disconnect: () -> Unit = {
        settings.disconnect()
        // After the key is gone, so a check already running cannot post for it.
        ReminderNotificationScheduler.revoke(appContext)
        editor.deleteTarget = null
        managerEnabled = false
        managerGeneration++
        imageDiskCache.clear()
        clearAttachmentCache(appContext)
        connected = false
    }
    BackHandler(enabled = nav.tab == Tab.REPORTS) { closeReports() }
    BackHandler(enabled = nav.returnFromVehicle != null && nav.tab == Tab.HOME) { nav.backFromVehicle() }
    BackHandler(enabled = nav.returnFromVehicle == null && nav.tab != Tab.REPORTS && nav.canGoBackToGarage) {
        nav.backTowardGarage()
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    // Keep the logo at the same coordinates on every tab. Previously the
                    // conditional Garage subtitle changed the height of this Column, causing
                    // Material3 TopAppBar to vertically recenter the whole title on other tabs.
                    // Keep the logo's pre-release vertical alignment on every screen.
                    Column(
                        modifier = Modifier.height(60.dp),
                        verticalArrangement = Arrangement.Top
                    ) {
                        // The source PNG is alpha-trimmed; this frame matches its real aspect
                        // ratio so ContentScale.Fit doesn't add an apparent left inset.
                        Image(
                            painter = painterResource(R.drawable.lubelink_header_logo),
                            contentDescription = "LubeLink",
                            modifier = Modifier.width(148.dp).height(44.dp).clickable(
                                onClickLabel = "Go to Garage", role = Role.Button) { nav.goToGarage() },
                            contentScale = ContentScale.Fit
                        )
                        // Preserve the existing stable header spacing across screens.
                    }
                },
                actions = {
                    // Opening Garage explicitly switches to All vehicles context.
                    if (!allVehicles || nav.tab != Tab.HOME) {
                        TextButton(onClick = { nav.goToGarage() }) { Text("Garage") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Charcoal)
            )
        },
        snackbarHost = {
            SnackbarHost(refreshSnackbar) { notification ->
                Snackbar(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    shape = panelShape,
                    containerColor = Panel,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    actionContentColor = Gold,
                    dismissActionContentColor = Gold
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (notification.visuals is RecordSuccessVisuals) {
                            Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = Gold)
                        }
                        Text(notification.visuals.message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Panel) {
                Tab.entries.filter { it != Tab.REPORTS }.forEach { destinationTab ->
                    NavigationBarItem(
                        selected = nav.tab == destinationTab,
                        onClick = {
                            if (nav.tab == Tab.REPORTS) reportRefreshing = false
                            nav.switchTab(destinationTab)
                        },
                        icon = { Icon(destinationTab.icon, contentDescription = null) },
                        label = { BottomNavLabel(destinationTab.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Gold, selectedTextColor = Gold,
                            indicatorColor = Charcoal,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets)) {
            PullToRefreshBox(
                isRefreshing = if (nav.tab == Tab.REPORTS) reportRefreshing else pullRefreshing,
                onRefresh = {
                    if (nav.tab == Tab.REPORTS) {
                        if (!reportRefreshing && account.garage != null) {
                            reportRefreshing = true
                            reportRefreshToken++
                        }
                    } else refreshCurrentScreen()
                },
                state = pullRefreshState,
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = pullRefreshState,
                        isRefreshing = if (nav.tab == Tab.REPORTS) reportRefreshing else pullRefreshing,
                        color = Gold, containerColor = Panel,
                        modifier = Modifier.align(Alignment.TopCenter)
                    )
                }
            ) {
            val garage = account.garage
            Column(Modifier.fillMaxSize()) {
            visibleGarage?.missing?.takeIf { it.isNotEmpty() && account.loadError == null }
                ?.let { GarageGapNotice(it) { reload++ } }
            // Record types that did not load for the history this screen shows (OD-31); Retry reads it again.
            val recordGap = when {
                account.loadError != null || garage == null -> emptySet()
                nav.tab == Tab.CALENDAR || (nav.tab == Tab.HISTORY && allVehicles) -> account.calendarMissing
                nav.tab == Tab.HISTORY || (nav.tab == Tab.HOME && selected != null) -> vehicleHistory.missing
                else -> emptySet()
            }
            if (recordGap.isNotEmpty()) RecordGapNotice(RecordsLoad(emptyList(), recordGap)) { refreshCurrentScreen() }
            Box(Modifier.weight(1f)) {
            when {
                account.loadError != null -> ErrorPane(account.loadError.orEmpty(), retry = { reload++ },
                    onDisconnect = disconnect)
                garage == null || visibleGarage == null -> CenterMessage("Loading your garage…", spinning = true)
                nav.tab == Tab.HOME && selected == null -> GarageScreen(
                    visibleGarage, api, imageRefresh, account.nicknames, garageLayout,
                    onManageVisibility = { nav.switchTab(Tab.SETTINGS) }, onSelect = { vehicleId ->
                        nav.selectFromGarage(vehicleId)
                        settings.lastVehicleId = vehicleId
                    },
                    onReports = { nav.openReports(null) },
                    // OD-38: LubeLogger's main page, where its own "+" adds a vehicle.
                    onAddVehicle = { uriHandler.openUri(settings.baseUrl.trimEnd('/') + "/") }
                )
                nav.tab == Tab.HOME && selected != null -> VehicleScreen(
                    selected, visibleGarage, api, vehicleHistory.records, vehicleHistory.loading,
                    vehicleHistory.error != null || vehicleHistory.missing.isNotEmpty(), imageRefresh,
                    nickname = account.nicknames[selected.id],
                    onHistory = { nav.tab = Tab.HISTORY },
                    onReports = { nav.openReports(selected.id) },
                    onAdd = if (editorEnabled) ({ beginAdd(RecordKind.SERVICE) }) else null,
                    onLogReminder = if (editorEnabled) beginFromReminder else null,
                    onEdit = if (editorEnabled) beginEdit else null,
                    onCopy = if (editorEnabled) beginCopy else null,
                    onDelete = if (managerEnabled) beginDelete else null
                )
                nav.tab == Tab.HISTORY -> HistoryScreen(
                    visibleGarage, nav.selectedId,
                    if (allVehicles) account.visibleCalendarRecords else vehicleHistory.records,
                    if (allVehicles) account.calendarError else vehicleHistory.error,
                    loading = allVehicles && account.calendarLoading,
                    includeFuel = showFuel, nicknames = account.nicknames, api = api,
                    typeFilter = historyFilter, onFilterChanged = { historyFilter = it },
                    newestFirst = newestFirst,
                    onAdd = if (editorEnabled) beginAdd else null,
                    onEdit = if (editorEnabled) beginEdit else null,
                    onCopy = if (editorEnabled) beginCopy else null,
                    onDelete = if (managerEnabled) beginDelete else null,
                    onOpenVehicle = openVehicle, retry = { reload++ },
                    onOpenReminders = { nav.switchTab(Tab.REMINDERS) },
                    missingKinds = if (allVehicles) account.calendarMissing else vehicleHistory.missing
                )
                nav.tab == Tab.REMINDERS -> RemindersScreen(visibleGarage, nav.selectedId, settings.baseUrl,
                    account.nicknames, openVehicle, if (editorEnabled) beginFromReminder else null,
                    highlightReminderId = nav.highlightedReminderId,
                    onHighlighted = { nav.highlightedReminderId = null }, suggest = suggestReminders)
                nav.tab == Tab.CALENDAR -> CalendarScreen(
                    visibleGarage, nav.selectedId, account.nicknames, account.visibleCalendarRecords,
                    account.calendarLoading, account.calendarError,
                    api, calendarMonth, calendarDay,
                    onMonthChanged = { calendarMonth = it }, onDayChanged = { calendarDay = it },
                    onOpenVehicle = openVehicle,
                    onEdit = if (editorEnabled) beginEdit else null,
                    onCopy = if (editorEnabled) beginCopy else null,
                    onDelete = if (managerEnabled) beginDelete else null,
                    onLogReminder = if (editorEnabled) beginFromReminder else null,
                    retry = { account.invalidateCalendar(); reload++ }
                )
                nav.tab == Tab.REPORTS -> ReportsScreen(
                    api = api, allGarage = garage, visibleGarage = visibleGarage,
                    nicknames = account.nicknames, startingVehicleId = nav.reportStartingVehicleId,
                    reloadGeneration = reload, cached = account.reportSnapshot,
                    onCache = { account.reportSnapshot = it },
                    refreshToken = reportRefreshToken,
                    onLoadingChanged = { reportRefreshing = it },
                    onOpenVehicle = { id ->
                        closeReports()
                        openVehicle(id)
                    },
                    onCopy = if (editorEnabled) beginCopy else null
                )
                else -> SettingsScreen(settings, garage, showFuel, account.nicknames,
                    onNicknameChange = { vehicleId, rawValue ->
                        val normalized = MaintenanceRules.normalizeNickname(rawValue)
                        settings.saveNickname(vehicleId, normalized)
                        account.setNickname(vehicleId, normalized)
                    },
                    onFuelChange = { showFuel = it; settings.includeFuel = it },
                    garageLayout = garageLayout,
                    onGarageLayoutChanged = { garageLayout = it; settings.garageLayout = it },
                    cornerStyle = cornerStyle, onCornerStyleChanged = onCornerStyleChanged,
                    hiddenVehicleIds = account.hiddenVehicleIds,
                    onVehicleVisibilityChanged = { vehicleId, hidden ->
                        // Applied at once, for this server; never waits for a posting check.
                        settings.setVehicleHidden(vehicleId, hidden)
                        account.setHidden(vehicleId, hidden)
                        // Invalidate the selection and any form that belongs to a vehicle now hidden.
                        if (hidden) {
                            nav.forgetVehicle(vehicleId)
                            editor.closeForVehicle(vehicleId)
                        }
                    },
                    newestFirst = newestFirst,
                    onHistorySortChanged = { newestFirst = it; settings.historyNewestFirst = it },
                    suggestReminders = suggestReminders,
                    onSuggestRemindersChanged = { suggestReminders = it; settings.suggestReminders = it },
                    onStartupChanged = { settings.startup = it },
                    onFavoriteChanged = { settings.favoriteVehicleId = it },
                    onRefreshImages = { imageRefresh++ },
                    editorEnabled = editorEnabled,
                    managerEnabled = managerEnabled,
                    onManagerKeyChanged = {
                        managerEnabled = settings.isManagerKeyConfigured()
                        managerGeneration++
                        editor.deleteTarget = null
                    },
                    onEditorKeyChanged = {
                        editorEnabled = settings.isEditorKeyConfigured()
                        managerEnabled = settings.isManagerKeyConfigured()
                        managerGeneration++
                        editor.deleteTarget = null
                        // A new client means a new, empty AccountDataState.
                        credentialGeneration++
                        // The previous key's checks and reminders end here; the new key's start if enabled.
                        ReminderNotificationScheduler.revoke(appContext)
                        ReminderNotificationScheduler.synchronize(appContext, settings)
                    },
                    gasFields = account.gasFields,
                    gasFieldError = account.gasFieldError,
                    gasFieldChecking = account.gasFieldChecking,
                    onCheckGasFields = { account.checkGasFields(refreshScope) },
                    onDisconnect = disconnect
                )
            }
            }
            }
            }
        }
    }
    if (editor.isShowing && editorEnabled && visibleGarage != null) {
        val openedSession = editor.session
        key(openedSession) {
            EditorRecordDialog(
                visibleGarage.vehicles, visibleGarage.summaries, account.nicknames,
                editor.vehicleId, settings.baseUrl, currentApiKey,
                initialKind = editor.initialKind, existingRecord = editor.target,
                initialReminder = editor.reminder, copySource = editor.copySource,
                descriptionRecords = account.descriptionRecords.filter { it.vehicleId in account.visibleVehicleIds },
                descriptionsLoading = account.descriptionsLoading,
                gasFields = account.gasFields,
                onGasFieldsNeeded = { account.checkGasFields(refreshScope) },
                onDismiss = { editor.closeIfCurrent(openedSession) },
                onSaved = { if (editor.closeIfCurrent(openedSession)) reload++ }
            )
        }
    }
    editor.deleteTarget?.let { record ->
        val vehicle = visibleGarage?.vehicles?.firstOrNull { it.id == record.vehicleId }
        if (vehicle != null && managerEnabled && currentManagerKey.isNotBlank()) {
            DeleteRecordDialog(vehicle, record,
                MaintenanceRules.vehicleContextLabel(vehicle, account.nicknames[vehicle.id]),
                settings.baseUrl, currentManagerKey,
                onDismiss = { editor.deleteTarget = null })
        } else {
            LaunchedEffect(record) { editor.deleteTarget = null }
        }
    }
    WriteSafetyHost(appContext) { notice ->
        editor.close()
        editor.deleteTarget = null
        reload++
        // Use the composition-owned scope: WriteSafety returning to Idle cancels the host's effect,
        // but must not cancel the success Snackbar with it.
        refreshScope.launch {
            refreshSnackbar.currentSnackbarData?.dismiss()
            refreshSnackbar.showSnackbar(RecordSuccessVisuals(notice))
        }
    }
}

/**
 * A bottom navigation label. Labels grow with the text-size setting up to 110%, all alike; beyond that, five labels side
 * by side would break mid-word ("Remin/ders"). The icons, and all other text, still scale fully.
 */
@Composable
internal fun BottomNavLabel(title: String) {
    val scale = LocalDensity.current.fontScale
    val size = LocalTextStyle.current.fontSize
    Text(title, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis,
        fontSize = if (scale > NAV_LABEL_MAX_SCALE) size * (NAV_LABEL_MAX_SCALE / scale) else size)
}

private const val NAV_LABEL_MAX_SCALE = 1.1f
