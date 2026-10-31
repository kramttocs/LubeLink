package com.kramttocs.lubelink.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * The value of the custom field [fieldName] in a record's "extraFields" ("" when the record has none of that name).
 * LubeLogger identifies custom fields by name. JSON null reads as "", distinct from the text "null". The list itself
 * is required: callers read it with [ResponseFields.array], so a missing or non-list "extraFields" is unreadable, never
 * a record without custom fields. Every entry is checked before any is used, whatever its position:
 * each must be an object whose name is text and whose value member is present and text or null (LubeLogger 1.7.4
 * always writes it), and no name may appear twice. An entry in any other form fails the record instead of being
 * skipped or turned into text.
 */
internal fun extraFieldValue(fields: JSONArray, fieldName: String): String =
    extraFieldEntries(fields, "a record's custom fields").firstOrNull { it.first == fieldName }?.second ?: ""

/** Every entry of a custom-field list as (name, value), checked as [extraFieldValue] describes. */
internal fun extraFieldEntries(fields: JSONArray, source: String): List<Pair<String, String>> {
    val read = ResponseFields(source)
    val entries = read.objects(fields).map { field ->
        val name = textOrNull(field, "name", source) ?: textOrNull(field, "Name", source) ?: read.fail("name")
        // A missing value member is unreadable; only a present JSON null means "no value".
        if (!field.has("value") && !field.has("Value")) read.fail("value")
        name to (textOrNull(field, "value", source) ?: textOrNull(field, "Value", source) ?: "")
    }
    entries.groupBy { it.first }.filterValues { it.size > 1 }.keys.firstOrNull()
        ?.let { read.fail("name (\"$it\" appears more than once)") }
    return entries
}

/** Text at [key]; null when absent or JSON null; a value of any other type is unreadable. */
private fun textOrNull(o: JSONObject, key: String, source: String): String? = when {
    !o.has(key) || o.isNull(key) -> null
    else -> o.opt(key) as? String ?: throw UnreadableResponseException(source, key)
}
