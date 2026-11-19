package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.serializerOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Builds a JsonObject from Kotlin values, e.g. for AgentEvent.data:
 * `jsonObjectOf("rule" to "internet", "step" to 1, "outcome" to "recovered")`.
 * Supported values: null, JsonElement, String, Number, Boolean, Map, Iterable, Array, enums and any
 * other @Serializable value (encoded with its serializer, so protocol enums use their wire names).
 */
fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject =
    JsonObject(pairs.associate { (k, v) -> k to v.toJsonElement() })

fun Any?.toJsonElement(): JsonElement = when (this) {
    null -> JsonNull
    is JsonElement -> this
    is String -> JsonPrimitive(this)
    is Number -> JsonPrimitive(this)
    is Boolean -> JsonPrimitive(this)
    is Map<*, *> -> JsonObject(entries.associate { (k, v) -> k.toString() to v.toJsonElement() })
    is Iterable<*> -> JsonArray(map { it.toJsonElement() })
    is Array<*> -> JsonArray(map { it.toJsonElement() })
    else -> encodeWithSerializer(this)
}

private fun encodeWithSerializer(value: Any): JsonElement {
    val serializer = runCatching { serializerOrNull(value.javaClass) }.getOrNull()
    if (serializer != null) {
        runCatching { return AgentJson.encodeToJsonElement(serializer, value) }
    }
    return JsonPrimitive(if (value is Enum<*>) value.name.lowercase() else value.toString())
}

/**
 * Deep-merges [patch] over this object with the semantics of settings.ts `mergeSettings`:
 * objects merge recursively, arrays and scalars (including null) replace.
 */
fun JsonObject.deepMerge(patch: JsonObject): JsonObject {
    val out = LinkedHashMap<String, JsonElement>(this)
    for ((k, v) in patch) {
        val base = out[k]
        out[k] = if (base is JsonObject && v is JsonObject) base.deepMerge(v) else v
    }
    return JsonObject(out)
}

/** Recursively removes object keys whose value is JSON null. */
fun JsonElement.withoutNulls(): JsonElement = when (this) {
    is JsonObject -> JsonObject(filterValues { it !is JsonNull }.mapValues { it.value.withoutNulls() })
    is JsonArray -> JsonArray(map { it.withoutNulls() })
    else -> this
}
