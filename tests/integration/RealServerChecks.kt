package com.kramttocs.lubelink.core

import kotlinx.coroutines.runBlocking

/**
 * Runs the production read, Editor and Manager clients against a REAL disposable LubeLogger 1.7.4
 * (scripts/lubelogger-fixture.sh) through a localhost TLS front. Synthetic data only; the fixture
 * refuses to run against anything but the container it started. Behaviour tests of the read, Editor and Manager clients.
 *
 * Args: <pkcs12 keystore> <backend port> <viewer key> <editor key> <manager key>
 */
fun main(args: Array<String>): Unit = runBlocking {
    require(args.size == 5) { "keystore, backend port, viewer, editor and manager keys required" }
    val (keystore, port, viewerKey, editorKey, managerKey) = args
    TlsProxy(keystore, port.toInt()).use { proxy ->
        val base = proxy.baseUrl
        val reader = LubeLoggerApi(base, viewerKey)
        val editor = LubeLoggerEditorApi(base, editorKey)
        val manager = LubeLoggerManagerApi(base, managerKey)
        var passed = 0
        suspend fun case(name: String, block: suspend () -> Unit) {
            block()
            passed++
            println("PASS real server: $name")
        }

        val vehicles = reader.loadGarage().vehicles
        val car = vehicles.single { it.model == "Wagon" }
        val ev = vehicles.single { it.model == "Volt" }
        val tractor = vehicles.single { it.model == "Tractor" }
        // A real server answers every record type; a missing one would hide records, so it fails the check.
        suspend fun history(v: Vehicle) = reader.loadHistory(v.id, includeFuel = true)
            .also { check(it.missing.isEmpty()) { "Record types missing: ${it.missing}" } }.records

        case("Garage loads the three synthetic vehicles with EV and hour-meter flags") {
            check(vehicles.size == 3) { "vehicles: $vehicles" }
            check(ev.isElectric && !car.isElectric)
            check(tractor.useHours && !car.useHours)
        }

        case("Vehicle details are read as LubeLogger stores them: purchase, tags, diesel; unset ones are none") {
            check(car.details == VehicleDetails("TEST-001", isDiesel = true, purchaseDate = java.time.LocalDate.of(2019, 3, 14),
                purchasePrice = java.math.BigDecimal(27450), soldDate = null, soldPrice = null, tags = listOf("synthetic-family", "synthetic-commuter"))) {
                "Wagon details: ${car.details}"
            }
            check(ev.details == VehicleDetails("TEST-EV1", false, null, null, null, null, emptyList())) { "Volt details: ${ev.details}" }
        }

        case("Record Fields readiness is READY for the configured station fields") {
            val check = reader.checkGasStationFields()
            check(check.readiness == FieldReadiness.READY) { "readiness: $check" }
        }

        val service = EditorDraft(RecordKind.SERVICE, "2026-10-12", "1200", "Oil change",
            "45.50", "Synthetic note", "it")
        val serviceId = editor.addRecord(car, car.id, service)
        case("Service add returns the server ID and reads back the persisted values") {
            val saved = history(car).single { it.kind == RecordKind.SERVICE && it.id == serviceId }
            check(saved.description == "Oil change" && saved.odometer == 1200L) { "saved: $saved" }
            check(MaintenanceRules.parseDate(saved.date).toString() == "2026-10-12") { "date: ${saved.date}" }
            check(saved.notes == "Synthetic note") { "notes: ${saved.notes}" }
        }

        val fuel = EditorDraft(RecordKind.FUEL, "2026-10-13", "1300", "", "52.10", "", "",
            gallons = "12.5", stationName = "Synthetic Fuel Stop", stationAddress = "1 Test Way, Testville")
        val fuelId = editor.addRecord(car, car.id, fuel)
        case("Fuel add stores station name and address in LubeLogger and History shows them") {
            val saved = history(car).single { it.kind == RecordKind.FUEL && it.id == fuelId }
            check(saved.fuelStation == "Synthetic Fuel Stop") { "station: '${saved.fuelStation}'" }
            check(saved.fuelStationAddress == "1 Test Way, Testville") { "address: '${saved.fuelStationAddress}'" }
        }

        case("Edit changes the description and verifies the read-back") {
            val snapshot = editor.loadEditableRecord(car, RecordRef(car.id, RecordKind.SERVICE, serviceId))
            editor.updateRecord(car, car.id, snapshot, snapshot.draft.copy(description = "Oil and filter"))
            val saved = history(car).single { it.kind == RecordKind.SERVICE && it.id == serviceId }
            check(saved.description == "Oil and filter") { "description: ${saved.description}" }
        }

        case("Literal text \"null\" in notes survives an unrelated edit") {
            var snapshot = editor.loadEditableRecord(car, RecordRef(car.id, RecordKind.SERVICE, serviceId))
            editor.updateRecord(car, car.id, snapshot, snapshot.draft.copy(notes = "null"))
            snapshot = editor.loadEditableRecord(car, RecordRef(car.id, RecordKind.SERVICE, serviceId))
            editor.updateRecord(car, car.id, snapshot, snapshot.draft.copy(cost = "46.00"))
            val saved = history(car).single { it.kind == RecordKind.SERVICE && it.id == serviceId }
            check(saved.notes == "null") { "notes: '${saved.notes}'" }
        }

        case("Fuel edit keeps the station fields it did not change") {
            val snapshot = editor.loadEditableRecord(car, RecordRef(car.id, RecordKind.FUEL, fuelId))
            editor.updateRecord(car, car.id, snapshot, snapshot.draft.copy(notes = "Edited"))
            val saved = history(car).single { it.kind == RecordKind.FUEL && it.id == fuelId }
            check(saved.notes == "Edited")
            check(saved.fuelStation == "Synthetic Fuel Stop" && saved.fuelStationAddress == "1 Test Way, Testville")
        }

        case("Edit from a stale snapshot is refused and the newer server value is kept") {
            val stale = editor.loadEditableRecord(car, RecordRef(car.id, RecordKind.SERVICE, serviceId))
            val fresh = editor.loadEditableRecord(car, RecordRef(car.id, RecordKind.SERVICE, serviceId))
            editor.updateRecord(car, car.id, fresh, fresh.draft.copy(description = "Changed elsewhere"))
            val failure = runCatching {
                editor.updateRecord(car, car.id, stale, stale.draft.copy(description = "Stale write"))
            }.exceptionOrNull()
            check(failure is LubeLoggerEditorApi.EditorApiException) { "expected conflict, got $failure" }
            val saved = history(car).single { it.kind == RecordKind.SERVICE && it.id == serviceId }
            check(saved.description == "Changed elsewhere") { "description: ${saved.description}" }
        }

        case("The saved fingerprint detects a server change made after the form opened") {
            val ref = RecordRef(car.id, RecordKind.SERVICE, serviceId)
            val opened = editor.loadEditableRecord(car, ref)
            val saved = LubeLoggerEditorApi.baselineFingerprint(opened) // what the form saves across process death
            check(saved.length == 64) { "fingerprint not bounded: ${saved.length}" }
            check(LubeLoggerEditorApi.baselineFingerprint(editor.loadEditableRecord(car, ref)) == saved) { "false conflict" }
            val other = editor.loadEditableRecord(car, ref)
            editor.updateRecord(car, car.id, other, other.draft.copy(notes = "updated by another session"))
            val fresh = editor.loadEditableRecord(car, ref)
            check(LubeLoggerEditorApi.baselineFingerprint(fresh) != saved) { "server change not detected" }
            // And the client still refuses a write prepared from the stale snapshot.
            val failure = runCatching {
                editor.updateRecord(car, car.id, opened, opened.draft.copy(description = "Stale restored edit"))
            }.exceptionOrNull()
            check(failure is LubeLoggerEditorApi.EditorApiException) { "expected conflict, got $failure" }
            val savedRecord = history(car).single { it.kind == RecordKind.SERVICE && it.id == serviceId }
            check(savedRecord.notes == "updated by another session") { "notes overwritten: ${savedRecord.notes}" }
        }

        case("An unchanged over-limit station name from LubeLogger does not block an unrelated edit") {
            // Stored directly through the LubeLogger API, as another client or an import could.
            val imported = "Synthetic imported station with a deliberately long name ".repeat(3).trim()
            check(imported.length > EditorRules.STATION_NAME_MAX)
            val reply = rawPost(base, editorKey, EditorRules.endpoint(car, RecordKind.FUEL, car.id),
                """{"Date":"2026-10-16","Odometer":"1500","Cost":30.5,"Notes":"","Tags":"","FuelConsumed":8,""" +
                    """"IsFillToFull":"true","MissedFuelUp":"false","ExtraFields":[{"name":"LubeLink Gas Station",""" +
                    """"value":"$imported","isRequired":false,"fieldType":0}]}""")
            val id = RecordId(Regex("\"recordId\"\\s*:\\s*(\\d+)").find(reply)?.groupValues?.get(1)?.toInt()
                ?: error("no record ID in $reply"))
            val ref = RecordRef(car.id, RecordKind.FUEL, id)
            val snapshot = editor.loadEditableRecord(car, ref)
            check(snapshot.draft.stationName == imported) { "LubeLogger did not keep the long value: ${snapshot.draft.stationName}" }
            editor.updateRecord(car, car.id, snapshot, snapshot.draft.copy(cost = "31.00"))
            val saved = history(car).single { it.kind == RecordKind.FUEL && it.id == id }
            check(saved.fuelStation == imported && saved.cost.toDouble() == 31.0) { "saved: $saved" }
            // A new or changed over-limit value is still refused before anything is sent.
            val fresh = editor.loadEditableRecord(car, ref)
            val refused = runCatching { editor.updateRecord(car, car.id, fresh, fresh.draft.copy(stationName = imported + "x")) }
            check(refused.exceptionOrNull() is IllegalArgumentException) { "expected refusal, got $refused" }
            check(history(car).single { it.kind == RecordKind.FUEL && it.id == id }.fuelStation == imported)
        }

        // The fixture's keys are created before any login exists, so LubeLogger reports each key's own name
        // as its user and no same-user pair can exist here; only the two rejections are checked.
        case("A rejected key is named: an Editor key LubeLogger refuses vs. a wrong Manager key") {
            val editorRejected = runCatching {
                LubeLoggerManagerApi(base, managerKey).verifySameAccount("synthetic-revoked-editor-key")
            }.exceptionOrNull()
            check(editorRejected is LubeLoggerManagerApi.ManagerException &&
                editorRejected.message.orEmpty().contains("Editor key")) { "editor rejection: $editorRejected" }
            val managerRejected = runCatching {
                LubeLoggerManagerApi(base, "synthetic-wrong-manager-key").verifySameAccount(editorKey)
            }.exceptionOrNull()
            check(managerRejected is LubeLoggerManagerApi.ManagerException &&
                managerRejected.message.orEmpty().contains("Manager key") &&
                !managerRejected.message.orEmpty().contains("Editor key")) { "manager rejection: $managerRejected" }
        }

        case("Hour-meter vehicle: Repair add round-trips the reading") {
            val id = editor.addRecord(tractor, tractor.id,
                EditorDraft(RecordKind.REPAIR, "2026-10-14", "350", "Hydraulic hose", "80.00", "", ""))
            val saved = history(tractor).single { it.kind == RecordKind.REPAIR && it.id == id }
            check(saved.odometer == 350L) { "reading: ${saved.odometer}" }
        }

        case("EV charging write is blocked before anything is sent") {
            val before = history(ev).size
            val failure = runCatching {
                editor.addRecord(ev, ev.id, EditorDraft(RecordKind.FUEL, "2026-10-14", "500", "", "10.00", "", "",
                    gallons = "30"))
            }.exceptionOrNull()
            check(failure is LubeLoggerEditorApi.EditorApiException) { "got $failure" }
            check(history(ev).size == before)
        }

        case("A Viewer key cannot create a record, and nothing is saved") {
            val before = history(car).size
            val failure = runCatching {
                LubeLoggerEditorApi(base, viewerKey).addRecord(car, car.id,
                    EditorDraft(RecordKind.UPGRADE, "2026-10-15", "1400", "Roof rack", "120.00", "", ""))
            }.exceptionOrNull()
            check(failure != null) { "viewer key write succeeded" }
            check(failure !is LubeLoggerEditorApi.WriteUncertainException) { "refusal reported as uncertain: $failure" }
            check(history(car).size == before)
        }

        case("An Editor key cannot delete; the record stays") {
            val displayed = history(car).single { it.kind == RecordKind.SERVICE && it.id == serviceId }
            val notManager = LubeLoggerManagerApi(base, editorKey)
            val failure = runCatching { notManager.delete(notManager.prepare(car, displayed)) }.exceptionOrNull()
            check(failure is LubeLoggerManagerApi.ManagerException) { "expected definite refusal, got $failure" }
            check(history(car).any { it.kind == RecordKind.SERVICE && it.id == serviceId })
        }

        case("A Fuel record without a station reads back as none: LubeLogger writes the custom-field list and every value member") {
            // Through LubeLink: no station fields are sent, LubeLogger answers [], and the strict read-back confirms it.
            val noStation = editor.addRecord(car, car.id, fuel.copy(date = "2026-10-14", odometer = "1350",
                stationName = "", stationAddress = ""))
            check(history(car).single { it.kind == RecordKind.FUEL && it.id == noStation }.fuelStation == "")
            // Past LubeLink's rules: a station entry sent with no value member. LubeLogger stores and returns it with
            // "value": null, so History and Edit read it as no station rather than refusing the record.
            val raw = org.json.JSONObject(rawPost(base, editorKey, "/api/vehicle/gasrecords/add?vehicleId=${car.id.value}",
                "{\"Date\":\"2026-10-15\",\"Odometer\":\"1400\",\"Cost\":10,\"Notes\":\"\",\"Tags\":\"\"," +
                    "\"FuelConsumed\":5,\"IsFillToFull\":\"true\",\"MissedFuelUp\":\"false\"," +
                    "\"ExtraFields\":[{\"name\":\"LubeLink Gas Station\",\"isRequired\":false,\"fieldType\":0}]}"))
            val rawId = RecordId(raw.optJSONObject("additionalData")!!.optInt("recordId", -1))
            check(rawId.isValid) { "raw add: $raw" }
            check(history(car).single { it.kind == RecordKind.FUEL && it.id == rawId }.fuelStation == "")
            check(editor.loadEditableRecord(car, RecordRef(car.id, RecordKind.FUEL, rawId)).draft.stationName == "")
        }

        case("Manager delete removes the record and confirms its absence") {
            val displayed = history(car).single { it.kind == RecordKind.FUEL && it.id == fuelId }
            manager.delete(manager.prepare(car, displayed))
            check(history(car).none { it.kind == RecordKind.FUEL && it.id == fuelId })
        }

        println("Passed $passed real-server scenarios against disposable LubeLogger")
    }
}

/** A plain LubeLogger API POST, bypassing LubeLink's own rules; synthetic fixture server only. */
private fun rawPost(base: String, key: String, path: String, body: String): String {
    val connection = java.net.URL(base + path).openConnection() as javax.net.ssl.HttpsURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.setRequestProperty("x-api-key", key)
    connection.setRequestProperty("Content-Type", "application/json")
    connection.outputStream.use { it.write(body.toByteArray()) }
    check(connection.responseCode in 200..299) { "raw POST failed: HTTP ${connection.responseCode}" }
    return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
}
