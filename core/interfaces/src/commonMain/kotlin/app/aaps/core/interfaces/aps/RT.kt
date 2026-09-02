package app.aaps.core.interfaces.aps

import app.aaps.core.data.datetime.parseIsoToEpochMillisOrNull
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlin.time.Instant

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

    var consoleLog: MutableList<String>? = null,
    var consoleError: MutableList<String>? = null,
    var isHypoRisk: Boolean = false,

    // AI decision auditor
    var aiAuditorEnabled: Boolean = false,
    var aiAuditorVerdict: String? = null, // CONFIRM, SOFTEN, SHIFT_TO_TBR
    var aiAuditorConfidence: Double? = null, // 0.0 to 1.0
    var aiAuditorModulation: String? = null, // modulation applied
    var aiAuditorRiskFlags: String? = null, // comma-separated risk flags

    // Learner state shown on RT
    var learnersInfo: String? = null, // example: "Basal x 1.05, ISF:42, React:0.95x"

    // Phase-space trajectory control for graphs
    var trajectoryEnabled: Boolean = false, // feature flag
    var trajectoryType: String? = null, // OPEN_DIVERGING, CLOSING_CONVERGING, TIGHT_SPIRAL, STABLE_ORBIT
    var trajectoryCurvature: Double? = null, // kappa: 0-1+ (above 0.3 is a tight spiral)
    var trajectoryConvergence: Double? = null, // mg/dL per min (positive means converging)
    var trajectoryCoherence: Double? = null, // -1 to 1 (above 0.6 is a good response)
    var trajectoryEnergy: Double? = null, // insulin units (above 2 is stacking)
    var trajectoryOpenness: Double? = null, // 0-1 (above 0.7 is diverging)
    var trajectoryHealth: Int? = null, // overall health 0-100
    var trajectoryModulationActive: Boolean = false, // true when modulation was applied
    var trajectoryWarningsCount: Int? = null, // warning count
    var trajectoryConvergenceETA: Int? = null, // predicted minutes to a stable orbit
    var trajectoryRelevanceScore: Double? = null, // cosine similarity 0.0-1.0

    // Context module
    var contextEnabled: Boolean = false, // feature flag
    var contextIntentCount: Int = 0, // number of active context intents
    var contextModulation: Double = 1.0, // SMB modulation factor (0.5-1.1)
    @Transient
    var aimiAdaptationStatus: AimiAdaptationStatus? = null
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

    object TimestampToIsoSerializer : KSerializer<Long> {

        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("LongToIso", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: Long) {
            encoder.encodeString(toISOString(value))
        }

        override fun deserialize(decoder: Decoder): Long {
            return fromISODateString(decoder.decodeString())
        }

        /**
         * Was joda's `ISODateTimeFormat.dateTimeParser()`. The replacement accepts the same shapes -
         * this parses device status written by other Nightscout uploaders, not only what AAPS wrote,
         * so `+0200` without a colon and offset-less values have to keep working.
         *
         * It still throws on input it cannot read, as joda did. A silent epoch-0 timestamp inside an
         * APS result would be worse than a loud failure.
         */
        fun fromISODateString(isoDateString: String): Long =
            parseIsoToEpochMillisOrNull(isoDateString)
                ?: throw IllegalArgumentException("Invalid format: $isoDateString")

        /**
         * `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC, always with three fractional digits.
         *
         * Built explicitly rather than with `Instant.toString()`, which drops trailing zeros and
         * omits the fraction altogether on a whole second.
         */
        private val isoOut = LocalDateTime.Format {
            year(); char('-'); monthNumber(); char('-'); day()
            char('T')
            hour(); char(':'); minute(); char(':'); second()
            char('.'); secondFraction(3)
        }

        /**
         * Locale independent on purpose. A formatter that resolves its calendar from the locale
         * breaks the wire format: on a phone set to Thai it selects the Buddhist calendar and writes
         * the year as 2569 instead of 2026 - a timestamp 543 years in the future.
         * `RtIsoStringParityTest` pins the output.
         */
        fun toISOString(date: Long): String =
            isoOut.format(Instant.fromEpochMilliseconds(date).toLocalDateTime(TimeZone.UTC)) + "Z"
    }

    companion object {

        private val serializer = Json { ignoreUnknownKeys = true }
        fun deserialize(jsonString: String) = serializer.decodeFromString(serializer(), jsonString)
    }
}