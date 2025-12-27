package app.aaps.core.interfaces.aps

import android.annotation.SuppressLint
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import org.joda.time.DateTime
import org.joda.time.format.ISODateTimeFormat
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class RT(
    var algorithm: APSResult.Algorithm = APSResult.Algorithm.UNKNOWN,
    var runningDynamicIsf: Boolean,
    @Serializable(with = TimestampToIsoSerializer::class)
    var timestamp: Long? = null,
    val temp: String = "absolute",
    var bg: Double? = null,
    var tick: String? = null,
    var eventualBG: Double? = null,
    var targetBG: Double? = null,
    var snoozeBG: Double? = null, // AMA only
    var insulinReq: Double? = null,
    var carbsReq: Int? = null,
    var carbsReqWithin: Int? = null,
    var units: Double? = null, // micro bolus
    @Serializable(with = TimestampToIsoSerializer::class)
    var deliverAt: Long? = null, // The time at which the micro bolus should be delivered
    var sensitivityRatio: Double? = null, // autosens ratio (fraction of normal basal)
    @Serializable(with = StringBuilderSerializer::class)
    var reason: StringBuilder = StringBuilder(),
    var duration: Int? = null,
    var rate: Double? = null,
    var predBGs: Predictions? = null,
    var COB: Double? = null,
    var IOB: Double? = null,
    var variable_sens: Double? = null,
    var isfMgdlForCarbs: Double? = null, // used to pass to AAPS client
    @Serializable(with = StringBuilderSerializer::class)
    var aimilog: StringBuilder = StringBuilder(),

    @Serializable(with = ConsoleLogSerializer::class)
    var consoleLog: MutableList<String>? = null,
    var consoleError: MutableList<String>? = null,
    var isHypoRisk: Boolean = false,
    
    // 🧠 AI Decision Auditor fields
    var aiAuditorEnabled: Boolean = false,
    var aiAuditorVerdict: String? = null,       // CONFIRM, SOFTEN, SHIFT_TO_TBR
    var aiAuditorConfidence: Double? = null,    // 0.0-1.0
    var aiAuditorModulation: String? = null,    // Description of modulation applied
    var aiAuditorRiskFlags: String? = null      // Comma-separated risk flags
) {

    fun serialize() = Json.encodeToString(serializer(), this)

    object StringBuilderSerializer : KSerializer<StringBuilder> {

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("StringBuilder", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: StringBuilder) {
            encoder.encodeString(value.toString())
        }

        override fun deserialize(decoder: Decoder): StringBuilder {
            return StringBuilder().append(decoder.decodeString())
        }
    }

    /**
     * 🛡️ Custom serializer for consoleLog that sanitizes decorative characters
     * 
     * Purpose: Keep visual logs with emojis for display, but serialize clean ASCII-only JSON
     * 
     * Removes:
     * - Emojis (📊 🍱 ⚠️ etc.)
     * - Box drawing characters (│ └ etc.)  
     * - Unicode arrows (→ × etc.)
     * - Control characters (\0 \n \t etc.)
     * 
     * Preserves:
     * - ASCII printable characters (0x20-0x7E)
     * - Essential content (numbers, letters, punctuation)
     */
    object ConsoleLogSerializer : KSerializer<MutableList<String>?> {
        
        override val descriptor: SerialDescriptor = 
            kotlinx.serialization.descriptors.listSerialDescriptor<String>()
        
        override fun serialize(encoder: Encoder, value: MutableList<String>?) {
            if (value == null) {
                encoder.encodeNull()
                return
            }
            
            // Sanitize each log entry before serialization
            val sanitized = value.map { entry ->
                entry
                    // Remove all non-ASCII characters (emojis, unicode, etc.)
                    .replace(Regex("[^\\x20-\\x7E]"), "")
                    // Collapse multiple spaces into one
                    .replace(Regex("\\s+"), " ")
                    // Trim leading/trailing spaces
                    .trim()
            }.filter { it.isNotEmpty() }  // Remove empty entries
            
            // Encode as list
            val compositeEncoder = encoder.beginCollection(descriptor, sanitized.size)
            sanitized.forEachIndexed { index, item ->
                compositeEncoder.encodeStringElement(descriptor, index, item)
            }
            compositeEncoder.endStructure(descriptor)
        }
        
        override fun deserialize(decoder: Decoder): MutableList<String>? {
            // Simple deserialization: decode as list normally
            val compositeDecoder = decoder.beginStructure(descriptor)
            val list = mutableListOf<String>()
            
            while (true) {
                val index = compositeDecoder.decodeElementIndex(descriptor)
                if (index == kotlinx.serialization.encoding.CompositeDecoder.DECODE_DONE) break
                list.add(compositeDecoder.decodeStringElement(descriptor, index))
            }
            compositeDecoder.endStructure(descriptor)
            return list
        }
    }

    object TimestampToIsoSerializer : KSerializer<Long> {

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LongToIso", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Long) {
            encoder.encodeString(toISOString(value))
        }

        override fun deserialize(decoder: Decoder): Long {
            return fromISODateString(decoder.decodeString())
        }

        fun fromISODateString(isoDateString: String): Long {
            val parser = ISODateTimeFormat.dateTimeParser()
            val dateTime = DateTime.parse(isoDateString, parser)
            return dateTime.toDate().time
        }

        fun toISOString(date: Long): String {
            @Suppress("SpellCheckingInspection", "LocalVariableName")
            val FORMAT_DATE_ISO_OUT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
            val f: DateFormat = SimpleDateFormat(FORMAT_DATE_ISO_OUT, Locale.getDefault())
            f.timeZone = TimeZone.getTimeZone("UTC")
            return f.format(date)
        }
    }

    companion object {

        private val serializer = Json { ignoreUnknownKeys = true }
        fun deserialize(jsonString: String) = serializer.decodeFromString(serializer(), jsonString)
    }
}