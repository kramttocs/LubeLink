package com.kramttocs.lubelink.ui

import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.Canvas
import com.kramttocs.lubelink.core.OdometerPoint
import com.kramttocs.lubelink.core.OdometerChartRules
import androidx.compose.ui.semantics.semantics
import com.kramttocs.lubelink.core.ReportRates
import com.kramttocs.lubelink.core.ReportRateRules
import com.kramttocs.lubelink.core.VehicleId
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kramttocs.lubelink.core.GarageData
import com.kramttocs.lubelink.core.LubeLoggerApi
import com.kramttocs.lubelink.core.MaintenanceRules
import com.kramttocs.lubelink.core.ReportDataset
import com.kramttocs.lubelink.core.ReportExpenseResult
import com.kramttocs.lubelink.core.ReportFuelResult
import com.kramttocs.lubelink.core.ReportMaintenanceResult
import com.kramttocs.lubelink.core.ReportPeriod
import com.kramttocs.lubelink.core.ReportRules
import com.kramttocs.lubelink.core.ReportScope
import com.kramttocs.lubelink.core.ReportSection
import com.kramttocs.lubelink.core.ReportSnapshot
import com.kramttocs.lubelink.core.ServiceRecord
import com.kramttocs.lubelink.core.fuel
import com.kramttocs.lubelink.core.maintenance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Reports is mounted only when opened; no data requests on Garage or Vehicle Overview.
 * The shared snapshot is consumed by all three reporting tabs. */
