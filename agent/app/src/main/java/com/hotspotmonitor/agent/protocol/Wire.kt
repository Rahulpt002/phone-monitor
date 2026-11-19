package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * The one Json instance used for every protocol message, settings blob, stored spot and profile
 * (docs/protocol.md section 1).
 *
 * `coerceInputValues` is added on top of the documented flags so that an unknown enum value or a
 * stray `null` for a field that has a default falls back to that default instead of failing the
 * whole message (receivers must tolerate newer senders).
 */
val AgentJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    classDiscriminator = "t"
    coerceInputValues = true
}

/** Limits shared by both ends (shared/src/protocol.ts `PROTOCOL`). */
object ProtocolLimits {
    const val VERSION = 1
    const val MAX_BATCH_ITEMS = 200
    const val MAX_MESSAGE_BYTES = 512 * 1024
    const val MAX_OUTPUT_BYTES = 64 * 1024

    /** Live telemetry older than this (Server clock) is treated as backlog. */
    const val LIVE_WINDOW_S = 120
}

/** WebSocket close codes (docs/protocol.md section 7). */
object CloseCodes {
    const val NORMAL = 1000
    const val UNAUTHORIZED = 4001
    const val REPLACED = 4002
    const val PROTOCOL_ERROR = 4003
}

/**
 * Encode/decode helpers for WebSocket frames.
 *
 * Always encode through the sealed base serializer (as these helpers do): encoding a concrete
 * class with its own serializer would leave out the `t` discriminator.
 */
object Wire {
    val json: Json get() = AgentJson

    fun encode(msg: AgentToServer): String = AgentJson.encodeToString(AgentToServer.serializer(), msg)

    fun encodeBuffered(msg: BufferedMsg): String = AgentJson.encodeToString(BufferedMsg.serializer(), msg)

    /** Encodes a Server -> Agent message (used by tests and local tooling). */
    fun encodeServer(msg: ServerToAgent): String = AgentJson.encodeToString(ServerToAgent.serializer(), msg)

    /**
     * Decodes one Server -> Agent frame. Returns null for an unknown `t`, a malformed frame or a
     * frame that does not match its type; the caller logs [typeOf] and carries on.
     */
    fun decodeServerMessage(text: String): ServerToAgent? =
        runCatching { AgentJson.decodeFromString(ServerToAgent.serializer(), text) }.getOrNull()

    /** Decodes one Agent -> Server frame (tests, local debug tooling). Null when not decodable. */
    fun decodeAgentMessage(text: String): AgentToServer? =
        runCatching { AgentJson.decodeFromString(AgentToServer.serializer(), text) }.getOrNull()

    /** The `t` of a frame, or null when the text is not a JSON object with a string `t`. */
    fun typeOf(text: String): String? = runCatching {
        (AgentJson.parseToJsonElement(text).jsonObject["t"] as? JsonPrimitive)?.contentOrNull
    }.getOrNull()

    /** Builds a `batch` frame from already-encoded buffered messages without re-encoding them. */
    fun batchFrame(encodedItems: List<String>): String =
        encodedItems.joinToString(separator = ",", prefix = "{\"t\":\"batch\",\"items\":[", postfix = "]}")

    val EMPTY_OBJECT: JsonObject = JsonObject(emptyMap())
}
