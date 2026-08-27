package app.aaps.plugins.aps.openAPSAIMI.activity

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlin.math.max
import kotlin.math.min

/**
 * Sensor-driven effort / activity belief.
 *
 * Fuses multi-window steps (5 / 15 / 60 min), heart-rate elevation, HRV depression and a stress
 * signal into a graded, **signed** posture ([Posture]) plus an effort-load **memory** that keeps the
 * protection alive after the activity ends (post-exercise insulin sensitivity lasts hours, whereas a
 * 15-min step window collapses in minutes).
 *
 * Key design points:
 *  - **Reduction-only output** ([Assessment.smbFactor] / [Assessment.basalFactor] are ≤ 1.0) — the
 *    insulin side can only be *lowered*; fail-safe direction.
 *  - **Exertion vs stress disambiguation**: HRV depression + HR elevation are common to both, but they
 *    pull insulin opposite ways (exercise → ↑sensitivity → less insulin; acute stress → ↑resistance).
 *    Steps disambiguate: movement ⇒ exertion (protect); HR↑ + HRV↓ + stress prob but no movement ⇒
 *    stress (do **not** reduce insulin).
 *  - **Onset is fast, memory is slow**: the 5-min window may open the *current* [State.ACTIVE]
 *    reduction on its own (we must not react late to real exercise), but it may **not** arm the
 *    cross-tick memory. Arming needs the 15-min window to show real movement, otherwise a single
 *    step burst (a walk to the kitchen) restarts hours of protection while the user sits still.
 *  - **Protection length follows the effort length**: the recent-effort horizon grows with how long
 *    the corroborated effort lasted, from [EFFORT_MEMORY_BASE_MIN] up to [EFFORT_MEMORY_MAX_MIN].
 *    A short walk gets short protection; a long session gets the full window.
 *  - **Pure & deterministic**: no clocks, no I/O. The caller owns [Memory] across ticks and passes
 *    `nowMs` in. This makes the whole branch unit-testable.
 */
object EffortActivityBelief {

    enum class State { IDLE, ACTIVE, RECENT_EFFORT }

    enum class Posture { NEUTRAL, EXERTION, STRESS }

    // ── Tunables (named for clarity and test anchoring) ─────────────────────────
    /** Steps/min that count as a brisk walk (full exertion intensity reference). */
    private const val WALK_STEPS_PER_MIN = 40.0
    /** Minimal sustained steps/min to consider the user "moving". */
    private const val ACTIVE_STEPS_PER_MIN = 25.0
    /** hrAvg15m − resting (bpm) that corroborates load. */
    private const val HR_ELEVATION_BPM = 25
    /** hrvDeviationZ at/under this is "depressed" (negative = below baseline). */
    private const val HRV_DEPRESSED_Z = -0.7
    /** Longest recent-effort protection, earned by a corroborated effort of [EFFORT_MINUTES_CAP]. */
    private const val EFFORT_MEMORY_MAX_MIN = 120.0
    /** Shortest recent-effort protection, given to the briefest corroborated effort. */
    private const val EFFORT_MEMORY_BASE_MIN = 30.0
    /** Extra minutes of protection per minute of corroborated effort. */
    private const val EFFORT_MEMORY_PER_EFFORT_MIN = 3.0
    /** Effort minutes credited to the first corroborated tick (one loop cycle). */
    private const val EFFORT_SEED_MIN = 5.0
    /** Longest gap between two corroborated ticks that still counts as the same effort. */
    private const val EFFORT_GAP_MAX_MIN = 15.0
    /** Effort minutes stop growing here; that value already earns [EFFORT_MEMORY_MAX_MIN]. */
    private const val EFFORT_MINUTES_CAP = 30.0
    /** Strongest SMB reduction at full exertion (×0.45). */
    private const val SMB_FLOOR = 0.45
    /** Strongest basal reduction at full exertion (×0.70). */
    private const val BASAL_FLOOR = 0.70

    /** Cross-tick memory owned by the caller. */
    data class Memory(
        val lastEffortMs: Long = 0L,
        /**
         * Steps/min of the **15-min** window that armed the memory; scales the recent-effort strength.
         * Deliberately not the 5-min rate: a short burst must not claim the strength of a real walk.
         */
        val peakStepsPerMin: Double = 0.0,
        /** How long the corroborated effort has lasted (minutes); scales the protection horizon. */
        val effortMinutes: Double = 0.0,
    )

    data class Inputs(
        val nowMs: Long,
        val stepsLast5m: Int,
        val stepsLast15m: Int,
        val stepsLast60m: Int,
        val hrAvg15mBpm: Int,
        val hrRestingBpm: Int,
        /** Normalised HRV deviation (negative = depressed); null when HRV unavailable. */
        val hrvDeviationZ: Double?,
        /** Transient insulin-resistance / stress probability (0..1). */
        val stressResistanceProb: Double,
    )

