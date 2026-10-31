#!/usr/bin/env python3
"""Offline source guardrails; no Android SDK, network or real credentials required."""
import re
from pathlib import Path
root=Path(__file__).resolve().parent.parent
core=root/'app/src/main/java/com/kramttocs/lubelink/core'
ui=root/'app/src/main/java/com/kramttocs/lubelink/ui'

def source(p): return p.read_text(encoding='utf-8')

def assert_has(text, needle, why):
    assert needle in text, why

v=source(core/'LubeLoggerApi.kt')
assert_has(v,'isElectric = boolean(item, "isElectric")', 'EV identity must load, and a missing value is an error')
assert_has(v,'ReminderScheduleRules.configuredMetric(text(item, "userMetric"), text(item, "metric"))', 'configured schedule')
assert_has(v,"path.substringBefore('?').endsWith(\"/all\")", 'all categories including fuel aggregate')
e=source(core/'LubeLoggerEditorApi.kt')
assert e.count('EditorRules.unsupportedChargingWrite(')==3, 'block EV Add, Edit-load and Update'
assert_has(e,'// Server-side v1.7.3 PUT replaces these collections.', 'preserve attachment fields')
assert_has(e,'&useMPG=true&useUKMPG=false', 'non-electric fuel endpoint normalization')
m=source(core/'LubeLoggerManagerApi.kt')
assert_has(m, 'if (stillThere != null)', 'verify rejected delete')
assert_has(m,'throw ManagerException("LubeLogger did not delete', 'definite failure')
assert_has(m,'// Never repeat DELETE.', 'do not retry DELETE')
w=source(ui/'WriteSafety.kt')
assert_has(w,'.putString(ACTIVE, description).putString(INTENT, intent.persistedCode()).commit()', 'persist write lock')
assert_has(w,'catch (e: LubeLoggerEditorApi.WriteUncertainException)', 'handle lost write outcome')
assert_has(w,'if (unresolved(context) != null && !checkedServer) return', 'do not clear lock automatically')
assert_has(w, 'RecordWriteIntent.fromPersistedCode', 'typed write intent restored from prefs')
assert_has(source(core/'ReminderNotifications.kt'), 'e.httpStatus == 401 || e.httpStatus == 403', 'retries not inferred from message')
# The read-back reads JSON null as "" but keeps the user's literal text "null" (behaviour: harness PUT_LITERAL_NULL).
# Read-backs use the typed text reader (JSON null is "", the text "null" stays "null"); behaviour: harness
# scenario PUT_LITERAL_NULL.
assert_has(source(core/'LubeLoggerEditorApi.kt'), 'private fun JSONObject.savedText(key: String): String = savedRecord.text(this, key)', 'preserve literal null')
assert_has(source(core/'ExtraFieldValues.kt'), '!o.has(key) || o.isNull(key) -> null', 'do not render JSON-null stations')
assert_has(source(ui/'SettingsUi.kt'), 'if (confirmDisconnect) DisconnectConfirmDialog(', 'disconnect confirmation')
assert_has(source(ui/'SharedUi.kt'), 'if (confirmDisconnect) DisconnectConfirmDialog(', 'disconnect confirmation on the connection-problem screen')
r=source(core/'ReminderNotifications.kt')
assert_has(r,'setRequiredNetworkType(NetworkType.CONNECTED)', 'background network constraints')
assert_has(r,'LubeLogger rejected the API key', 'bad key diagnosis')
assert_has(source(ui/'SettingsUi.kt'),'if (granted) ReminderNotificationScheduler.checkNow(context)', 'notification permission grant')
assert_has(source(ui/'SettingsUi.kt'),'OpenFreeMap supplies map tiles', 'map privacy disclosure')
a=source(root/'app/build.gradle.kts')
assert_has(a,'versionName = "1.0.2"', 'release version (OD-80)')
assert_has(a,'versionCode = 46', 'release version code (OD-80)')
# The station lookups name the app's version in their User-Agent; it follows versionName.
version = re.search(r'versionName = "([^"]+)"', a).group(1)
agents = re.findall(r'LubeLink-Android/([0-9.]+)', source(core/'FuelStations.kt'))
assert agents and all(v == version for v in agents), f'User-Agent versions {agents} differ from versionName {version}'
assert_has(a,'applicationId = "com.kramttocs.lubelink"', 'production app identity (OD-02, 2026-10-19)')
assert_has(a,'minSdk = 30', 'Android 11 and later (OD-81)')
# Money is added only by ReportRules.moneyTotal (exact costs, rounded once, OD-77); the rounded row reader is never
# called in app code, so no total can be built from rounded rows.
main_src=root/'app/src/main/java'
row_reader_calls=[f'{f.name}:{n}' for f in main_src.rglob('*.kt') for n,line in enumerate(f.read_text(encoding='utf-8').splitlines(),1)
                  if 'readCost(' in line and 'fun readCost(' not in line]
