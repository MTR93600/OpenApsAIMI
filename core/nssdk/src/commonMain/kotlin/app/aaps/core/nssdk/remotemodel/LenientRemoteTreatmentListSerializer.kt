package app.aaps.core.nssdk.remotemodel

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Decodes a treatments page one document at a time.
 *
 * The default list serializer fails the whole array on the first bad element. `NsHttpResponse.body`
 * turns that failure into null without throwing, and the history call still returns the ETag, so
 * the page was skipped and the cursor moved past it. A document that cannot be read is dropped
 * here instead. The others are returned, and one warning carries the count and the first id.
 */
internal class LenientRemoteTreatmentListSerializer(
    private val onWarning: (String) -> Unit
) : KSerializer<List<RemoteTreatment>> {

    private val element = RemoteTreatment.serializer()
    private val list = ListSerializer(element)

    override val descriptor: SerialDescriptor = list.descriptor

    override fun serialize(encoder: Encoder, value: List<RemoteTreatment>) = list.serialize(encoder, value)

    override fun deserialize(decoder: Decoder): List<RemoteTreatment> {
        if (decoder !is JsonDecoder) return list.deserialize(decoder)
        val array = decoder.decodeJsonElement() as? JsonArray
            ?: throw SerializationException("Expected a JSON array of treatments")
        val decoded = ArrayList<RemoteTreatment>(array.size)
        var skipped = 0
        var first: String? = null
        for (index in array.indices) {
            val item = array[index]
            try {
                decoded += decoder.json.decodeFromJsonElement(element, item)
            } catch (e: SerializationException) {
                if (skipped == 0) first = "${identifierOf(item, index)}: ${e.message ?: e::class.simpleName}"
                skipped++
            }
        }
        if (skipped > 0)
            onWarning("Treatments, documents skipped because they could not be read: $skipped. The first: $first")
        return decoded
    }
}

private fun identifierOf(element: JsonElement, index: Int): String {
    val obj = element as? JsonObject ?: return "index $index"
    val identifier = (obj["identifier"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (!identifier.isNullOrEmpty()) return identifier
    val id = (obj["_id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    if (!id.isNullOrEmpty()) return id
    return "index $index"
}
