package app.aaps.plugins.aps.openAPSAIMI.physio

/**
 * Evidence-based "patient is asleep now" signal — complements clock [isNight].
 *
 * Priority: therapy sleep event → Health Connect ongoing session → wearable heuristic (steps + HR vs RHR).
 */
object SleepLiveDetector {

    /** Minimum confidence to treat as asleep (leaf export + guards). */
    const val ASLEEP_THRESHOLD = 0.55

    /** Blocks hyper SMB release when asleep (union with clock night). */
    const val RELEASE_GUARD_THRESHOLD = 0.55

    private const val THERAPY_CONFIDENCE = 0.95
    private const val HC_ONGOING_CONFIDENCE = 0.88
    private const val MAX_HEURISTIC_CONFIDENCE = 0.72
    private const val STEPS_IDLE_MAX_15M = 40
    private const val STEPS_STILL_MAX_5M = 12
    private const val HR_REST_MARGIN_BPM = 12

    enum class Source {
        NONE,
        THERAPY,
        HEALTH_CONNECT,
        WEARABLE,
    }

    data class Input(
        val nowMs: Long = System.currentTimeMillis(),
        val therapySleepTime: Boolean = false,
        val stepsLast15m: Int = 0,
        val stepsLast5m: Int = 0,
        val hrNowBpm: Int = 0,
        val rhrRestingBpm: Int = 0,
        val hcSessionActive: Boolean = false,
        val clockIsNight: Boolean = false,
    )

    data class Result(
        val confidence: Double,
        val source: Source,
        val summary: String,
    ) {
        val isAsleep: Boolean get() = confidence >= ASLEEP_THRESHOLD
    }

    fun evaluate(input: Input): Result {
        if (input.therapySleepTime) {
            return Result(THERAPY_CONFIDENCE, Source.THERAPY, "therapy_sleep")
        }
        if (input.hcSessionActive) {
            return Result(HC_ONGOING_CONFIDENCE, Source.HEALTH_CONNECT, "hc_session_active")
        }
        val heuristic = wearableHeuristic(input)
        if (heuristic >= ASLEEP_THRESHOLD) {
            return Result(
                confidence = heuristic,
                source = Source.WEARABLE,
                summary = wearableSummary(input, heuristic),
            )
        }
        return Result(
            confidence = heuristic.coerceAtLeast(0.0),
            source = Source.NONE,
            summary = "awake",
        )
    }

    fun isAsleep(confidence: Double): Boolean = confidence >= ASLEEP_THRESHOLD

    /** Night window OR confident live-asleep (e.g. daytime nap). */
    fun sleepGuardActive(isNight: Boolean, asleepLiveConfidence: Double): Boolean =
        isNight || asleepLiveConfidence >= RELEASE_GUARD_THRESHOLD

    private fun wearableHeuristic(input: Input): Double {
        if (input.hrNowBpm <= 0) return 0.0
        val stepsQuiet = input.stepsLast15m <= STEPS_IDLE_MAX_15M &&
            input.stepsLast5m <= STEPS_STILL_MAX_5M
        if (!stepsQuiet) return 0.0
        val rhr = input.rhrRestingBpm.takeIf { it > 0 } ?: return 0.0
        if (input.hrNowBpm > rhr + HR_REST_MARGIN_BPM) return 0.0
        var score = 0.45
        if (input.stepsLast15m == 0 && input.stepsLast5m == 0) score += 0.12
        if (input.hrNowBpm <= rhr + 5) score += 0.10
        if (input.clockIsNight) score += 0.08
        return score.coerceIn(0.0, MAX_HEURISTIC_CONFIDENCE)
    }

    private fun wearableSummary(input: Input, confidence: Double): String =
        "wearable steps15=${input.stepsLast15m} hr=${input.hrNowBpm}/rhr=${input.rhrRestingBpm} " +
            "conf=${"%.2f".format(confidence)}"
}
