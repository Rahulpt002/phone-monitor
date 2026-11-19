package com.hotspotmonitor.agent.protocol

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToLong

/**
 * Integer fields that come from the Server (settings, rates, versions) are plain JS `number`s on
 * the other side, so a value like `30.0` or `12.5` is valid there. A strict Int decoder would reject
 * the whole `hello` because of one such field; these serializers round instead.
 * Applied with `@file:UseSerializers` in Settings.kt and Messages.kt.
 */
object LenientIntSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.hotspotmonitor.agent.protocol.LenientInt", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)

    override fun deserialize(decoder: Decoder): Int {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val v = lenientLong(decoder)
        return v.coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt()
    }
}

object LenientLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("com.hotspotmonitor.agent.protocol.LenientLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder !is JsonDecoder) return decoder.decodeLong()
        return lenientLong(decoder)
    }
}

private fun lenientLong(decoder: JsonDecoder): Long {
    val element = decoder.decodeJsonElement()
    val primitive = element as? JsonPrimitive
        ?: throw SerializationException("Expected a number but found $element")
    primitive.content.toLongOrNull()?.let { return it }
    val d = primitive.content.toDoubleOrNull()
    if (d == null || d.isNaN() || d.isInfinite()) {
        throw SerializationException("Expected a number but found '${primitive.content}'")
    }
    return d.roundToLong()
}