assert not row_reader_calls, f'Rounded row costs used in app code (totals must use moneyTotal): {row_reader_calls}'
# Every script the CI workflow runs is run by scripts/run-local-gates.sh too, where that script exists (it is not
# part of every copy of this repository).
local_gates=root/'scripts/run-local-gates.sh'
gates=local_gates.read_text(encoding='utf-8') if local_gates.exists() else None
workflow_text=(root/'.github/workflows/android-ci.yml').read_text(encoding='utf-8')
ci_scripts=set(re.findall(r'(?<![/\w])(?:\./)?((?:scripts|tests|tools)/[A-Za-z0-9_./-]+\.(?:py|sh))', workflow_text))
# Not local by design: the signing script needs the release secrets (CI only; tests/release_signing_check.py runs
# its logic locally), and the .github/scripts helpers only format CI annotations.
ci_scripts-={'scripts/build-signed-release.sh'}
missing_gates=sorted(f for f in ci_scripts if f not in gates) if gates is not None else []
assert not missing_gates, f'CI runs scripts that scripts/run-local-gates.sh does not: {missing_gates}'
# One Robolectric SDK for every JVM test: a second one is a declared change, not a per-test annotation.
for test in (root/'app/src/test').rglob('*.kt'):
    assert not re.search(r'\bConfig\s*\([^)]*\bsdk\s*=', test.read_text(encoding='utf-8')), f'{test.name}: per-test Robolectric SDK'