    data class Assessment(
        val state: State,
        val posture: Posture,
        val confidence: Double,
        val minutesSinceEffort: Double,
        /** Multiplicative SMB cap, in [SMB_FLOOR, 1.0]. 1.0 = no reduction. */
        val smbFactor: Double,
        /** Multiplicative basal cap, in [BASAL_FLOOR, 1.0]. */
        val basalFactor: Double,
        val reasons: List<String>,
    ) {
        val reducesInsulin: Boolean get() = smbFactor < 1.0 || basalFactor < 1.0
    }

    fun assess(inputs: Inputs, prior: Memory): Pair<Assessment, Memory> {
        val reasons = mutableListOf<String>()

        // Step rate: most reactive window with data drives onset; 60-min window is the load.
        val rate5 = if (inputs.stepsLast5m > 0) inputs.stepsLast5m / 5.0 else 0.0
        val rate15 = if (inputs.stepsLast15m > 0) inputs.stepsLast15m / 15.0 else 0.0
        val stepRate = max(rate5, rate15)
        val sustainedRate = if (inputs.stepsLast60m > 0) inputs.stepsLast60m / 60.0 else 0.0

        val hrElevation = if (inputs.hrAvg15mBpm > 0 && inputs.hrRestingBpm > 0) {
            inputs.hrAvg15mBpm - inputs.hrRestingBpm
        } else {
            0
        }
        val hrvDepressed = inputs.hrvDeviationZ != null && inputs.hrvDeviationZ <= HRV_DEPRESSED_Z

        // Onset (fast): the 5-min window alone may open the *current* reduction, so we never react
        // late to real exercise. It lasts only as long as the movement itself.
        val exertingNow = stepRate >= ACTIVE_STEPS_PER_MIN
        // Corroboration (slow): only the 15-min window may arm the cross-tick memory. Measured over a
        // 24-h field trace, every short burst that used to re-arm full protection had a 15-min rate of
        // at most 18 steps/min, while every real walk was 28-89 steps/min — this threshold sits between.
        val sustainedEffortNow = rate15 >= ACTIVE_STEPS_PER_MIN
        if (exertingNow) reasons.add("steps=${stepRate.toInt()}/min(5=${rate5.toInt()},15=${rate15.toInt()})")
        if (exertingNow && !sustainedEffortNow) reasons.add("burst_uncorroborated")
        if (hrElevation >= HR_ELEVATION_BPM) reasons.add("hr+$hrElevation")
        if (hrvDepressed) reasons.add("hrv_depressed")

        // Step-derived exertion intensity (0..1).
        val stepIntensity = when {
            stepRate >= WALK_STEPS_PER_MIN -> (0.6 + 0.4 * ((stepRate - WALK_STEPS_PER_MIN) / WALK_STEPS_PER_MIN)).coerceIn(0.6, 1.0)
            stepRate >= ACTIVE_STEPS_PER_MIN ->
                0.35 + 0.25 * ((stepRate - ACTIVE_STEPS_PER_MIN) / (WALK_STEPS_PER_MIN - ACTIVE_STEPS_PER_MIN))
            else -> 0.0
        }.coerceIn(0.0, 1.0)
        val sustainedBonus = (sustainedRate / WALK_STEPS_PER_MIN).coerceIn(0.0, 0.30)
        val hrConf = (hrElevation.toDouble() / (HR_ELEVATION_BPM * 2)).coerceIn(0.0, 0.50)
        val hrvBonus = if (hrvDepressed) 0.15 else 0.0

        // Memory update: only a **corroborated** effort stamps the time, the 15-min rate and the effort
        // length. A bare 5-min burst therefore cannot restart the protection — that was the defect that
        // kept insulin cut for hours while the user sat still. The peak is *not* carried over from an
        // already-expired prior effort, so a past hard effort cannot inflate a later light one.
        val priorHorizonMin = memoryHorizonMin(prior.effortMinutes)
        val priorAgeMin = if (prior.lastEffortMs > 0L) {
            ((inputs.nowMs - prior.lastEffortMs) / 60_000.0).coerceAtLeast(0.0)
        } else {
            Double.MAX_VALUE
        }
        val priorExpired = prior.lastEffortMs == 0L || priorAgeMin > priorHorizonMin
        val memory = if (sustainedEffortNow) {
            // Same effort continues when the last corroborated tick is still fresh; otherwise this is a
            // new effort and its length starts again at one loop cycle.
            val sameEffort = !priorExpired && priorAgeMin <= EFFORT_GAP_MAX_MIN
            val effortMinutes = if (sameEffort) {
                (safeMinutes(prior.effortMinutes) + priorAgeMin).coerceAtMost(EFFORT_MINUTES_CAP)
            } else {
                EFFORT_SEED_MIN
            }
            val basePeak = if (sameEffort) prior.peakStepsPerMin else 0.0
            Memory(
                lastEffortMs = inputs.nowMs,
                peakStepsPerMin = max(basePeak, rate15),
                effortMinutes = effortMinutes,
            )
        } else {
            prior
        }
        val minutesSinceEffort = if (memory.lastEffortMs > 0L) {
            ((inputs.nowMs - memory.lastEffortMs) / 60_000.0).coerceAtLeast(0.0)
        } else {
            Double.MAX_VALUE
        }
        // Protection length follows the effort length, not the peak of a noisy 5-min window.
        val horizonMin = memoryHorizonMin(memory.effortMinutes)

        val state = when {
            exertingNow -> State.ACTIVE
            minutesSinceEffort <= horizonMin -> State.RECENT_EFFORT
            else -> State.IDLE
        }
        // Linear decay of recent-effort protection over the (effort-scaled) memory horizon.
        val recency = when (state) {
            State.ACTIVE -> 1.0
            State.RECENT_EFFORT -> (1.0 - minutesSinceEffort / horizonMin).coerceIn(0.0, 1.0)
            State.IDLE -> 0.0
        }

        // Posture: movement ⇒ exertion; HR↑ + (HRV↓ or stress) without movement ⇒ stress.
        val stress = inputs.stressResistanceProb.coerceIn(0.0, 1.0)
        val posture = when {
            exertingNow -> Posture.EXERTION
            state == State.RECENT_EFFORT -> Posture.EXERTION
            hrElevation >= HR_ELEVATION_BPM && (hrvDepressed || stress >= 0.55) -> Posture.STRESS
            else -> Posture.NEUTRAL
        }

        // Exertion strength drives the (reduction-only) insulin factors. Stress NEVER reduces insulin.
        val exertionStrength = when {
            state == State.ACTIVE ->
                (stepIntensity + sustainedBonus + hrConf + hrvBonus).coerceIn(0.0, 1.0)
            state == State.RECENT_EFFORT -> {
                // peakStepsPerMin is now the corroborated 15-min rate, so this is at least
                // ACTIVE_STEPS_PER_MIN / WALK_STEPS_PER_MIN = 0.625 for any memory this object armed.
                // The 0.30 floor only still applies to a memory restored by the caller.
                val peakScale = (memory.peakStepsPerMin / WALK_STEPS_PER_MIN).coerceIn(0.30, 1.0)
                (recency * peakScale).coerceIn(0.0, 1.0)
            }
            else -> 0.0
        }

        // Hard bounds. A non-finite input can only ever mean "unknown", so it must fail open (1.0 =
        // no reduction), never leak NaN into the dose path.
        val smbFactor = clampFactor(1.0 - (1.0 - SMB_FLOOR) * exertionStrength, SMB_FLOOR)
        val basalFactor = clampFactor(1.0 - (1.0 - BASAL_FLOOR) * exertionStrength, BASAL_FLOOR)

        val confidence = when (posture) {
            Posture.EXERTION -> max(exertionStrength, recency)
            Posture.STRESS -> (hrConf * 1.4 + hrvBonus + stress * 0.4).coerceIn(0.0, 1.0)
            Posture.NEUTRAL -> 0.0
        }.let { if (it.isFinite()) it.coerceIn(0.0, 1.0) else 0.0 }

        if (posture == Posture.STRESS) reasons.add("stress=${aimiFmt2(stress)}")
        reasons.add("${state.name}/${posture.name} since=${if (minutesSinceEffort == Double.MAX_VALUE) "∞" else minutesSinceEffort.toInt().toString()}m")

        val assessment = Assessment(
            state = state,
            posture = posture,
            confidence = confidence,
            minutesSinceEffort = minutesSinceEffort,
            smbFactor = smbFactor,
            basalFactor = basalFactor,
            reasons = reasons,
        )
        return assessment to memory
    }