@Composable
internal fun ReportsScreen(
    api: LubeLoggerApi,
    allGarage: GarageData,
    visibleGarage: GarageData,
    nicknames: Map<VehicleId, String>,
    startingVehicleId: VehicleId?,
    reloadGeneration: Int,
    cached: ReportSnapshot?,
    onCache: (ReportSnapshot) -> Unit,
    refreshToken: Int,
    onLoadingChanged: (Boolean) -> Unit,
    onOpenVehicle: (VehicleId) -> Unit,
    onCopy: ((ServiceRecord) -> Unit)?
) {
    val refreshTokenOnEntry = remember { refreshToken }
    var scopeId by remember { mutableStateOf(startingVehicleId) }
    var includeHidden by remember { mutableStateOf(false) }
    var period by remember { mutableStateOf(ReportPeriod.LAST_TWELVE_MONTHS) }
    var section by remember { mutableStateOf(ReportSection.EXPENSES) }
    var vehicleMenu by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var expenses by remember { mutableStateOf<ExpensesView?>(null) }
    var fuel by remember { mutableStateOf<ReportFuelResult?>(null) }
    var maintenance by remember { mutableStateOf<ReportMaintenanceResult?>(null) }
    var selectedRows by remember { mutableStateOf<Pair<String, List<ServiceRecord>>?>(null) }

    val visibleIds = remember(visibleGarage) { visibleGarage.vehicles.mapTo(mutableSetOf()) { it.id } }
    val hiddenCount = allGarage.vehicles.count { it.id !in visibleIds }
    val selectable = if (includeHidden) allGarage.vehicles else visibleGarage.vehicles
    val effectiveScopeId = scopeId?.takeIf { id -> selectable.any { it.id == id } }
    // A stale snapshot can remain visible if a manual/network refresh fails. It is
    // never silently reused as fresh and is dropped when records are written.
    val displaySnapshot = cached?.takeIf { it.generation == reloadGeneration }
    val freshSnapshot = displaySnapshot?.takeIf { it.isFresh(reloadGeneration, System.currentTimeMillis()) }

    // Stable effect keys: writing the refreshed snapshot must NOT cancel this load
    // before onLoadingChanged(false) and strand the pull-to-refresh indicator.
    // Retry after a failed load reads again, like a pull-to-refresh (OD-31).
    var retries by remember { mutableIntStateOf(0) }
    LaunchedEffect(api, reloadGeneration, refreshToken, retries) {
        if (retries == 0 && refreshToken == refreshTokenOnEntry && freshSnapshot != null) {
            onLoadingChanged(false)
            return@LaunchedEffect
        }
        loading = true
        onLoadingChanged(true)
        error = null
        try {
            val result = api.loadReportDataset()
            onCache(ReportSnapshot(result.records, reloadGeneration, System.currentTimeMillis()))
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { error = safeMessage(e)
        } finally {
            loading = false
            onLoadingChanged(false)
        }
    }

    LaunchedEffect(displaySnapshot, effectiveScopeId, includeHidden, period, section, allGarage, visibleIds) {
        expenses = null; fuel = null; maintenance = null
        val snapshot = displaySnapshot ?: return@LaunchedEffect
        val dataset = ReportDataset(snapshot.records)
        val scope = ReportScope(effectiveScopeId, includeHidden)
        val today = LocalDate.now()
        // Only the active tab's calculation runs; switching sections uses the
        // same fetched records and does not make another API call.
        when (section) {
            ReportSection.EXPENSES -> expenses = withContext(Dispatchers.Default) {
                val report = ReportRules.expenses(dataset, allGarage.vehicles, visibleIds, scope, period, today)
                ExpensesView(report,
                    ReportRateRules.rates(report, allGarage.vehicles, effectiveScopeId != null, period, today),
                    effectiveScopeId?.let { id -> OdometerChartRules.points(report.recordsInPeriod.filter { it.vehicleId == id }) }
                        .orEmpty())
            }
            ReportSection.FUEL -> fuel = withContext(Dispatchers.Default) {
                ReportRules.fuel(dataset, allGarage.vehicles, visibleIds, scope, period, today)
            }
            ReportSection.MAINTENANCE -> maintenance = withContext(Dispatchers.Default) {
                ReportRules.maintenance(dataset, allGarage.vehicles, visibleIds, scope, period, today)
            }
        }
    }

    val byId = remember(allGarage) { allGarage.vehicles.associateBy { it.id } }
    val scopeName = effectiveScopeId?.let { id ->
        byId[id]?.let { MaintenanceRules.vehicleTitle(it, nicknames[id]) }
    } ?: "All vehicles"
    val labelFor: (VehicleId) -> String = { id ->
        byId[id]?.let { MaintenanceRules.vehicleContextLabel(it, nicknames[id]) } ?: "Vehicle $id"
    }
    val drill: (String, List<ServiceRecord>) -> Unit = { label, rows ->
        selectedRows = label to rows
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = ScreenEndPadding,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Read here, with the results below: the list can rebuild its items without this screen recomposing, and a
        // value read outside would then show "Calculating" over a finished report.
        val sectionReady = when (section) {
            ReportSection.EXPENSES -> expenses != null
            ReportSection.FUEL -> fuel != null
            ReportSection.MAINTENANCE -> maintenance != null
        }
        item {
            Text("Reports", style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            // A chip that does not fit moves to the next line, so large text never breaks a label mid-word.
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(ReportSection.EXPENSES to "Expenses", ReportSection.FUEL to "Fuel",
                    ReportSection.MAINTENANCE to "Maintenance").forEach { (option, name) ->
                    FilterChip(selected = section == option, onClick = { section = option },
                        label = { Text(name, maxLines = 1, softWrap = false) })
                }
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box {
                    OutlinedButton(onClick = { vehicleMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Text(scopeName, modifier = Modifier.weight(1f), maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = vehicleMenu, onDismissRequest = { vehicleMenu = false },
                        modifier = Modifier.heightIn(max = 350.dp)) {
                        DropdownMenuItem(text = { Text("All vehicles") }, onClick = {
                            scopeId = null; vehicleMenu = false
                        })
                        selectable.forEach { vehicle ->
                            DropdownMenuItem(text = {
                                Column {
                                    Text(MaintenanceRules.vehicleTitle(vehicle, nicknames[vehicle.id]))
                                    MaintenanceRules.vehicleSubtitle(vehicle, nicknames[vehicle.id])?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }, onClick = { scopeId = vehicle.id; vehicleMenu = false })
                        }
                    }
                }
                if (hiddenCount > 0) {
                    LabeledSwitch("Include $hiddenCount hidden ${if (hiddenCount == 1) "vehicle" else "vehicles"}",
                        includeHidden, {
                            includeHidden = it
                            if (!it && scopeId !in visibleIds) scopeId = null
                        }, style = MaterialTheme.typography.bodySmall)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ReportPeriod.LAST_TWELVE_MONTHS to "12 months",
                        ReportPeriod.THIS_YEAR to "This year", ReportPeriod.ALL_TIME to "All time")
                        .forEach { (option, label) ->
                            FilterChip(selected = period == option, onClick = { period = option },
                                label = { Text(label, maxLines = 1, softWrap = false) })
                        }
                }
            }
        }
        if (error != null) item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Could not refresh reports: ${error.orEmpty()}", color = MaterialTheme.colorScheme.error)
                if (displaySnapshot != null) Text("Showing the previously loaded data.",
                    style = MaterialTheme.typography.bodySmall)
                if (!loading) OutlinedButton(onClick = { retries++ }) { Text("Retry") }
            }
        }
        if (loading && displaySnapshot == null) item { CenterMessage("Loading report records…", spinning = true) }
        else if (displaySnapshot == null && error == null) item { CenterMessage("Loading reports…", spinning = true) }
        else if (displaySnapshot != null && !sectionReady) item { CenterMessage("Calculating report…", spinning = true) }

        if (section == ReportSection.EXPENSES) expenses?.let { view ->
            val report = view.report
            item {
                ReportSummary("Total recorded expenses", ReportRules.money(report.total),
                    "${report.validCostRecords} records with costs")
            }
            // OD-52, OD-55: cost per mile and per day, distance and distance a day.
            item { RateTiles(view.rates) }
            // OD-52: one vehicle's odometer readings in the period, on a line; a tap names a reading.
            effectiveScopeId?.let { id ->
                val vehicle = byId[id]
                if (vehicle != null && view.chart.size >= 2) item(key = "odometer_chart_$id") {
                    OdometerChart(view.chart, vehicle.useHours)
                }
            }
            if (report.missingCostRecords > 0) item { ReportNote("${report.missingCostRecords} records have no usable cost; excluded from totals.") }
            if (report.undatedRecords > 0) item { ReportNote("${report.undatedRecords} records have no valid date; excluded.") }
            item { Text("By category", style = MaterialTheme.typography.titleMedium) }
            items(report.categories, key = { "category_${it.name}" }) { group ->
                ExpenseBar(group.name, group.total, report.total, group.records.size) {
                    drill(group.name, group.records)
                }
            }
            item { Text("By month", style = MaterialTheme.typography.titleMedium) }
            val monthly = report.months.takeLast(24).asReversed()
            val peak = monthly.maxOfOrNull { it.total } ?: ReportRules.ZERO
            items(monthly, key = { "month_${it.month}" }) { month ->
                val label = month.month.format(DateTimeFormatter.ofPattern("MMM ''yy", Locale.US))
                ExpenseBar(label, month.total, peak, month.records.size) {
                    drill(label, month.records)
                }
            }
            if (report.months.size > 24) item { ReportNote("Showing the newest 24 months; totals cover the selected period.") }
            if (effectiveScopeId == null) {
                item { Text("By vehicle", style = MaterialTheme.typography.titleMedium) }
                items(report.vehicles, key = { "vehicle_${it.vehicleId}" }) { group ->
                    val label = labelFor(group.vehicleId)
                    ExpenseBar(label, group.total, report.total, group.records.size) {
                        drill(label, group.records)
                    }
                }
            }
            if (report.recordsInPeriod.isEmpty()) item { ReportNote("No records for this period.") }
            item { ReportNote("Totals include recorded Service, Repair, Upgrade and Fuel expenses only.") }
        }
        if (section == ReportSection.FUEL) fuel?.let { report ->
            item { ReportSummary("Fuel spending", ReportRules.money(report.totalSpent),
                "${report.records.size} fuel records") }
            item { ReportSummary("Fuel purchased", "${"%,.2f".format(Locale.US, report.purchasedGallons)} gal",
                "${report.records.size - report.missingGallonsRecords} records with usable gallons") }
            report.averagePaidPerGallon?.let { average ->
                item { ReportSummary("Average paid per gallon", ReportRules.money(average),
                    "Weighted by gallons from transactions with both cost and volume") }
            }
            if (report.missingCostRecords > 0 || report.missingGallonsRecords > 0) item {
                ReportNote("${report.missingCostRecords} records without usable cost; " +
                    "${report.missingGallonsRecords} without usable gallons.")
            }
            item { Text("Fuel spending by month", style = MaterialTheme.typography.titleMedium) }
            val recent = report.months.take(24)
            val peak = recent.maxOfOrNull { it.total } ?: ReportRules.ZERO
            items(recent, key = { "fuel_month_${it.month}" }) { group ->
                val label = group.month.format(DateTimeFormatter.ofPattern("MMM ''yy", Locale.US))
                ExpenseBar(label, group.total, peak, group.records.size) { drill(label, group.records) }
            }
            item { Text("By fuel station", style = MaterialTheme.typography.titleMedium) }
            items(report.stations, key = { "station_${it.name}" }) { group ->
                ExpenseBar(group.name, group.total, report.totalSpent, group.records.size) {
                    drill(group.name, group.records)
                }
            }
            if (effectiveScopeId != null && byId[effectiveScopeId]?.useHours == false) {
                item { Text("Fuel economy (LubeLogger MPG)", style = MaterialTheme.typography.titleMedium) }
                if (report.mpgSamples.isEmpty()) item { ReportNote("No valid MPG measurements in this period.") }
                val recentMpg = report.mpgSamples.take(20)
                val peakMpg = recentMpg.mapNotNull { it.fuelEconomy }.maxOrNull() ?: 0.0
                items(recentMpg, key = { "mpg_${it.vehicleId}_${it.id}" }) { record ->
                    FuelMpgBar(record, peakMpg) { drill("Fuel economy", listOf(record)) }
                }
            } else if (effectiveScopeId == null) item {
                ReportNote("Select an individual vehicle to view MPG. Garage-wide MPG is not meaningful.")
            }
            if (report.records.isEmpty()) item { ReportNote("No fuel records for this period.") }
            item { ReportNote("MPG values come from LubeLogger; incomplete and missed fill-ups may affect availability.") }
        }
        if (section == ReportSection.MAINTENANCE) maintenance?.let { report ->
            item { ReportSummary("Recorded maintenance spending", ReportRules.money(report.totalSpent),
                "${report.records.size} service, repair and upgrade records") }
            if (report.missingCostRecords > 0) item {
                ReportNote("${report.missingCostRecords} records have no usable cost; counted as activity but excluded from totals.")
            }
            item { Text("By work type", style = MaterialTheme.typography.titleMedium) }
            items(report.categories, key = { "work_${it.name}" }) { group ->
                ExpenseBar(group.name, group.total, report.totalSpent, group.records.size) {
                    drill(group.name, group.records)
                }
            }
            item { Text("Maintenance spending by month", style = MaterialTheme.typography.titleMedium) }
            val recent = report.months.take(24)
            val peak = recent.maxOfOrNull { it.total } ?: ReportRules.ZERO
            items(recent, key = { "maint_month_${it.month}" }) { group ->
                val label = group.month.format(DateTimeFormatter.ofPattern("MMM ''yy", Locale.US))
                ExpenseBar(label, group.total, peak, group.records.size) { drill(label, group.records) }
            }
            item { Text("Recent maintenance", style = MaterialTheme.typography.titleMedium) }
            items(report.records.take(20), key = { "work_recent_${it.kind}_${it.vehicleId}_${it.id}" }) { record ->
                ReportLine("${record.description.ifBlank { record.kind.label }} · ${labelFor(record.vehicleId)}",
                    "${MaintenanceRules.recordDateLabel(record.date)} · ${record.kind.label}") { drill("Maintenance record", listOf(record)) }
            }
            if (report.records.isEmpty()) item { ReportNote("No maintenance records for this period.") }
            item { ReportNote("Maintenance here means recorded services, repairs and upgrades—not reminder completion or projected future costs.") }
        }
    }
    selectedRows?.let { (label, records) ->
        ReportRecordsDialog(label, records, byId.mapValues { it.value.useHours },
            byId.mapValues { (id, vehicle) -> MaintenanceRules.vehicleContextLabel(vehicle, nicknames[id]) },
            api, navigableIds = visibleIds, onCopy = onCopy, onOpenVehicle = { id ->
                selectedRows = null
                onOpenVehicle(id)
            }, onDismiss = { selectedRows = null })
    }
}

@Composable
private fun FuelMpgBar(record: ServiceRecord, peak: Double, onClick: () -> Unit) {
    val mpg = record.fuelEconomy ?: return
    val ratio = if (peak > 0 && peak.isFinite()) (mpg / peak).coerceIn(0.0, 1.0).toFloat() else 0f
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${MaintenanceRules.recordDateLabel(record.date)} · ${MaintenanceRules.lineForDisplay(record.fuelStation.ifBlank { "Fuel" })}",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(MaintenanceRules.mpgLabel(mpg) ?: "", color = Gold,
                    fontWeight = FontWeight.SemiBold)
            }
            Box(Modifier.fillMaxWidth().height(7.dp).background(Charcoal, progressBarShape)) {
                if (ratio > 0f) Box(Modifier.fillMaxWidth(ratio).height(7.dp)
                    .background(Gold, progressBarShape))
            }
        }
    }
}