assert 'LUBELINK_RELEASE_KEYSTORE' in a, 'local-only signing configuration'
assert '.gitignore' in [p.name for p in root.iterdir()], 'gitignore present'
# Page headings/actions must stay outside their scrolling content.
g=source(ui/'GarageUi.kt')
assert g.index('GarageHeading(onReports)') < g.index('LazyVerticalGrid('), 'Garage grid header must stay fixed'
assert g.index('GarageHeading(onReports)') < g.index('LazyColumn('), 'Garage list header must stay fixed'
c=source(ui/'CalendarUi.kt')
assert c.index('ContextHeader("Calendar"') < c.index('LazyColumn('), 'Calendar header must stay fixed'
rem=source(ui/'RemindersUi.kt')
assert rem.index('ContextHeader("Reminders"') < rem.index('LazyColumn('), 'Reminders header must stay fixed'
# Settings has a real read-only refresh cycle, rather than an interactive dead end.
app=source(ui/'LubeLinkApp.kt')
assert 'account.garage != null && nav.tab != Tab.REPORTS)' in app, 'Settings refresh must not be blocked'
assert 'refreshRequest = null' in app and 'done(failure)' in source(ui/'AccountRefresh.kt'), 'Always clear pull refresh indicator'
# AlertDialog titles use one gold divider pattern; custom record/editor/reminder titles
# already draw the same divider and should not acquire a second one.
dialog_header = source(ui/'DialogHeaderUi.kt')
assert_has(dialog_header, 'HorizontalDivider(color = Gold, thickness = 1.dp)', 'shared dialog divider')
ui_sources = [source(file) for file in ui.glob('*.kt')]
assert not any('title = { Text(' in content for content in ui_sources), 'plain AlertDialog title without gold divider'
assert sum(content.count('title = { LubeDialogTitle(') for content in ui_sources) >= 18, 'shared dialog title coverage'
# Extra-field discovery must be read-only and never erase location data on unknown API responses.
fields=source(core/'RecordFieldRules.kt')
assert 'LubeLink Gas Station' in fields and 'LubeLink Station Address' in fields, 'exact Fuel field names'
assert 'GasRecord' in fields and 'fieldType.equals("Text"' in fields, 'correct schema detection'
assert_has(v, 'arrayGet("/api/extrafields")', 'authenticated read-only API endpoint')
# The setup is read strictly (behaviour checked in the contract replay's custom-field section).
assert_has(v, 'val read = ResponseFields("the custom-field setup")', 'do not mistake malformed data for missing configuration')
account=source(ui/'AccountDataState.kt')
assert_has(account, 'gasFields = reader.checkGasStationFields()', 'record fields read only when requested')
assert_has(account, 'gasFields = if (prior.readiness', 'failed verification retains last known status')
editor=source(ui/'EditorUi.kt')
assert_has(editor, 'gasFields.readiness != com.kramttocs.lubelink.core.FieldReadiness.MISSING', 'hide station controls only when confirmed missing')
assert_has(editor, 'fun stationFieldError()', 'do not silently write unconfigured station fields')
assert_has(editor, 'onGasFieldsNeeded()', 'on-demand editor lookup')
assert_has(source(ui/'SettingsUi.kt'), '"Record Fields"', 'central Settings entry')
assert_has(source(ui/'SettingsUi.kt'), '"Check configuration"', 'manual recheck')
assert_has(source(ui/'SettingsUi.kt'), '"Copy field names"', 'setup assistance')
assert_has(source(ui/'SettingsUi.kt'), '"View field setup"', 'inline field setup available even when ready')
assert_has(source(ui/'SettingsUi.kt'), '"Text · Optional"', 'field type and optional status shown')
assert_has(source(ui/'SettingsUi.kt'), 'name !in gasFields.missing', 'typed individual field readiness without comparing label strings')
assert_has(source(ui/'DeleteRecordUi.kt'), 'vehicleLabel = vehicleLabel)', 'delete vehicle above divider')
assert_has(source(ui/'HistoryUi.kt'), 'LubeDialogVehicleLine(vehicleLabel, onOpenVehicle)', 'history vehicle link in header')
assert_has(source(ui/'RemindersUi.kt'), 'vehicleLabel = vehicleLabel, onOpenVehicle = onOpenVehicle', 'reminder vehicle in header')
assert_has(source(ui/'EditorUi.kt'), 'vehicleLabel = vehicle?.let(::entryLabel) ?: "Selected vehicle"', 'review vehicle in header')
# Replay hardening: streaming POST/PUT makes the JSON body non-replayable on a lost reply.
# Make sure the production-client harness cannot silently disappear while
# retaining source-level assertions for the same paths.
runner=source(root/'scripts/run-network-tests.sh')
assert_has(runner, 'ProductionClientNetworkChecksKt', 'execute real Editor/Manager network regression tests')
client_tests=source(root/'tests/jvm_harness/ProductionClientNetworkChecks.kt')
assert_has(client_tests, 'api.addRecord(', 'production POST client tested')
assert_has(client_tests, 'api.updateRecord(', 'production PUT client tested')
assert_has(client_tests, 'api.delete(', 'production DELETE client tested')
assert_has(client_tests, 'DELETE_400_VERIFY_FAIL', 'read-back failure handled as uncertain')
assert_has(e, 'connection.setFixedLengthStreamingMode(bodyBytes.size)', 'write bodies must not be automatically replayed')
assert_has(e, 'connection.outputStream.use { it.write(bodyBytes) }', 'write exactly one pre-encoded body')
assert_has(m, 'if (e.httpStatus != 400 && e.httpStatus != 404) throw e', 'DELETE 400/404 must be reconciled')
assert_has(m, 'return@withContext // The record is confirmed absent.', 'DELETE response reconciled by GET')
assert_has(m, 'httpStatus = if (method == "DELETE" || reportStatus) status else null', 'structured HTTP status for DELETE (and the opt-in key check)')
assert_has(editor, 'EditorRules.shouldClearUnavailableNewFuelStation(', 'all new-fuel missing-field transitions')
assert_has(editor, 'stationValuesOmitted = true', 'make omitted station visible')
assert_has(editor, 'Station not copied: Gas Station fields are not configured in LubeLogger.', 'copy omission explanation')
assert_has(editor, 'Station omitted: Gas Station fields are not configured in LubeLogger.', 'ordinary Add omission explanation')
assert_has(editor, 'LaunchedEffect(gasFields.readiness, kind, existingRecord?.id)', 'run missing-field transition on kind changes')
assert_has(source(core/'EditorRules.kt'), 'kind == RecordKind.FUEL && !isEdit && readiness == FieldReadiness.MISSING', 'missing-field new-fuel pure rule')
assert_has(source(ui/'SettingsUi.kt'), 'may hide local station favorites', 'credential rotation warning')
assert_has(c, 'MaintenanceRules.vehicleTitle(it, nicknames[id])', 'selected Calendar header matches History and Reminders')
assert_has(c, 'vehicles[id]?.let { MaintenanceRules.vehicleContextLabel(it, nicknames[id]) } ?: "Vehicle"', 'all-vehicles Calendar entries retain full identity')
from xml.etree import ElementTree as ET
manifest=ET.parse(root/'app/src/main/AndroidManifest.xml').getroot()
android='{http://schemas.android.com/apk/res/android}'
application=manifest.find('application')
assert application.attrib.get(android+'allowBackup') == 'false'
assert application.attrib.get(android+'dataExtractionRules') == '@xml/data_extraction_rules'
assert application.attrib.get(android+'fullBackupContent') == '@xml/backup_rules'
for xml_name,sections in [('data_extraction_rules.xml',('cloud-backup','device-transfer')),
                         ('backup_rules.xml',(None,))]:
    tree=ET.parse(root/'app/src/main/res/xml'/xml_name).getroot()
    for section in sections:
        parent=tree if section is None else tree.find(section)
        assert parent is not None, f'Missing {section} in {xml_name}'
        covered={(el.attrib.get('domain'),el.attrib.get('path')) for el in parent.findall('exclude')}
        # Android walks each storage domain on its own and matches excludes by exact path, so excluding root/. does
        # not cover files, databases or shared preferences (LL-02): every domain is excluded by name.
        domains={'root','file','database','sharedpref','external',
                 'device_root','device_file','device_database','device_sharedpref'}
        missing=sorted(d for d in domains if (d,'.') not in covered)
        assert not missing, f'Backups/transfers may retain private data: {xml_name}/{section} misses {missing}'
