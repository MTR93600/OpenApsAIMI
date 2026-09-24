package app.aaps.plugins.sync.garmin

/**
 * Garmin `/mode` decision: which therapy keyword is accepted, for how long, and whether an FCL
 * temporary target goes with it.
 *
 * Source: `origin/dev_OAPSAIMI` @ `166ddb6db0` (`GarminPlugin.onPostMode`,
 * `LoopHubImpl.postTherapyMode`). The watch query is
 * `/mode?mode=lunch&duration=60`. The NOTE text stored for AIMI `Therapy` is the keyword alone;
 * the minutes live on `TherapyEvent.duration`. Only `fcl` also starts a
 * temporary target of [FCL_TEMP_TARGET_MGDL] mg/dL for [FCL_TEMP_TARGET_DURATION_MIN] minutes.
 * `sport` does not.
 *
 * Pure: no clock, no preferences, no Android. The HTTP endpoint and the persistence write stay in
 * androidMain. Connect IQ has no Apple artifact, so this object is the shared half and there is
 * no ios actual.
 */
internal object GarminTherapyMode {

    /** FCL companion temporary target (mg/dL). Ends the FCL meal basal together with the target. */
    const val FCL_TEMP_TARGET_MGDL: Double = 80.0

    /** FCL companion temporary target length, in minutes. Independent of the mode duration query. */
    const val FCL_TEMP_TARGET_DURATION_MIN: Int = 30

    /** Watch-reported duration is clamped to this inclusive range before storage clamps again. */
    const val REPORTED_DURATION_MAX_MIN: Int = 480

    /**
     * Keywords accepted from Garmin `/mode`. Must stay the set `therapy.kt` can detect from a NOTE.
     * `breakfast` is not accepted: the watch keyword is `bfast`.
     */
    val allowedModes: Set<String> = setOf(
        "bfast", "lunch", "dinner", "highcarb", "fcl", "sport", "stop", "meal", "snack",
    )

    private val defaultDurationMin: Map<String, Int> = mapOf(
        "bfast" to 60,
        "lunch" to 60,
        "dinner" to 60,
        "highcarb" to 90,
        "fcl" to 30,
        "sport" to 120,
        "meal" to 60,
        "snack" to 30,
        "stop" to 1,
    )

    data class TempTarget(val mgdl: Double, val durationMin: Int)

    sealed interface Decision {
        val json: String
    }

    data class Rejected(
        /** Mode after trim and lowercase, or empty when the query was missing. */
        val rawMode: String,
        override val json: String,
    ) : Decision

    data class Accepted(
        val mode: String,
        /**
         * Minutes returned to the watch and passed to `postTherapyMode` before the storage clamp.
         * A requested `0` stays `0` here.
         */
        val reportedDurationMin: Int,
        /** NOTE text. The keyword only, never `"lunch 60"`. */
        val note: String,
        /** Present only for `fcl`. */
        val tempTarget: TempTarget?,
        /** True when the duration query was present and not an integer. The default is used. */
        val durationInvalid: Boolean,
        val durationRaw: String?,
        override val json: String,
    ) : Decision

    fun decide(modeQuery: String?, durationQuery: String?): Decision {
        val rawMode = modeQuery?.trim()?.lowercase().orEmpty()
        if (rawMode.isEmpty() || rawMode !in allowedModes) {
            return Rejected(rawMode, """{"ok":false,"error":"invalid_mode"}""")
        }
        val defaultDuration = defaultDurationMin[rawMode] ?: 60
        val parsed = parseDuration(durationQuery, defaultDuration)
        val reported = parsed.minutes.coerceIn(0, REPORTED_DURATION_MAX_MIN)
        val tempTarget = if (rawMode == "fcl") {
            TempTarget(FCL_TEMP_TARGET_MGDL, FCL_TEMP_TARGET_DURATION_MIN)
        } else {
            null
        }
        return Accepted(
            mode = rawMode,
            reportedDurationMin = reported,
            note = rawMode,
            tempTarget = tempTarget,
            durationInvalid = parsed.invalid,
            durationRaw = durationQuery,
            json = """{"ok":true,"mode":"$rawMode","duration":$reported}""",
        )
    }

    /**
     * Minutes written on `TherapyEvent.duration`.
     *
     * `stop` is at least 1 minute. Every other keyword is clamped to 1..[REPORTED_DURATION_MAX_MIN],
     * so a watch request of `0` still opens a one-minute window.
     */
    fun storedDurationMin(keyword: String, durationMin: Int): Int {
        val normalized = keyword.trim().lowercase()
        return when {
            normalized == "stop" -> durationMin.coerceAtLeast(1)
            else -> durationMin.coerceIn(1, REPORTED_DURATION_MAX_MIN)
        }
    }

    private data class ParsedDuration(val minutes: Int, val invalid: Boolean)

    /** Same fallback as `GarminPlugin.getQueryParameter` for an `Int`: empty keeps the default. */
    private fun parseDuration(raw: String?, defaultMin: Int): ParsedDuration {
        if (raw.isNullOrEmpty()) return ParsedDuration(defaultMin, invalid = false)
        return try {
            ParsedDuration(raw.toInt(), invalid = false)
        } catch (_: NumberFormatException) {
            ParsedDuration(defaultMin, invalid = true)
        }
    }
}
