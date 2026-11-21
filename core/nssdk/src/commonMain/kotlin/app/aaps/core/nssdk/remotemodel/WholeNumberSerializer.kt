package app.aaps.core.nssdk.remotemodel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * A [Long] that also accepts an integral decimal, the way Gson did.
 *
 * `"duration": 30.0` and `"duration": "30.0"` become 30. `30.5` is refused: Gson throws on a
 * non-integral value rather than rounding. A plain integer and a numeric string still decode, and
 * the value is written back as a plain integer, so an upload does not change shape.
 *
 * This is not [RoundedIntSerializer]. That one rounds `10.5` to 11. Here a fraction is an error,
 * and the document that carries it is rejected.
 *
 * The check uses the IEEE value, which is also what Gson does, so a decimal above 2^53 can round
 * before the fraction is tested. Treatment integers (durations, pump ids) sit far below that.
 * A quoted number goes through `toDoubleOrNull`. That parser is the same on the JVM and on Native:
 * it trims spaces and accepts a leading `+`, a trailing `d` or `f`, and a hexadecimal float, as
 * `Double.parseDouble` does. A fraction is still refused.
 */
internal object WholeLongSerializer : KSerializer<Long> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.aaps.core.nssdk.WholeLong", PrimitiveKind.LONG)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder !is JsonDecoder) return decoder.decodeLong()
        return integralLong(decoder.decodeJsonElement())
    }

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)
}

/** Same rule as [WholeLongSerializer], for an [Int]. A value outside the Int range is refused. */
internal object WholeIntSerializer : KSerializer<Int> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("app.aaps.core.nssdk.WholeInt", PrimitiveKind.INT)

    override fun deserialize(decoder: Decoder): Int {
        if (decoder !is JsonDecoder) return decoder.decodeInt()
        val value = integralLong(decoder.decodeJsonElement())
        if (value !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
            throw SerializationException("Expected an integral number in Int range, found $value")
        return value.toInt()
    }

    override fun serialize(encoder: Encoder, value: Int) = encoder.encodeInt(value)
}

private fun integralLong(element: JsonElement): Long {
    val primitive = element as? JsonPrimitive
        ?: throw SerializationException("Expected an integral number, found $element")
    primitive.content.toLongOrNull()?.let { return it }
    val asDouble = primitive.content.toDoubleOrNull()
    if (asDouble != null && asDouble.isFinite()) {
        val asLong = asDouble.toLong()
        if (asLong.toDouble() == asDouble) return asLong
    }
    throw SerializationException("Expected an integral number, found $element")
}
