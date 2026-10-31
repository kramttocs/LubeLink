package org.json

/** Test-only, minimal JSON shim for JVM tests without Android's org.json dependency.
 * Only supports the methods actually exercised by the production API clients.
 * Do not ship this file in the Android app or treat it as a JSON conformance test. */
/** Android org.json returns this sentinel for an explicit JSON null. */
object JsonNull { override fun toString(): String = "null" }

class JSONObject private constructor(private val data: LinkedHashMap<String, Any?>) {
    constructor(): this(linkedMapOf())
    constructor(text: String): this((MiniParser(text).parse() as? JSONObject
        ?: error("Expected JSON object")).data)

    fun put(key: String, value: Any?): JSONObject {
        if (value == null) data.remove(key) else data[key] = value
        return this
    }
    fun has(key: String): Boolean = data.containsKey(key)
    fun remove(key: String): Any? = data.remove(key)
    fun length(): Int = data.size
    fun opt(key: String): Any? = data[key]
    fun isNull(key: String): Boolean = data[key] == null || data[key] === JsonNull
    fun optString(key: String, fallback: String = ""): String = data[key]?.toString() ?: fallback
    fun optInt(key: String, fallback: Int = 0): Int = data[key]?.toString()?.toDoubleOrNull()?.toInt() ?: fallback
    fun optBoolean(key: String, fallback: Boolean = false): Boolean = when (data[key]?.toString()?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> fallback
    }
    fun optJSONObject(key: String): JSONObject? = data[key] as? JSONObject
    fun optJSONArray(key: String): JSONArray? = data[key] as? JSONArray
    fun getJSONArray(key: String): JSONArray = optJSONArray(key) ?: error("Not an array: $key")
    override fun toString(): String = data.entries.joinToString(",", "{", "}") { (k,v) -> quote(k) + ":" + json(v) }
    companion object {
        internal fun parsed(map: LinkedHashMap<String, Any?>): JSONObject = JSONObject(map)
    }
}

class JSONArray private constructor(private val data: MutableList<Any?>) {
    constructor(): this(mutableListOf())
    constructor(text: String): this((MiniParser(text).parse() as? JSONArray
        ?: error("Expected JSON array")).data)
    fun put(value: Any?): JSONArray { data.add(value); return this }
    fun length(): Int = data.size
    fun optJSONObject(index: Int): JSONObject? = data.getOrNull(index) as? JSONObject
    fun opt(index: Int): Any? = data.getOrNull(index)
    override fun toString(): String = data.joinToString(",", "[", "]") { json(it) }
    companion object { internal fun parsed(values: MutableList<Any?>): JSONArray = JSONArray(values) }
}

private fun quote(s: String): String = buildString {
    append('"')
    for (c in s) when (c) {
        '"' -> append("\\\"")
        '\\' -> append("\\\\")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (c.code < 32) append("\\u%04x".format(c.code)) else append(c)
    }
    append('"')
}
private fun json(value: Any?): String = when (value) {
    null, JsonNull -> "null"
    is String -> quote(value)
    is Number, is Boolean -> value.toString()
    is JSONObject, is JSONArray -> value.toString()
    else -> error("Unsupported synthetic JSON type: ${value.javaClass.name}")
}

private class MiniParser(private val src: String) {
    private var i = 0
    private fun space() { while (i < src.length && src[i].isWhitespace()) i++ }
    private fun take(c: Char) { space(); require(i < src.length && src[i] == c) { "Expected $c at $i" }; i++ }
    fun parse(): Any? { val value = value(); space(); require(i == src.length) { "Trailing JSON" }; return value }
    private fun value(): Any? {
        space(); require(i < src.length) { "Empty JSON" }
        return when (src[i]) {
            '{' -> obj()
            '[' -> arr()
            '"' -> string()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", JsonNull)
            else -> number()
        }
    }
    private fun literal(token: String, output: Any?): Any? {
        require(src.startsWith(token, i)) { "Bad literal at $i" }; i += token.length; return output
    }
    private fun obj(): JSONObject {
        take('{'); val m = linkedMapOf<String, Any?>(); space()
        if (src.getOrNull(i) == '}') { i++; return JSONObject.parsed(m) }
        while (true) {
            space(); val k = string(); take(':'); m[k] = value(); space()
            if (src.getOrNull(i) == '}') { i++; return JSONObject.parsed(m) }
            take(',')
        }
    }
    private fun arr(): JSONArray {
        take('['); val a = mutableListOf<Any?>(); space()
        if (src.getOrNull(i) == ']') { i++; return JSONArray.parsed(a) }
        while (true) {
            a.add(value()); space()
            if (src.getOrNull(i) == ']') { i++; return JSONArray.parsed(a) }
            take(',')
        }
    }
    private fun string(): String {
        take('"'); val b = StringBuilder()
        while (true) {
            require(i < src.length) { "Unclosed JSON string" }
            when (val c = src[i++]) {
                '"' -> return b.toString()
                '\\' -> {
                    require(i < src.length)
                    b.append(when (val next = src[i++]) {
                        '"', '\\', '/' -> next
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'b' -> '\b'
                        'f' -> '\u000C'
                        'u' -> { val hex = src.substring(i, i + 4); i += 4; hex.toInt(16).toChar() }
                        else -> error("Bad JSON escape $next")
                    })
                }
                else -> b.append(c)
            }
        }
    }
    private fun number(): Number {
        val start = i
        while (i < src.length && src[i] in "-+0123456789.eE") i++
        require(i > start) { "Bad value at $i" }
        val literal = src.substring(start, i)
        return literal.toLongOrNull() ?: literal.toDouble()
    }
}
