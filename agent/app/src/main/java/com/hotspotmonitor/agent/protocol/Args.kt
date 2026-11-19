package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.floor

/*
 * Typed readers for CmdMsg.args (a JsonObject, because each command has its own shape; see the
 * comments in CommandNames). `opt*` return null when the key is missing, null or of the wrong type;
 * `require*` throw InvalidArgsException, which the executor maps to CmdRejectReason.INVALID_ARGS.
 */

class InvalidArgsException(message: String) : IllegalArgumentException(message)

private fun JsonObject.primitive(key: String): JsonPrimitive? {
    val v = this[key] ?: return null
    if (v is JsonNull) return null
    return v as? JsonPrimitive
}

fun JsonObject.optBoolean(key: String): Boolean? {
    val p = primitive(key) ?: return null
    return if (p.isString) null else p.booleanOrNull
}

fun JsonObject.optLong(key: String): Long? {
    val p = primitive(key) ?: return null
    if (p.isString) return null
    p.longOrNull?.let { return it }
    val d = p.doubleOrNull ?: return null
    return if (d == floor(d) && d >= Long.MIN_VALUE.toDouble() && d <= Long.MAX_VALUE.toDouble()) d.toLong() else null
}

fun JsonObject.optInt(key: String): Int? =
    optLong(key)?.takeIf { it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() }?.toInt()

fun JsonObject.optDouble(key: String): Double? {
    val p = primitive(key) ?: return null
    return if (p.isString) null else p.doubleOrNull
}

fun JsonObject.optString(key: String): String? {
    val p = primitive(key) ?: return null
    return if (p.isString) p.content else null
}

fun JsonObject.requireBoolean(key: String): Boolean =
    optBoolean(key) ?: throw InvalidArgsException("'$key' must be true or false")

fun JsonObject.requireInt(key: String, range: IntRange? = null): Int {
    val v = optInt(key) ?: throw InvalidArgsException("'$key' must be an integer")
    if (range != null && v !in range) throw InvalidArgsException("'$key' must be in ${range.first}..${range.last}")
    return v
}

fun JsonObject.optInt(key: String, range: IntRange): Int? {
    if (!containsKey(key) || this[key] is JsonNull) return null
    return requireInt(key, range)
}

fun JsonObject.requireString(key: String, allowBlank: Boolean = false): String {
    val v = optString(key) ?: throw InvalidArgsException("'$key' must be a string")
    if (!allowBlank && v.isBlank()) throw InvalidArgsException("'$key' must not be empty")
    return v
}