    /**
     * Recent-effort protection horizon in minutes, scaled by how long the corroborated effort lasted.
     * A single corroborated tick earns [EFFORT_MEMORY_BASE_MIN] + one cycle; a continuous effort of
     * [EFFORT_MINUTES_CAP] earns the full [EFFORT_MEMORY_MAX_MIN], so a real session keeps exactly the
     * protection it had before this scaling existed.
     */
    private fun memoryHorizonMin(effortMinutes: Double): Double =
        (EFFORT_MEMORY_BASE_MIN + EFFORT_MEMORY_PER_EFFORT_MIN * safeMinutes(effortMinutes))
            .coerceIn(EFFORT_MEMORY_BASE_MIN, EFFORT_MEMORY_MAX_MIN)

    /** Sanitises a caller-owned minute count: non-finite or negative means "no effort recorded". */
    private fun safeMinutes(minutes: Double): Double =
        if (minutes.isFinite()) minutes.coerceIn(0.0, EFFORT_MINUTES_CAP) else 0.0

    /** Clamps a reduction-only factor into `[floor, 1.0]`; a non-finite value fails open at 1.0. */
    private fun clampFactor(value: Double, floor: Double): Double =
        if (value.isFinite()) value.coerceIn(floor, 1.0) else 1.0
}
