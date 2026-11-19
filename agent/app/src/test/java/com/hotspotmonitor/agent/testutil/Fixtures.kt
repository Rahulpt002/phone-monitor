package com.hotspotmonitor.agent.testutil

import com.hotspotmonitor.agent.protocol.AgentJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.math.BigDecimal

/** Reads shared/fixtures (path from the `fixturesDir` system property set in app/build.gradle.kts). */
object Fixtures {
    val dir: File by lazy {
        val path = System.getProperty("fixturesDir") ?: error("System property fixturesDir is not set")
        File(path).also { require(it.isDirectory) { "Fixtures directory not found: $it" } }
    }

    fun text(name: String): String = File(dir, name).readText()

    fun element(name: String): JsonElement = AgentJson.parseToJsonElement(text(name))

    fun objects(name: String): List<JsonObject> = element(name).jsonArray.map { it.jsonObject }
}

/**
 * Canonical form for comparing JSON produced by TypeScript and Kotlin: object keys with null values
 * are dropped (explicitNulls = false omits them) and numbers compare by value (41 == 41.0).
 */
fun canonical(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.filterValues { it !is JsonNull }.mapValues { canonical(it.value) })
    is JsonArray -> JsonArray(element.map { canonical(it) })
    is JsonNull -> JsonNull
    is JsonPrimitive -> {
        if (element.isString) {
            element
        } else {
            element.content.toBigDecimalOrNull()
                ?.let { JsonPrimitive(BigDecimal(it.stripTrailingZeros().toPlainString())) }
                ?: element
        }
    }
}
