package com.kramttocs.lubelink.core

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.json.JsonNull

/**
 * Replays the responses captured from a disposable LubeLogger (tests/contract/<version>) through the unchanged
 * production clients: every captured read is parsed, the synthetic values come out exactly, and each field the
 * app relies on fails with a named error when it is missing or of the wrong type (docs/API-CONTRACT.md).
 * Args: <PKCS12 keystore> <capture dir>.
 */
fun main(args: Array<String>) = runBlocking {
    require(args.size == 2) { "Pass the synthetic keystore and the capture directory" }
    var passed = 0
    var failed = 0
    fun pass(name: String) { passed++; println("PASS contract replay: $name") }
    ContractReplayServer(args[0], args[1]).use { server ->
        val reader = LubeLoggerApi(server.baseUrl, "synthetic-editor")
        val editor = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val manager = LubeLoggerManagerApi(server.baseUrl, "synthetic-manager")

        // The captured values, read by the production parsers.
        check(reader.checkConnection() == "it-editor")
        editor.verifyConnection()
        val garage = reader.loadGarage()
        // Details are checked in their own section.
        check(garage.vehicles.map { it.copy(details = null) } == listOf(
            Vehicle(VehicleId(1), 2015, "Synthetic", "Wagon", "/defaults/noimage.png", useHours = false, isElectric = false),
            Vehicle(VehicleId(2), 2021, "Synthetic", "Volt", "/defaults/noimage.png", useHours = false, isElectric = true),
            Vehicle(VehicleId(3), 2010, "Synthetic", "Tractor", "/defaults/noimage.png", useHours = true, isElectric = false)
        )) { "Vehicles: ${garage.vehicles}" }
        check(garage.summaries[VehicleId(1)] == VehicleSummary(VehicleId(1), 1300, 1, 0, 1)) { "Summary: ${garage.summaries}" }
        val reminder = garage.reminders.single()
        check(reminder.id == ReminderId(1) && reminder.vehicleId == VehicleId(1) && reminder.description == "Rotate tires" &&
            reminder.urgency == "Urgent" && reminder.dueDate == "2026-11-01" && reminder.notes == "Synthetic reminder" &&
            reminder.isRecurring == null) { "Reminder: $reminder" }
        pass("whoami, vehicles, vehicle summaries and reminders parse to the synthetic values")

        val history = reader.loadHistory(VehicleId(1), includeFuel = true).also { check(it.missing.isEmpty()) }.records
        val service = history.single { it.kind == RecordKind.SERVICE }
        check(service.id == RecordId(1) && service.description == "Oil change" && service.odometer == 1200L &&
            service.cost == "45.5" && service.notes == "Synthetic note" && service.tags == "synthetic") { "Service: $service" }
        val repair = history.single { it.kind == RecordKind.REPAIR }
        check(repair.description == "Brake pads" && repair.cost == "120" && repair.notes == "") { "Repair: $repair" }
        val fuel = history.single { it.kind == RecordKind.FUEL }
        check(fuel.fuelConsumed == 12.5 && fuel.cost == "52.1" && fuel.notes == "" &&
            fuel.fuelStation == "Synthetic Fuel Stop" && fuel.fuelStationAddress == "1 Test Way, Testville") { "Fuel: $fuel" }
        check(history.none { it.kind == RecordKind.UPGRADE }) // the captured upgrade belongs to vehicle 3
        val calendar = reader.loadCalendarHistory(includeFuel = true).also { check(it.missing.isEmpty()) }.records
        check(calendar.single { it.kind == RecordKind.UPGRADE }.let { it.vehicleId == VehicleId(3) && it.cost == "89.99" })
        check(calendar.size == 4) { "Calendar: $calendar" }
        check(reader.checkGasStationFields().readiness == FieldReadiness.READY)
        pass("per-vehicle and all-vehicle records, fuel stations and extra-field setup parse to the synthetic values")

        val wagon = garage.vehicles.first()
        val serviceSnapshot = editor.loadEditableRecord(wagon, service.ref)
        check(serviceSnapshot.draft == EditorDraft(RecordKind.SERVICE, "2026-10-01", "1200", "Oil change", "45.5",
            "Synthetic note", "synthetic", filledTank = false)) { "Service edit: ${serviceSnapshot.draft}" }
        val fuelSnapshot = editor.loadEditableRecord(wagon, fuel.ref)
        check(fuelSnapshot.draft == EditorDraft(RecordKind.FUEL, "2026-10-04", "1300", "", "52.1", "", "", "12.5",
            filledTank = true, missedFill = false, stationName = "Synthetic Fuel Stop",
            stationAddress = "1 Test Way, Testville")) { "Fuel edit: ${fuelSnapshot.draft}" }
        manager.prepare(wagon, service)
        manager.prepare(wagon, fuel)
        pass("Edit opens the captured service and fuel records unchanged; Delete accepts the displayed records")

        val missing = server.capturedReads() - server.requested().map { it.removePrefix("GET ") }.toSet()
        check(missing.isEmpty()) { "Captured reads never replayed: $missing" }
        pass("every captured read (${server.capturedReads().size} paths) went through a production parser")

        // Each check below runs on its own, so a run on code without the fix shows every check that fails.
        val problems = mutableListOf<String>() // every field case that failed in the current section
        suspend fun section(name: String, block: suspend () -> Unit) {
            problems.clear()
            try {
                block()
                check(problems.isEmpty()) { "${problems.size} field cases:\n    " + problems.joinToString("\n    ") }
                pass(name)
            }
            catch (e: Throwable) { failed++; println("FAIL contract replay: $name: ${e.message}") }
            finally { server.reset() }
        }
        val loadGarage: suspend () -> Unit = { reader.loadGarage() }
        val serviceHistory: suspend () -> RecordsLoad = { reader.loadHistory(VehicleId(1), includeFuel = true) }
        val allHistory: suspend () -> RecordsLoad = { reader.loadCalendarHistory(includeFuel = true) }
        // A record type whose answer has a bad field is reported missing; the other types still load (OD-31).
        suspend fun eachFieldMissingKind(path: String, captureName: String, fields: List<String>, kind: RecordKind,
                                         load: suspend () -> RecordsLoad) {
            for (field in fields) for (wrong in listOf<Any?>(null, JSONObject())) {
                server.serveBody(path, JSONArray(server.body(captureName)).also { it.optJSONObject(0)!!.put(field, wrong) }.toString())
                val result = runCatching { load() }.getOrNull()
                if (result == null || result.missing != setOf(kind) || result.records.any { it.kind == kind } ||
                    result.records.isEmpty())
                    problems.add("$path ${if (wrong == null) "without" else "with the wrong type for"} $field: " +
                        "expected $kind reported missing and the other types kept, got ${result?.missing} / " +
                        "${result?.records?.map { it.kind }}")
                server.reset()
            }
        }

        // A field the app relies on, missing or of the wrong type, fails with that field named.
        suspend fun expectUnreadable(label: String, field: String, call: suspend () -> Unit) {
            val failure = runCatching { call() }.exceptionOrNull()
            check(failure is UnreadableResponseException && failure.field == field) {
                "$label: expected an unreadable \"$field\", got ${failure?.javaClass?.simpleName}: ${failure?.message}"
            }
        }
        suspend fun eachField(path: String, captureName: String, fields: List<String>, call: suspend () -> Unit,
                              pick: (JSONArray) -> JSONObject = { it.optJSONObject(0)!! }) {
            // Wrong type: an object where a value belongs, or text where an object belongs.
            for (field in fields) for (wrong in listOf<Any?>(null, if (field == "vehicleData") "x" else JSONObject())) {
                val body = server.body(captureName)
                val changed = if (body.trimStart().startsWith("[")) JSONArray(body).also { pick(it).put(field, wrong) }.toString()
                    else JSONObject(body).put(field, wrong).toString()
                server.serveBody(path, changed)
                try { expectUnreadable("$path ${if (wrong == null) "without" else "with the wrong type for"} $field", field, call) }
                catch (e: IllegalStateException) { problems.add(e.message.orEmpty()) }
                server.reset()
            }
        }
        // Summaries and reminders: a bad field makes that section "missing" (shown as such, OD-24), never 0 or "none".
        suspend fun eachFieldMissing(path: String, captureName: String, fields: List<String>, section: GarageSection) {
            for (field in fields) for (wrong in listOf<Any?>(null, if (field == "vehicleData") "x" else JSONObject())) {
                server.serveBody(path, JSONArray(server.body(captureName)).also { it.optJSONObject(0)!!.put(field, wrong) }.toString())
                val garage = runCatching { reader.loadGarage() }.getOrNull()
                val empty = if (section == GarageSection.SUMMARIES) garage?.summaries?.isEmpty() else garage?.reminders?.isEmpty()
                if (garage == null || section !in garage.missing || empty != true || garage.vehicles.size != 3)
                    problems.add("$path ${if (wrong == null) "without" else "with the wrong type for"} $field: " +
                        "expected the vehicles with $section missing, got ${garage?.missing} / ${garage?.vehicles?.size} vehicles")
                server.reset()
            }
        }
        section("Garage and connection checks: each required field, missing or wrongly typed, is a named error or a missing section") {
            eachField("/api/vehicles", "vehicles",
                listOf("id", "year", "make", "model", "imageLocation", "useHours", "isElectric"), loadGarage)
            eachFieldMissing("/api/vehicle/info", "vehicle-info", listOf("vehicleData", "lastReportedOdometer",
                "urgentReminderCount", "veryUrgentReminderCount", "pastDueReminderCount", "serviceRecordCount"),
                GarageSection.SUMMARIES)
            eachFieldMissing("/api/vehicle/reminders/all", "reminders-all", listOf("id", "vehicleId", "description", "urgency",
                "userMetric", "metric", "dueDate", "dueOdometer", "dueDays", "dueDistance", "notes"), GarageSection.REMINDERS)
            eachField("/api/whoami", "whoami-editor", listOf("username"), { reader.checkConnection() })
            eachField("/api/whoami", "whoami-editor", listOf("username"), { editor.verifyConnection() })
            // A user name that names nobody (blank, spaces, JSON null) is not a working connection.
            fun whoamiWith(value: String) = server.body("whoami-editor")
                .replace(Regex("\"username\"\\s*:\\s*\"[^\"]*\""), "\"username\":$value")
            for (blank in listOf("\"\"", "\"   \"", "null")) {
                server.serveBody("/api/whoami", whoamiWith(blank))
                try {
                    expectUnreadable("Setup with username $blank", "username") { reader.checkConnection() }
                    expectUnreadable("Editor key with username $blank", "username") { editor.verifyConnection() }
                } catch (e: IllegalStateException) { problems.add(e.message.orEmpty()) }
                server.reset()
            }
            // The Manager check compares user names as text only: two keys both answering the number 7 do not match.
            server.serveBody("/api/whoami", whoamiWith("7"))
            if (runCatching { manager.verifySameAccount("synthetic-editor") }.isSuccess)
                problems.add("Manager: two numeric user names were taken as the same account")
            server.reset()
        }

        section("Vehicle details: read as captured; one missing or malformed detail makes that vehicle's details unreadable, never blank, and the Garage still loads") {
            // As captured: every vehicle's details are read, with the values the capture holds.
            val captured = JSONArray(server.body("vehicles"))
            val garage = reader.loadGarage()
            for (index in 0 until captured.length()) {
                val raw = captured.optJSONObject(index)!!
                val details = garage.vehicles.single { it.id.value == raw.optInt("id") }.details
                fun date(key: String) = if (raw.isNull(key)) null else java.time.LocalDate.parse(raw.optString(key))
                fun price(key: String) = raw.optString(key).toBigDecimal().takeIf { it.signum() != 0 }
                val expected = VehicleDetails(raw.optString("licensePlate"), raw.optBoolean("isDiesel"), date("purchaseDate"),
                    price("purchasePrice"), date("soldDate"), price("soldPrice"),
                    raw.optJSONArray("tags")!!.let { tags -> (0 until tags.length()).map { tags.opt(it).toString() } })
                if (details != expected) problems.add("vehicle ${raw.optInt("id")}: read $details, captured $expected")
            }
            suspend fun malformed(label: String, change: (JSONObject) -> Unit) {
                server.serveBody("/api/vehicles", JSONArray(server.body("vehicles")).also { change(it.optJSONObject(0)!!) }.toString())
                val result = runCatching { reader.loadGarage() }.getOrNull()
                when {
                    result == null -> problems.add("$label: the Garage did not load")
                    result.missing.isNotEmpty() || result.vehicles.size != captured.length() -> problems.add("$label: Garage $result")
                    result.vehicles.first().details != null -> problems.add("$label: read as ${result.vehicles.first().details}")
                    result.vehicles.drop(1).any { it.details == null } -> problems.add("$label: another vehicle's details were lost")
                }
                server.reset()
            }
            for (field in listOf("licensePlate", "isDiesel", "purchaseDate", "purchasePrice", "soldDate", "soldPrice", "tags")) {
                malformed("details without $field") { it.put(field, null) }
                malformed("details with an object for $field") { it.put(field, JSONObject()) }
            }
            // A date never set as empty text, as a LubeLogger whose vehicles were saved that way sends it: read as not
            // set, like null, and the other details still shown.
            server.serveBody("/api/vehicles", JSONArray(server.body("vehicles")).also {
                it.optJSONObject(0)!!.put("purchaseDate", "").put("soldDate", "")
            }.toString())
            val emptyDates = reader.loadGarage().vehicles.first().details
            val first = captured.optJSONObject(0)!!
            if (emptyDates == null || emptyDates.purchaseDate != null || emptyDates.soldDate != null ||
                emptyDates.licensePlate != first.optString("licensePlate") || emptyDates.tags.size != first.optJSONArray("tags")!!.length())
                problems.add("dates sent as empty text: read as $emptyDates")
            server.reset()
            malformed("a purchase date in another format") { it.put("purchaseDate", "03/14/2019") }
            malformed("a purchase date of spaces") { it.put("purchaseDate", "  ") }
            malformed("a negative purchase price") { it.put("purchasePrice", -5) }
            malformed("a tag that is not text") { it.put("tags", JSONArray().put("family").put(7)) }
        }

        section("History and Calendar: a record type with a required field missing or wrongly typed is reported missing; the others still load") {
            eachFieldMissingKind("/api/vehicle/servicerecords?vehicleId=1", "servicerecords-vehicle1",
                listOf("id", "vehicleId", "date", "description", "odometer", "cost", "notes", "tags", "files"),
                RecordKind.SERVICE, serviceHistory)
            eachFieldMissingKind("/api/vehicle/gasrecords?vehicleId=1&useMPG=true&useUKMPG=false", "gasrecords-vehicle1",
                listOf("id", "vehicleId", "date", "odometer", "cost", "notes", "tags", "files", "fuelEconomy",
                    "fuelConsumed", "extraFields"), RecordKind.FUEL, serviceHistory)
            eachFieldMissingKind("/api/vehicle/servicerecords/all", "servicerecords-all", listOf("id", "vehicleId", "cost"),
                RecordKind.SERVICE, allHistory)
        }

        section("History, Calendar and Reports: one failing record type is named and the rest kept; Reports waits; all failing is an error") {
            server.serveRaw("/api/vehicle/repairrecords?vehicleId=1", 500, "text/html", "<html>synthetic server fault</html>")
            val partial = serviceHistory()
            check(partial.missing == setOf(RecordKind.REPAIR) && partial.records.none { it.kind == RecordKind.REPAIR } &&
                partial.records.any { it.kind == RecordKind.SERVICE }) { "History with Repair failing: ${partial.missing}" }
            server.reset()
            server.serveRaw("/api/vehicle/repairrecords/all", 500, "text/html", "<html>synthetic server fault</html>")
            val all = allHistory()
            check(all.missing == setOf(RecordKind.REPAIR) && all.records.isNotEmpty()) { "Calendar with Repair failing: ${all.missing}" }
            val report = runCatching { reader.loadReportDataset() }.exceptionOrNull()
            check(report is IncompleteRecordsException && report.load.missing == setOf(RecordKind.REPAIR) &&
                report.message!!.startsWith("Repair records couldn't be loaded")) { "Reports with Repair failing: $report" }
            server.reset()
            for (kind in listOf("servicerecords", "repairrecords", "upgraderecords"))
                server.serveRaw("/api/vehicle/$kind?vehicleId=1", 500, "text/html", "<html>synthetic server fault</html>")
            server.serveRaw("/api/vehicle/gasrecords?vehicleId=1&useMPG=true&useUKMPG=false", 500, "text/html",
                "<html>synthetic server fault</html>")
            val everyType = runCatching { serviceHistory() }.exceptionOrNull()
            check(everyType is LubeLoggerApi.ApiException && everyType.httpStatus == 500) { "Every type failing: $everyType" }
        }

        section("Edit and Delete: a record or vehicle missing a field they show or send back is a named error, not a blank") {
            eachField("/api/vehicle/servicerecords?vehicleId=1&id=1", "servicerecords-by-id",
                listOf("date", "odometer", "description", "cost", "notes", "tags"), { editor.loadEditableRecord(wagon, service.ref) })
            eachField("/api/vehicle/gasrecords?vehicleId=1&id=1&useMPG=true&useUKMPG=false", "gasrecords-by-id",
                listOf("date", "odometer", "cost", "notes", "tags", "fuelConsumed", "isFillToFull", "missedFuelUp",
                    "startingSoc", "endingSoc"), { editor.loadEditableRecord(wagon, fuel.ref) })
            eachField("/api/vehicles", "vehicles", listOf("id", "year", "make", "model"),
                { editor.loadEditableRecord(wagon, service.ref) })
            eachField("/api/vehicles", "vehicles", listOf("id", "year", "make", "model"), { manager.prepare(wagon, service) })
        }

        section("allowed JSON nulls read as empty or unknown; stray list entries and foreign records are errors") {
            // JSON null where the contract allows it is "empty" or "not known", never the word null or a zero.
            val nullOdometer = JSONArray(server.body("servicerecords-vehicle1"))
                .also { it.optJSONObject(0)!!.put("odometer", JsonNull).put("description", JsonNull) }.toString()
            server.serveBody("/api/vehicle/servicerecords?vehicleId=1", nullOdometer)
            val unknown = reader.loadHistory(VehicleId(1), includeFuel = false).records.single { it.kind == RecordKind.SERVICE }
            check(unknown.odometer == null && unknown.description == "") { "Null fields: $unknown" }
            server.reset()
            // A list entry that is not an object fails the response instead of disappearing.
            server.serveBody("/api/vehicles", JSONArray(server.body("vehicles")).put(7).toString())
            expectUnreadable("vehicle list with a number entry", "list entry 4") { reader.loadGarage() }
            server.reset()
            // A record from another vehicle in a per-vehicle answer is refused, not shown under this vehicle.
            server.serveBody("/api/vehicle/servicerecords?vehicleId=1",
                JSONArray(server.body("servicerecords-vehicle1")).also { it.optJSONObject(0)!!.put("vehicleId", 2) }.toString())
            // Since OD-31 that type is reported missing (named), never shown under this vehicle.
            val foreign = serviceHistory()
            check(foreign.missing == setOf(RecordKind.SERVICE) && foreign.records.none { it.kind == RecordKind.SERVICE }) {
                "Per-vehicle records naming another vehicle: ${foreign.missing}"
            }
            server.reset()
        }

        section("Custom fields: a malformed field setup or fuel-station field is a named error, never \"not set up\" or a blank") {
            val setup: suspend () -> Unit = { reader.checkGasStationFields() }
            eachField("/api/extrafields", "extrafields", listOf("recordType", "extraFields"), setup)
            for (field in listOf("name", "fieldType", "isRequired")) for (wrong in listOf<Any?>(null, JSONObject())) {
                val body = JSONArray(server.body("extrafields")).also {
                    it.optJSONObject(0)!!.optJSONArray("extraFields")!!.optJSONObject(0)!!.put(field, wrong)
                }.toString()
                server.serveBody("/api/extrafields", body)
                try { expectUnreadable("field setup ${if (wrong == null) "without" else "with the wrong type for"} $field", field, setup) }
                catch (e: IllegalStateException) { problems.add(e.message.orEmpty()) }
                server.reset()
            }
            server.serveBody("/api/extrafields", JSONArray(server.body("extrafields")).put(7).toString())
            try { expectUnreadable("field setup with a number entry", "list entry 2", setup) }
            catch (e: IllegalStateException) { problems.add(e.message.orEmpty()) }
            server.reset()
            // A JSON-null station value is an empty station, never the word "null"; the fuel records still load.
            server.serveBody("/api/vehicle/gasrecords?vehicleId=1&useMPG=true&useUKMPG=false",
                JSONArray(server.body("gasrecords-vehicle1")).also {
                    it.optJSONObject(0)!!.optJSONArray("extraFields")!!.optJSONObject(0)!!.put("value", JsonNull)
                }.toString())
            val nullStation = runCatching { serviceHistory() }.getOrNull()
            val nullFuel = nullStation?.records?.firstOrNull { it.kind == RecordKind.FUEL }
            if (nullStation == null || nullStation.missing.isNotEmpty() || nullFuel == null || nullFuel.fuelStation != "")
                problems.add("fuel record with a null station value: expected an empty station, got ${nullFuel?.fuelStation} / ${nullStation?.missing}")
            server.reset()
            // A fuel record whose station field is not text, or whose custom-field list holds a non-object, makes
            // the fuel records unreadable (reported missing), never a blank station.
            val fuelPath = "/api/vehicle/gasrecords?vehicleId=1&useMPG=true&useUKMPG=false"
            for ((label, change) in listOf<Pair<String, (JSONArray) -> Unit>>(
                "a station value that is an object" to { fields -> fields.optJSONObject(0)!!.put("value", JSONObject()) },
                "a station value that is a number" to { fields -> fields.optJSONObject(0)!!.put("value", 12) },
                "a field name that is a number" to { fields -> fields.optJSONObject(0)!!.put("name", 3) },
                "an entry that is not an object" to { fields -> fields.put(7) },
                // Every entry is checked, wherever it is, and a field name appears once.
                "a malformed entry after the station fields" to { fields -> fields.put(JSONObject().put("name", 7).put("value", "x")) },
                "a wrong-type value on another field" to { fields -> fields.put(JSONObject().put("name", "Other").put("value", 5)) },
                "the station field twice" to { fields -> fields.put(JSONObject().put("name", "LubeLink Gas Station").put("value", "Second")) },
                // The value member must be there; only a present JSON null means "no station".
                "a station entry without its value member" to { fields -> fields.optJSONObject(0)!!.remove("value") })) {
                server.serveBody(fuelPath, JSONArray(server.body("gasrecords-vehicle1"))
                    .also { change(it.optJSONObject(0)!!.optJSONArray("extraFields")!!) }.toString())
                val result = runCatching { serviceHistory() }.getOrNull()
                if (result == null || result.missing != setOf(RecordKind.FUEL) || result.records.any { it.kind == RecordKind.FUEL })
                    problems.add("fuel records with $label: expected FUEL reported missing, got ${result?.missing}")
                server.reset()
            }
        }

        // LL-05: a list that repeats an id is unreadable, never counted twice or shown twice (Compose list keys).
        section("Duplicate ids: a repeated vehicle fails Garage, repeated reminders or summaries are missing, a repeated record type is missing") {
            fun doubled(captureName: String) = JSONArray(server.body(captureName)).also { it.put(JSONObject(it.optJSONObject(0)!!.toString())) }.toString()
            server.serveBody("/api/vehicles", doubled("vehicles"))
            val vehicles = runCatching { reader.loadGarage() }.exceptionOrNull()
            if (vehicles !is UnreadableResponseException || vehicles.field != "id")
                problems.add("repeated vehicle: expected an unreadable \"id\", got ${vehicles?.javaClass?.simpleName ?: "success"}")
            server.reset()
            server.serveBody("/api/vehicle/reminders/all", doubled("reminders-all"))
            val garage = runCatching { reader.loadGarage() }.getOrNull()
            if (garage == null || GarageSection.REMINDERS !in garage.missing || garage.reminders.isNotEmpty())
                problems.add("repeated reminder: expected reminders missing, got ${garage?.missing} / ${garage?.reminders?.size} reminders")
            server.reset()
            server.serveBody("/api/vehicle/servicerecords?vehicleId=1", doubled("servicerecords-vehicle1"))
            val history = runCatching { serviceHistory() }.getOrNull()
            if (history == null || history.missing != setOf(RecordKind.SERVICE) || history.records.any { it.kind == RecordKind.SERVICE })
                problems.add("repeated service record: expected Service missing and not counted, got ${history?.missing} / " +
                    "${history?.records?.count { it.kind == RecordKind.SERVICE }} service records")
            server.reset()
            // A repeated vehicle summary with a conflicting reading, in either order: summaries missing, never one chosen.
            for (conflictFirst in listOf(false, true)) {
                val rows = JSONArray(server.body("vehicle-info"))
                val conflict = JSONObject(rows.optJSONObject(0)!!.toString()).put("lastReportedOdometer", 999_999)
                val body = JSONArray()
                if (conflictFirst) body.put(conflict)
                for (i in 0 until rows.length()) body.put(rows.optJSONObject(i)!!)
                if (!conflictFirst) body.put(conflict)
                server.serveBody("/api/vehicle/info", body.toString())
                val withConflict = runCatching { reader.loadGarage() }.getOrNull()
                if (withConflict == null || GarageSection.SUMMARIES !in withConflict.missing || withConflict.summaries.isNotEmpty() ||
                    withConflict.vehicles.isEmpty())
                    problems.add("repeated summary (conflict ${if (conflictFirst) "first" else "last"}): expected summaries missing and " +
                        "the vehicles kept, got ${withConflict?.missing} / ${withConflict?.summaries?.size} summaries")
                server.reset()
            }
            // Control: the same id in two record types is two different records, both kept.
            val all = serviceHistory()
            val shared = all.records.groupBy { it.id }.filterValues { list -> list.map { it.kind }.distinct().size > 1 }
            if (all.missing.isNotEmpty()) problems.add("control: unchanged captures reported ${all.missing} missing")
            if (shared.isEmpty()) println("NOTE contract replay: the captures have no id shared across record types")
        }
        section("Garage: failing summaries or reminders are reported missing; the vehicles still load; failing vehicles fail it") {
            server.serveRaw("/api/vehicle/reminders/all", 500, "text/html", "<html>synthetic server fault</html>")
            val noReminders = reader.loadGarage()
            check(noReminders.vehicles.size == 3 && noReminders.reminders.isEmpty() &&
                noReminders.missing == setOf(GarageSection.REMINDERS) && noReminders.summaries.size == 3) { "reminders: $noReminders" }
            server.reset()
            server.serveBody("/api/vehicle/info", JSONArray(server.body("vehicle-info"))
                .also { it.optJSONObject(0)!!.put("serviceRecordCount", null) }.toString())
            val noSummaries = reader.loadGarage()
            check(noSummaries.vehicles.size == 3 && noSummaries.summaries.isEmpty() && noSummaries.reminders.size == 1 &&
                noSummaries.missing == setOf(GarageSection.SUMMARIES)) { "summaries: $noSummaries" }
            server.reset()
            check(reader.loadGarage().missing.isEmpty())
            server.serveRaw("/api/vehicles", 500, "text/html", "<html>synthetic server fault</html>")
            check(runCatching { reader.loadGarage() }.exceptionOrNull() is LubeLoggerApi.ApiException)
            server.reset()
        }

        section("wrong key, server fault, a non-LubeLogger answer and a missing record are distinct messages") {
            // Failure forms are distinct, plain-language states.
            server.serveCapture("/api/vehicles", "error-wrong-key")
            val rejected = runCatching { reader.loadGarage() }.exceptionOrNull()
            check(rejected is LubeLoggerApi.ApiException && rejected.httpStatus == 401 &&
                rejected.message!!.startsWith("LubeLogger did not accept the API key")) { "401: ${rejected?.message}" }
            server.reset()
            server.serveCapture("/api/whoami", "error-whoami-wrong-key")
            check(runCatching { reader.checkConnection() }.exceptionOrNull().let {
                it is LubeLoggerApi.ApiException && it.httpStatus == 401 })
            server.reset()
            server.serveRaw("/api/vehicles", 500, "text/html", "<html>synthetic server fault</html>")
            val fault = runCatching { reader.loadGarage() }.exceptionOrNull()
            check(fault is LubeLoggerApi.ApiException && fault.httpStatus == 500 &&
                fault.message!!.startsWith("LubeLogger had a problem on the server")) { "500: ${fault?.message}" }
            server.reset()
            server.serveRaw("/api/vehicles", 200, "text/html", "<html>synthetic sign-in page</html>")
            val notJson = runCatching { reader.loadGarage() }.exceptionOrNull()
            check(notJson is LubeLoggerApi.ApiException && notJson.message == NOT_LUBELOGGER_MESSAGE) { "HTML: ${notJson?.message}" }
            server.reset()
            server.serveCapture("/api/vehicle/servicerecords?vehicleId=1&id=1", "error-record-missing")
            check(runCatching { editor.loadEditableRecord(wagon, service.ref) }.exceptionOrNull()?.message ==
                "Record missing in LubeLogger. Editing blocked.")
            check(runCatching { manager.prepare(wagon, service) }.exceptionOrNull()?.message ==
                "Record no longer exists. Refresh History or Calendar.")
            server.reset()
        }
    }
    println("Passed $passed contract replay checks against ${args[1]}; $failed failed")
    check(failed == 0) { "$failed contract replay checks failed" }
}