# Once in Git, enforce executable bits needed for Linux/macOS and CI.
if (root/'.git').exists():
    import subprocess
    names=['gradlew','scripts/run-offline-tests.sh','scripts/run-network-tests.sh']
    output=subprocess.check_output(['git','ls-files','-s','--',*names],cwd=root,text=True)
    modes={line.split('\t',1)[1]:line.split(' ',1)[0] for line in output.splitlines()}
    for name in names:
        if name in modes:
            assert modes[name]=='100755', f'{name} must be staged executable (git add --chmod=+x)'
print('Passed source-level safety, header, field-setup, replay, and backup assertions')

# Structural guardrails (existence/structure ONLY, not runtime proof).
assert 'import androidx.compose.ui.test.assertExists' not in source(root/'app/src/test/ui/java/com/kramttocs/lubelink/quality/EngineeringUiTest.kt'), 'Compose assertExists is a member, not importable'
assert_has(source(root/'app/build.gradle.kts'), 'verifyEmulatorOnly', 'connected test must check ADB device isolation')
assert_has(source(root/'app/build.gradle.kts'), 'dependsOn(verifyEmulatorOnly)', 'connected test gated by ADB preflight')
assert_has(source(ui/'WriteSafety.kt'), 'if (cleared) markerRevision++', 'unlock publishes observable marker transition')
assert_has(source(ui/'WriteSafetyHost.kt'), 'WriteSafety.markerRevision', 'lock dialog observes persisted marker change')
assert_has(source(ui/'HistoryUi.kt'), 'it.kind == typeFilter.kind', 'History filter typed, not label-based')
assert_has(source(core/'FuelStations.kt'), 'StationSearchPolicy.fallbackNotice(', 'Geoapify fallback classified by typed status')
assert 'e.message?.contains("usage limit")' not in source(core/'FuelStations.kt'), 'do not classify HTTP by rendered text'
assert 'readingLabel(reading:' not in source(core/'EditorRules.kt'), 'unused formatting helper removed'
# Every preferences file is opened through StoredFormats, which checks its format version first.
import re
opens=[(f.name, line) for f in (root/'app/src/main/java').rglob('*.kt')
       for line in f.read_text(encoding='utf-8').splitlines() if 'getSharedPreferences(' in line]
assert [n for n,_ in opens]==['StoredFormats.kt'], f'preferences opened outside StoredFormats: {opens}'
# Corners (OD-53): screens take their corners from the theme. Only ui/LubeTheme.kt names a corner shape (rounded or
# cut, absolute or not; RectangleShape has no corners to choose), and only
# ui/Buttons.kt uses Material's buttons, which are drawn fully round whatever the theme says.
kotlin=[f for f in (root/'app/src/main/java').rglob('*.kt')]
own_corners=[f'{f.name}: {line.strip()}' for f in kotlin if f.name!='LubeTheme.kt'
             for line in f.read_text(encoding='utf-8').splitlines() if re.search(r'\b(Absolute)?(RoundedCorner|CutCorner)Shape\b|\bCircleShape\b', line)]
assert not own_corners, f'corners named outside the theme (OD-53): {own_corners}'
material_buttons=[f'{f.name}: {line.strip()}' for f in kotlin if f.name!='Buttons.kt'
                  for line in f.read_text(encoding='utf-8').splitlines()
                  if re.search(r'androidx\.compose\.material3\.(Button|OutlinedButton|TextButton)\b(?!Defaults|Colors|Elevation)', line)]
assert not material_buttons, f"Material's buttons used outside ui/Buttons.kt (OD-53): {material_buttons}"
# Screen tests use Compose's v2 test rules, which resume effects on the main thread (TestDispatcherThreadTest).
old_rules=[f'{f.name}: {line.strip()}' for d in ('app/src/test','app/src/androidTest') for f in (root/d).rglob('*.kt')
           for line in f.read_text(encoding='utf-8').splitlines()
           if re.match(r'import androidx\.compose\.ui\.test\.junit4\.(createComposeRule|createAndroidComposeRule|createEmptyComposeRule)\b', line)]
assert not old_rules, f"Compose's first test rules used (use junit4.v2): {old_rules}"
# No user data in the app source: its own script, run here so CI runs it with these checks.
import runpy
runpy.run_path(str(root/'tests/user_data_check.py'), run_name='__main__')

