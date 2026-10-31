package com.kramttocs.lubelink.core

import kotlinx.coroutines.runBlocking

/** Runs the actual, unchanged Editor and Manager write clients over mock localhost HTTPS.
 * Test-only org.json shim decodes synthetic fixtures. No Android runtime is exercised. */
fun main(args: Array<String>) = runBlocking {
    val nullFixture = org.json.JSONObject("{\"a\":null,\"b\":\"null\"}")
    check(nullFixture.optString("a") == "null")
    check(nullFixture.opt("a") != null && nullFixture.isNull("a"))
    check(nullFixture.optString("b") == "null" && !nullFixture.isNull("b"))
    nullFixture.put("a", null)
    check(!nullFixture.has("a"))
    println("PASS JSON fixture: Android null sentinel and explicit-null removal")
    // The Edit baseline saved across process death is a fixed 64 characters, however large the record.
    val bigNotes = "n".repeat(400_000)
    val bigJson = org.json.JSONObject().put("id", 15).put("vehicleId", 1).put("date", "2026-10-12")
        .put("odometer", "1200").put("description", "Inspection").put("cost", "10.00").put("notes", bigNotes)
        .put("tags", "").put("files", org.json.JSONArray()).put("extraFields", org.json.JSONArray())
    val bigRef = RecordRef(VehicleId(1), RecordKind.SERVICE, RecordId(15))
    val bigDraft = EditorDraft(RecordKind.SERVICE, "2026-10-12", "1200", "Inspection", "10.00", bigNotes, "")
    val fingerprint = LubeLoggerEditorApi.baselineFingerprint(LubeLoggerEditorApi.EditableSnapshot(bigRef, bigJson.toString(), bigDraft))
    check(fingerprint.length == 64) { "fingerprint length ${fingerprint.length}" }
    val changed = org.json.JSONObject(bigJson.toString()).put("notes", bigNotes + "!")
    check(LubeLoggerEditorApi.baselineFingerprint(LubeLoggerEditorApi.EditableSnapshot(bigRef, changed.toString(), bigDraft)) != fingerprint)
    println("PASS baseline fingerprint: 64 characters for a 400 KB record; detects a one-character change")
    // LL-06: a value holding the field separator cannot pass for a different split between two fields.
    fun snapshotWith(notes: String, tags: String) = LubeLoggerEditorApi.EditableSnapshot(bigRef,
        org.json.JSONObject(bigJson.toString()).put("notes", notes).put("tags", tags).toString(), bigDraft)
    val pairs = listOf(
        ("a\u0001tags=b" to "c") to ("a" to "b\u0001tags=c"),          // the review's pair
        ("" to "x") to ("x" to ""),                                    // the same text moved between fields
        ("a\u0001" to "") to ("a" to "\u0001"))
    for ((one, two) in pairs) check(LubeLoggerEditorApi.baselineFingerprint(snapshotWith(one.first, one.second)) !=
        LubeLoggerEditorApi.baselineFingerprint(snapshotWith(two.first, two.second))) { "LL-06: $one and $two share a fingerprint" }
    val nullNotes = LubeLoggerEditorApi.EditableSnapshot(bigRef, "{\"id\":15,\"vehicleId\":1,\"date\":\"2026-10-12\",\"odometer\":\"1200\"," +
        "\"description\":\"Inspection\",\"cost\":\"10.00\",\"notes\":null,\"tags\":\"\",\"files\":[],\"extraFields\":[]}", bigDraft)
    check(LubeLoggerEditorApi.baselineFingerprint(nullNotes) != LubeLoggerEditorApi.baselineFingerprint(snapshotWith("", ""))) { "LL-06: null notes match empty notes and tags" }
    println("PASS baseline fingerprint: separator characters in values cannot make two records look alike (LL-06)")
    require(args.size == 1) { "Pass the synthetic temporary PKCS12 keystore path" }
    val vehicle = Vehicle(VehicleId(1), 2015, "Example", "Sedan", "", false)
    val displayed = ServiceRecord(RecordId(15), VehicleId(1), "2026-10-12", "Inspection", 1200, "10.00",
        "", "", emptyList(), RecordKind.SERVICE)
    val draft = EditorDraft(RecordKind.SERVICE, "2026-10-12", "1200", "Inspection", "10.00", "", "")
    var passed = 0
    var failed = 0
    // Every scenario runs and reports; any failure fails the run at the end.
    suspend fun run(mode: String, name: String,
                    block: suspend (SyntheticServer) -> Unit) {
        try {
            SyntheticServer(args[0], mode).use { server -> block(server) }
            passed++
            println("PASS production clients: $name")
        } catch (e: Throwable) {
            failed++
            println("FAIL production clients: $name: ${e.message}")
        }
    }
    run("POST_DROP", "POST committed, reply lost: once, uncertain") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val failure = runCatching { api.addRecord(vehicle, vehicle.id, draft) }.exceptionOrNull()
        check(failure is LubeLoggerEditorApi.WriteUncertainException) {
            "Expected uncertain POST; got ${failure?.javaClass?.name ?: "success"}: ${failure?.message}"
        }
        check(server.posts() == 1) { "POST was replayed ${server.posts()} times" }
    }
    run("PUT_DROP", "PUT committed, reply lost: once, uncertain") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val snapshot = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.SERVICE, RecordId(15)))
        val failure = runCatching {
            api.updateRecord(vehicle, vehicle.id, snapshot, snapshot.draft.copy(description = "Inspected"))
        }.exceptionOrNull()
        check(failure is LubeLoggerEditorApi.WriteUncertainException) {
            "Expected uncertain PUT; got ${failure?.javaClass?.name ?: "success"}: ${failure?.message}"
        }
        check(server.puts() == 1) { "PUT was replayed ${server.puts()} times" }
    }
    run("FUEL_POST_SUCCESS", "Read-only client parses GasRecord field definitions and history station values") { server ->
        val api = LubeLoggerApi(server.baseUrl, "synthetic-editor")
        check(api.checkGasStationFields().readiness == FieldReadiness.READY)
        val fuel = api.loadHistory(vehicle.id, includeFuel = true).records.filter { it.kind == RecordKind.FUEL }
        check(fuel.size == 1)
        check(fuel.single().fuelStation == "New Station")
        check(fuel.single().fuelStationAddress == "123 Test Rd")
    }
        // Positive creates: the actual LubeLink client must read back the server record.
    run("POST_SUCCESS", "Service create: id and persisted values round trip") { server ->
        val id = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor").addRecord(vehicle, vehicle.id, draft)
        check(id == RecordId(15) && server.posts() == 1)
        check(server.lastPostBody().contains("\"Description\":\"Inspection\""))
    }
    run("POST_MISMATCH", "Create readback mismatch is uncertain") { server ->
        val failure = runCatching { LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
            .addRecord(vehicle, vehicle.id, draft) }.exceptionOrNull()
        check(failure is LubeLoggerEditorApi.WriteUncertainException)
        check(server.posts() == 1)
    }
    run("POST_FRACTIONAL_ID", "A create reply whose record ID is not a whole number is uncertain, never record 15") { server ->
        val failure = runCatching { LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
            .addRecord(vehicle, vehicle.id, draft) }
        check(failure.exceptionOrNull() is LubeLoggerEditorApi.WriteUncertainException) {
            "Expected uncertain; got ${failure.getOrNull() ?: failure.exceptionOrNull()}"
        }
        check(server.posts() == 1)
    }
    run("FUEL_POST_SUCCESS", "Fuel create: station fields and quantities round trip") { server ->
        val fuel = EditorDraft(RecordKind.FUEL, "2026-10-12", "1200", "", "45.67", "", "",
            gallons = "12.5", stationName = "New Station", stationAddress = "123 Test Rd")
        val id = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
            .addRecord(vehicle, vehicle.id, fuel)
        check(id == RecordId(15) && server.posts() == 1)
        check(server.lastPostBody().contains("LubeLink Gas Station"))
        check(server.lastPostBody().contains("New Station"))
        check(server.lastPostBody().contains("LubeLink Station Address"))
    }
    for (status in listOf(408, 429)) {
        run("POST_$status", "POST $status after write: uncertain") { server ->
            val failure = runCatching { LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
                .addRecord(vehicle, vehicle.id, draft) }.exceptionOrNull()
            check(failure is LubeLoggerEditorApi.WriteUncertainException) {
                "POST $status was not uncertain: $failure"
            }
            check(server.posts() == 1)
        }
        run("PUT_$status", "PUT $status after write: uncertain") { server ->
            val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
            val original = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.SERVICE, RecordId(15)))
            val failure = runCatching { api.updateRecord(vehicle, vehicle.id, original,
                original.draft.copy(description = "Inspected")) }.exceptionOrNull()
            check(failure is LubeLoggerEditorApi.WriteUncertainException) {
                "PUT $status was not uncertain: $failure"
            }
            check(server.puts() == 1)
        }
    }
    run("PUT_SUCCESS", "Service edit round trip preserves files and unrelated fields") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val original = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.SERVICE, RecordId(15)))
        check(api.updateRecord(vehicle, vehicle.id, original, original.draft.copy(description = "Inspected")) == EditOutcome.SAVED)
        check(server.puts() == 1)
        check(server.lastPutBody().contains("synthetic.pdf"))
        check(server.lastPutBody().contains("Unrelated Field"))
    }
    run("FUEL_PUT_SUCCESS", "Fuel edit round trip keeps unrelated extra fields and attachments") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val original = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.FUEL, RecordId(15)))
        api.updateRecord(vehicle, vehicle.id, original,
            original.draft.copy(stationName = "New Station"))
        check(server.puts() == 1)
        check(server.lastPutBody().contains("Unrelated Field"))
        check(server.lastPutBody().contains("synthetic.pdf"))
        check(server.lastPutBody().contains("New Station"))
    }
    run("PUT_LITERAL_NULL", "Literal null notes survive an unrelated cost edit") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val original = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.SERVICE, RecordId(15)))
        check(original.draft.notes == "null") { "User notes unexpectedly coerced: '${original.draft.notes}'" }
        api.updateRecord(vehicle, vehicle.id, original, original.draft.copy(cost = "11.00"))
        check(server.puts() == 1)
        check(server.lastPutBody().contains("\"notes\":\"null\""))
    }
    run("FUEL_NULL_EXTRA", "JSON null GasRecord field never appears as text null in History/Edit") { server ->
        val editor = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val original = editor.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.FUEL, RecordId(15)))
        check(original.draft.stationName == "") { "JSON-null station shown as ${original.draft.stationName}" }
        val fuel = LubeLoggerApi(server.baseUrl, "synthetic-editor")
            .loadHistory(vehicle.id, includeFuel = true).records.single { it.kind == RecordKind.FUEL }
        check(fuel.fuelStation == "") { "JSON-null station shown in History: ${fuel.fuelStation}" }
    }
    for (kind in RecordKind.entries) {
        run("NOOP", "Unchanged $kind edit does not send PUT") { server ->
            val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
            val original = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, kind, RecordId(15)))
            val outcome = api.updateRecord(vehicle, vehicle.id, original, original.draft)
            check(outcome == EditOutcome.NOTHING_TO_SAVE) { "Unchanged edit reported $outcome" }
            check(server.puts() == 0)
        }
    }
    run("NOOP", "Formatting-only cost does not send PUT") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val original = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.SERVICE, RecordId(15)))
        val outcome = api.updateRecord(vehicle, vehicle.id, original, original.draft.copy(cost = "10.0"))
        check(outcome == EditOutcome.NOTHING_TO_SAVE) { "Formatting-only edit reported $outcome" }
        check(server.puts() == 0)
    }
        suspend fun deleteCase(mode: String, outcome: String) {
        run(mode, "DELETE ${mode}: $outcome") { server ->
            val api = LubeLoggerManagerApi(server.baseUrl, "synthetic-manager")
            val prepared = api.prepare(vehicle, displayed)
            val failure = runCatching { api.delete(prepared) }.exceptionOrNull()
            when (outcome) {
                "success" -> check(failure == null) { "Expected delete success; got $failure" }
                "definite" -> check(failure is LubeLoggerManagerApi.ManagerException) {
                    "Expected definite delete rejection; got $failure"
                }
                "uncertain" -> check(failure is LubeLoggerEditorApi.WriteUncertainException) {
                    "Expected uncertain delete; got $failure"
                }
            }
            check(server.deletes() == 1) { "Unexpected delete count ${server.deletes()}" }
        }
    }
    // A DELETE whose reply is lost is sent once and is uncertain, never resent by the transport.
    deleteCase("DELETE_DROP", "uncertain")
    deleteCase("DELETE_400_ABSENT", "success")
    deleteCase("DELETE_404_ABSENT", "success")
    deleteCase("DELETE_400_PRESENT", "definite")
    deleteCase("DELETE_400_VERIFY_FAIL", "uncertain")
    deleteCase("DELETE_204_ABSENT", "success")
    deleteCase("DELETE_204_PRESENT", "uncertain")
    deleteCase("DELETE_403_PRESENT", "definite")
    // The record is still in LubeLogger and the check after DELETE answers malformed or foreign data. Only a
    // well-formed list without the record means it is gone, so each of these is uncertain, never "deleted".
    for (mode in listOf("DELETE_204_VERIFY_EMPTY_OBJECT", "DELETE_204_VERIFY_NUMBER", "DELETE_204_VERIFY_FOREIGN",
            "DELETE_204_VERIFY_NO_VEHICLE", "DELETE_204_VERIFY_DUPLICATE", "DELETE_404_VERIFY_EMPTY_OBJECT",
            "DELETE_REJECTED_VERIFY_EMPTY_OBJECT"))
        deleteCase(mode, "uncertain")
    // Related cases: a duplicated Add read-back, and Add or Edit read-backs missing a field, are uncertain too.
    for (mode in listOf("POST_READBACK_DUPLICATE", "POST_READBACK_NO_NOTES")) run(mode, "$mode: uncertain, POST once") { server ->
        val failure = runCatching { LubeLoggerEditorApi(server.baseUrl, "synthetic-editor").addRecord(vehicle, vehicle.id, draft) }
            .exceptionOrNull()
        check(failure is LubeLoggerEditorApi.WriteUncertainException) { "Expected uncertain create; got $failure" }
        check(server.posts() == 1)
    }
    run("PUT_READBACK_NO_NOTES", "PUT_READBACK_NO_NOTES: uncertain, PUT once") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val snapshot = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.SERVICE, RecordId(15)))
        val failure = runCatching { api.updateRecord(vehicle, vehicle.id, snapshot, snapshot.draft.copy(description = "Inspected")) }
            .exceptionOrNull()
        check(failure is LubeLoggerEditorApi.WriteUncertainException) { "Expected uncertain edit; got $failure" }
        check(server.puts() == 1)
    }
    // A read-back whose value prints the same as what was sent but has another JSON type (Notes "123" read
    // back as the number 123, Tags "true" as the boolean true, a station "123" as a number) is uncertain, never a match.
    for ((mode, sent) in listOf("POST_READBACK_NUMBER_NOTES" to draft.copy(notes = "123"),
            "POST_READBACK_BOOLEAN_TAGS" to draft.copy(tags = "true")))
        run(mode, "$mode: uncertain, POST once") { server ->
            val failure = runCatching { LubeLoggerEditorApi(server.baseUrl, "synthetic-editor").addRecord(vehicle, vehicle.id, sent) }
                .exceptionOrNull()
            check(failure is LubeLoggerEditorApi.WriteUncertainException) { "Expected uncertain create; got ${failure ?: "success"}" }
            check(server.posts() == 1)
        }
    run("PUT_READBACK_NUMBER_NOTES", "PUT_READBACK_NUMBER_NOTES: uncertain, PUT once") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val snapshot = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.SERVICE, RecordId(15)))
        val failure = runCatching { api.updateRecord(vehicle, vehicle.id, snapshot, snapshot.draft.copy(notes = "123")) }
            .exceptionOrNull()
        check(failure is LubeLoggerEditorApi.WriteUncertainException) { "Expected uncertain edit; got ${failure ?: "success"}" }
        check(server.puts() == 1)
    }
    run("FUEL_PUT_READBACK_NUMBER_STATION", "FUEL_PUT_READBACK_NUMBER_STATION: uncertain, PUT once") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val snapshot = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.FUEL, RecordId(15)))
        val failure = runCatching { api.updateRecord(vehicle, vehicle.id, snapshot, snapshot.draft.copy(stationName = "123")) }
            .exceptionOrNull()
        check(failure is LubeLoggerEditorApi.WriteUncertainException) { "Expected uncertain edit; got ${failure ?: "success"}" }
        check(server.puts() == 1)
    }
    // A Fuel Add with no station, whose read-back has no usable custom-field list (an object, null, a number,
    // none at all) or a station entry without its value member, is uncertain: never "no station" and a success. A
    // valid empty list, and present JSON-null values, are a real "no station" and succeed.
    val blankStation = EditorDraft(RecordKind.FUEL, "2026-10-12", "1200", "", "45.67", "", "", gallons = "12.5")
    for (mode in listOf("FUEL_POST_EXTRAS_OBJECT", "FUEL_POST_EXTRAS_NULL", "FUEL_POST_EXTRAS_NUMBER",
            "FUEL_POST_EXTRAS_ABSENT", "FUEL_POST_EXTRAS_NO_VALUE"))
        run(mode, "$mode: blank station, uncertain, POST once") { server ->
            val failure = runCatching {
                LubeLoggerEditorApi(server.baseUrl, "synthetic-editor").addRecord(vehicle, vehicle.id, blankStation)
            }.exceptionOrNull()
            check(failure is LubeLoggerEditorApi.WriteUncertainException) { "Expected uncertain create; got ${failure ?: "success"}" }
            check(server.posts() == 1)
        }
    for (mode in listOf("FUEL_POST_EXTRAS_EMPTY", "FUEL_POST_EXTRAS_NULL_VALUE"))
        run(mode, "$mode: blank station confirmed") { server ->
            val id = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor").addRecord(vehicle, vehicle.id, blankStation)
            check(id == RecordId(15) && server.posts() == 1)
        }
    run("FUEL_EDIT_NO_VALUE", "FUEL_EDIT_NO_VALUE: a station entry without its value member blocks Edit, never a blank") { server ->
        val failure = runCatching {
            LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
                .loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.FUEL, RecordId(15)))
        }
        check(failure.isFailure) { "Edit opened with station \"${failure.getOrNull()?.draft?.stationName}\"" }
    }
    run("FUEL_PUT_READBACK_NO_VALUE", "FUEL_PUT_READBACK_NO_VALUE: uncertain, PUT once") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val snapshot = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.FUEL, RecordId(15)))
        val failure = runCatching { api.updateRecord(vehicle, vehicle.id, snapshot, snapshot.draft.copy(stationName = "New Station")) }
            .exceptionOrNull()
        check(failure is LubeLoggerEditorApi.WriteUncertainException) { "Expected uncertain edit; got ${failure ?: "success"}" }
        check(server.puts() == 1)
    }
    // LL-04: the pre-write check compares the flags that decide what a write means, not only year/make/model. The server
    // says not electric and miles; a Garage loaded earlier says otherwise. Nothing may be sent.
    for ((label, stale) in listOf("electric" to vehicle.copy(isElectric = true), "hours" to vehicle.copy(useHours = true))) {
        run("POST_SUCCESS", "LL-04 Add with a stale $label flag: refused, nothing sent") { server ->
            val failure = runCatching { LubeLoggerEditorApi(server.baseUrl, "synthetic-editor").addRecord(stale, stale.id, draft) }
                .exceptionOrNull()
            check(failure is LubeLoggerEditorApi.EditorApiException) { "Expected a refusal; got ${failure ?: "success"}" }
            check(server.posts() == 0) { "POST sent ${server.posts()} times" }
        }
        run("PUT_SUCCESS", "LL-04 Edit with a stale $label flag: refused, nothing sent") { server ->
            val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
            val snapshot = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.SERVICE, RecordId(15)))
            val failure = runCatching { api.updateRecord(stale, stale.id, snapshot, snapshot.draft.copy(description = "Inspected")) }
                .exceptionOrNull()
            check(failure is LubeLoggerEditorApi.EditorApiException) { "Expected a refusal; got ${failure ?: "success"}" }
            check(server.puts() == 0) { "PUT sent ${server.puts()} times" }
        }
    }
    run("PUT_SUCCESS", "LL-04 control: Edit with matching flags still saves") { server ->
        val api = LubeLoggerEditorApi(server.baseUrl, "synthetic-editor")
        val snapshot = api.loadEditableRecord(vehicle, RecordRef(vehicle.id, RecordKind.SERVICE, RecordId(15)))
        check(api.updateRecord(vehicle, vehicle.id, snapshot, snapshot.draft.copy(description = "Inspected")) == EditOutcome.SAVED)
        check(server.puts() == 1)
    }
    println("Passed $passed synthetic HTTPS scenarios using the production write clients; $failed failed")
    check(failed == 0) { "$failed production-client scenarios failed" }
}