/**
 * The expense report with its tiles and chart, worked out together in one calculation with one date, so what the
 * screen shows always belongs to one choice of vehicle and period.
 */
private class ExpensesView(val report: ReportExpenseResult, val rates: ReportRates, val chart: List<OdometerPoint>)

@Composable
private fun ReportSummary(title: String, amount: String, caption: String) {
    Card(colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(amount, color = Gold, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineMedium)
            Text(caption, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OdometerChart(points: List<OdometerPoint>, useHours: Boolean) {
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val positions = remember(points) { OdometerChartRules.positions(points) }
    val low = points.minOf { it.reading }; val high = points.maxOf { it.reading }
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().background(Panel, panelShape).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Odometer in this period", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.height(120.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(MaintenanceRules.mileageLabel(high, useHours), style = MaterialTheme.typography.labelSmall, color = muted)
                Text(MaintenanceRules.mileageLabel(low, useHours), style = MaterialTheme.typography.labelSmall, color = muted)
            }
            Canvas(Modifier.weight(1f).height(120.dp)
                .semantics {
                    contentDescription = OdometerChartRules.summary(points, useHours)
                    selected?.let { stateDescription = OdometerChartRules.label(points[it], useHours) }
                    // TalkBack cannot tap a point: these step through the readings, one at a time.
                    customActions = listOf(OdometerChartRules.NEXT_READING to 1, OdometerChartRules.PREVIOUS_READING to -1)
                        .map { (name, by) ->
                            CustomAccessibilityAction(name) { selected = OdometerChartRules.step(points.size, selected, by); true }
                        }
                }
                .pointerInput(points) {
                    // The same inset as the drawing below, so a tap chooses the point drawn nearest to it.
                    val inset = 6.dp.toPx()
                    detectTapGestures { tap ->
                        selected = OdometerChartRules.nearest(points, (tap.x - inset) / (size.width - 2 * inset))
                    }
                }) {
                val inset = 6.dp.toPx()
                fun at(i: Int): Offset {
                    val x = inset + positions[i] * (size.width - 2 * inset)
                    val y = if (high == low) size.height / 2
                        else inset + (1 - (points[i].reading - low).toFloat() / (high - low)) * (size.height - 2 * inset)
                    return Offset(x, y)
                }
                drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                val line = Path().apply { points.indices.forEach { i -> at(i).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
                drawPath(line, Gold, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                points.indices.forEach { i ->
                    val r = if (i == selected) 6.dp.toPx() else 4.dp.toPx()
                    drawCircle(Charcoal, r + 2.dp.toPx(), at(i))
                    drawCircle(Gold, r, at(i))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(MaintenanceRules.recordDateLabel(points.first().record.date), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(MaintenanceRules.recordDateLabel(points.last().record.date), style = MaterialTheme.typography.labelSmall, color = muted)
        }
        Text(selected?.let { OdometerChartRules.label(points[it], useHours) } ?: OdometerChartRules.TAP_HINT,
            style = MaterialTheme.typography.bodySmall,
            color = if (selected == null) muted else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun RateTiles(rates: ReportRates) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReportRateRules.tiles(rates).chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { (label, value) ->
                    Column(Modifier.weight(1f).background(Panel, panelShape).padding(12.dp)
                        .semantics(mergeDescendants = true) {}) {
                        Text(label, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value, style = if (value in ReportRateRules.MESSAGES) MaterialTheme.typography.bodyMedium
                            else MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
        ReportRateRules.notes(rates).forEach { ReportNote(it) }
    }
}

@Composable
private fun ReportNote(message: String) {
    Text(message, style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Report labels come from server text (descriptions, station names): shown bounded, never laid out whole. */
private const val REPORT_LABEL_LINES = 3

@Composable
private fun ReportLine(label: String, value: String, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(MaintenanceRules.lineForDisplay(label), style = MaterialTheme.typography.bodyMedium,
                    maxLines = REPORT_LABEL_LINES, overflow = TextOverflow.Ellipsis)
                Text(value, color = Gold, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Details", tint = Gold)
        }
    }
}

@Composable
private fun ExpenseBar(label: String, amount: BigDecimal, max: BigDecimal,
                       count: Int, onClick: () -> Unit) {
    val ratio = if (max > ReportRules.ZERO) (amount.toDouble() / max.toDouble())
        .takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)?.toFloat() ?: 0f else 0f
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Panel)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(MaintenanceRules.lineForDisplay(label), modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, maxLines = REPORT_LABEL_LINES,
                    overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(8.dp))
                Text(ReportRules.money(amount), fontWeight = FontWeight.SemiBold, color = Gold)
            }
            Box(Modifier.fillMaxWidth().height(7.dp).background(Charcoal, progressBarShape)) {
                if (ratio > 0f) Box(Modifier.fillMaxWidth(ratio).height(7.dp)
                    .background(Gold, progressBarShape))
            }
            Text("$count ${if (count == 1) "record" else "records"} · Tap for details",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** ONE dialog at a time. Selecting a record replaces the list with the same
 * shared RecordDetailsDialog used by History; closing details returns to the list. */
@Composable
private fun ReportRecordsDialog(
    title: String, records: List<ServiceRecord>, useHours: Map<VehicleId, Boolean>,
    labels: Map<VehicleId, String>, api: LubeLoggerApi, navigableIds: Set<VehicleId>,
    onOpenVehicle: (VehicleId) -> Unit, onDismiss: () -> Unit,
    onCopy: ((ServiceRecord) -> Unit)?
) {
    var detail by remember(records) { mutableStateOf<ServiceRecord?>(null) }
    val selected = detail
    if (selected != null) {
        RecordDetailsDialog(selected, useHours[selected.vehicleId] ?: false, api,
            vehicleLabel = labels[selected.vehicleId],
            onOpenVehicle = if (selected.vehicleId in navigableIds) ({ onOpenVehicle(selected.vehicleId) }) else null,
            onDismiss = { detail = null },
            onCopy = if (selected.vehicleId in navigableIds) onCopy?.let { copy ->
                ({ detail = null; onDismiss(); copy(selected) }) } else null)
    } else {
        AlertDialog(onDismissRequest = onDismiss,
            title = { LubeDialogTitle(MaintenanceRules.lineForDisplay(title), maxLines = REPORT_LABEL_LINES) },
            text = {
                if (records.isEmpty()) Text("No records for this selection.")
                else LazyColumn(Modifier.heightIn(max = 430.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(records, key = { "${it.vehicleId}-${it.kind}-${it.id}" }) { record ->
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            if (record.vehicleId in navigableIds) {
                                TextButton(onClick = { onOpenVehicle(record.vehicleId) },
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(labels[record.vehicleId] ?: "Vehicle", color = Gold,
                                        modifier = Modifier.weight(1f), maxLines = 2,
                                        style = MaterialTheme.typography.labelMedium)
                                    Icon(Icons.Outlined.ChevronRight, contentDescription = "Open vehicle", tint = Gold)
                                }
                            } else Text(labels[record.vehicleId] ?: "Vehicle",
                                style = MaterialTheme.typography.labelMedium, color = Gold)
                            RecordRow(record, useHours[record.vehicleId] ?: false) { detail = record }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
    }
}
