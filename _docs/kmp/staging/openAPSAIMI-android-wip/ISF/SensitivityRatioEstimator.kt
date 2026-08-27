package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import org.json.JSONObject
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope
import kotlin.math.exp
import kotlin.math.ln

/**
 * Estimates **how strongly insulin actually acts for this patient**, as a dimensionless ratio on the
 * profile ISF.
 *
 * ## Why a ratio, and why from outcomes
 *
 * The commanded sensitivity is today a stack of ten stages — a BG staircase, a delta correction, two
 * disagreeing fast estimators combined by a maximum, a slow floor, three rate limiters and a cache —
 * and only 18 % of its movement is explained by the variables it is supposed to depend on. It is not
 * an estimate of anything; it is a dosing policy wearing the name of a physiological parameter.
 *
 * This estimator answers one question and nothing else: *given what was delivered, how much did
 * glucose actually fall?* It observes **closed windows** where the answer is readable — nothing
 * being digested, no bolus of any origin, a decaying IOB, glucose going down — and folds each
 * observation into a slow state.
 *
 * The result is a ratio, not an ISF, so it composes: `S = profileIsf × ratio`. The circadian shape
 * stays owned by the user's profile blocks, which is where it belongs.
 *
 * ## What "closed" has to mean
 *
 * A window that is merely free of AIMI's own SMBs is not closed. A meal bolus is a bolus, and the
 * carbs it covers are still being absorbed: the absorption offsets part of the fall, so the drop
 * credited to insulin is **too small**, so the measured ratio is **too low**, so the sensitivity
 * commanded from it is too low, so the loop gives **more** insulin. That is the dangerous direction,
 * on the quantity proposed to replace the commanded sensitivity. So a window is rejected outright if
 * it — or its run-up — contains any carbs on board, any bolus of any origin, or any SMB.
 *
 * ## The basal deficit correction
 *
 * `iobU` is net of profile basal. When the loop runs a zero temp basal, IOB decays partly because
 * insulin acted and partly because insulin was **not given** — and the second part lowers nothing.
 * Ignoring it made the same production data read 9.7 mg/dL/U instead of 11.9. The deficit is
 * subtracted from the insulin credited with the fall, and it is computed from the basal that was
 * **delivered**, which is the running temp basal, not the rate the loop is about to ask for.
 *
 * ## Why the state is persisted
 *
 * Roughly 52 observations are needed to move the ratio from 1.0 to 0.94. Android kills the process
 * routinely, and an in-memory ratio restarts at 1.0 every time — so in the field it would never
 * converge, and every export would show a value that had merely not had time to move. The ratio,
 * the observation count and the time of the last fold are written to disk and reloaded, with a
 * staleness rule: state older than [STALE_AFTER_DAYS] days describes a patient who may have changed,
 * so it is discarded rather than trusted.
 *
 * ## What it is not
 *
 * It cannot separate a meal from a drop in sensitivity within a single episode — measured on
 * 2026-08-04, a real meal and the fat tail of an earlier one were indistinguishable on BG, delta,
 * IOB and IOB slope. That is why observations are only taken in windows where nothing is being
 * digested, and why the state moves over hours, not minutes.
 *
 * See `docs/adr/0008-isf-decision-architecture.md`.
 */
@SingleIn(AppScope::class)
class SensitivityRatioEstimator @Inject constructor(
    private val storageHelper: AimiStorageHelper,
) {

    data class Sample(
        val timestampMs: Long,
        val bgMgdl: Double,
        val iobU: Double,
        val profileBasalUph: Double,
        /** Basal the pump is **running**, not the rate the loop is about to request. */
        val deliveredBasalUph: Double,
        /** SMB commanded on this tick. */
        val smbU: Double,
        val profileIsfMgdl: Double,
        /** Carbs on board. Any amount disqualifies the window: absorption hides part of the fall. */
        val cobG: Double,
        /** Time of the most recent bolus of **any** origin, or 0 when none is known. */
        val lastBolusMs: Long,
    )

    /** One usable observation, kept for logging and tests. */
    data class Observation(
        val timestampMs: Long,
        val ratio: Double,
        val bgDropMgdl: Double,
        val insulinActedU: Double,
        val windowMinutes: Double,
    )

    private val samples = ArrayDeque<Sample>()

    /** Current slow state. Starts neutral: the profile is trusted until measurements say otherwise. */
    var ratio: Double = 1.0
        private set

    /** Last observation folded in, or `null` when none has been taken yet. */
    var lastObservation: Observation? = null
        private set

    /** Number of observations folded in, across restarts. */
    var observationCount: Int = 0
        private set

    /** Time of the last fold, across restarts. 0 when nothing has ever been folded. */
    var lastFoldMs: Long = 0L
        private set

    private var stateLoaded = false

    /**
     * Feeds one loop tick and returns the current ratio.
     *
     * Cheap and side-effect free apart from the internal state and the small state file written on a
     * fold: safe to call on every tick, including ticks where nothing else runs. An estimator that
     * only updates when a controller engages is not an estimator — that defect is why the meal model
     * froze for hours at a time.
     */
    fun observe(sample: Sample): Double {
        if (!sample.isUsable()) return ratio
        loadStateOnce(sample.timestampMs)
        samples.addLast(sample)
        while (samples.isNotEmpty() &&
            sample.timestampMs - samples.first().timestampMs > RETENTION_MINUTES * 60_000L
        ) {
            samples.removeFirst()
        }
        closedWindowObservation(sample)
            ?.takeIf { mayFoldAt(it.timestampMs) }
            ?.let { fold(it) }
        return ratio
    }

    /** Sensitivity this estimator would command for [profileIsfMgdl], bounded relative to it. */
    fun sensitivityMgdl(profileIsfMgdl: Double): Double =
        if (profileIsfMgdl.isFinite() && profileIsfMgdl > 0.0) {
            (profileIsfMgdl * ratio).coerceIn(profileIsfMgdl * MIN_RATIO, profileIsfMgdl * MAX_RATIO)
        } else {
            profileIsfMgdl
        }

    fun reset() {
        samples.clear()
        ratio = 1.0
        lastObservation = null
        observationCount = 0
        lastFoldMs = 0L
    }

    private fun Sample.isUsable(): Boolean =
        timestampMs > 0L && bgMgdl.isFinite() && bgMgdl > 0.0 &&
            iobU.isFinite() && profileIsfMgdl.isFinite() && profileIsfMgdl > 0.0

    /**
     * Looks for a window ending at [end] where the fall can be attributed to insulin alone.
     *
     * Rejects anything with carbs on board or a bolus of any origin inside the window or in the
     * run-up, a rise (which would mean carbs are arriving), or too little insulin acting to measure
     * against.
     */
    private fun closedWindowObservation(end: Sample): Observation? {
        val start = samples.firstOrNull { s ->
            val span = (end.timestampMs - s.timestampMs) / 60_000.0
            span in MIN_WINDOW_MINUTES..MAX_WINDOW_MINUTES
        } ?: return null

        val window = samples.filter { it.timestampMs in start.timestampMs..end.timestampMs }
        if (window.size < MIN_WINDOW_SAMPLES) return null

        val runUpStart = start.timestampMs - QUIET_RUN_UP_MINUTES * 60_000L
        val runUp = samples.filter { it.timestampMs in runUpStart until start.timestampMs }
        val quietZone = window + runUp
        // Any insulin the loop did not schedule as basal, and any digestion, breaks the attribution.
        if (quietZone.any { it.smbU > 0.0 }) return null
        if (quietZone.any { it.cobG > 0.0 }) return null
        // 0 means "no bolus is known", not "a bolus at the epoch".
        if (quietZone.any { it.lastBolusMs > 0L && it.lastBolusMs >= runUpStart }) return null

        if (start.iobU < MIN_START_IOB_U) return null
        val drop = start.bgMgdl - end.bgMgdl
        if (drop <= 0.0) return null

        val acted = (start.iobU - end.iobU) - basalDeficitU(window)
        if (acted < MIN_ACTED_U) return null

        val modulation = window.map { bgModulation(it.bgMgdl) }.average()
        if (modulation <= 0.0) return null

        val observed = drop / acted / modulation / start.profileIsfMgdl
        if (!observed.isFinite() || observed <= 0.0) return null

        return Observation(
            timestampMs = end.timestampMs,
            ratio = observed,
            bgDropMgdl = drop,
            insulinActedU = acted,
            windowMinutes = (end.timestampMs - start.timestampMs) / 60_000.0,
        )
    }

    /** Insulin the loop did **not** give relative to the profile over the window, in units. */
    private fun basalDeficitU(window: List<Sample>): Double =
        window.zipWithNext().sumOf { (a, b) ->
            val hours = (b.timestampMs - a.timestampMs) / 3_600_000.0
            maxOf(0.0, a.profileBasalUph - a.deliveredBasalUph) * hours
        }

    /**
     * Windows overlap: the same fall qualifies on several consecutive ticks, and folding each of
     * them would make one episode count five or ten times. A minimum spacing keeps one episode
     * roughly one observation.
     */
    private fun mayFoldAt(timestampMs: Long): Boolean =
        lastFoldMs <= 0L || timestampMs - lastFoldMs >= MIN_FOLD_SPACING_MINUTES * 60_000L

    /**
     * Folds one observation with a weight set by the **time since the last fold**.
     *
     * The alpha used to be derived for a 5-minute step while `fold` was called once per qualifying
     * observation, so the realised time constant was `TAU_HOURS × ticks-per-observation` — unbounded,
     * and different for every patient. Keying it on elapsed time makes [TAU_HOURS] mean what it says.
     * The elapsed time is capped at [MAX_FOLD_ELAPSED_HOURS] so a single window after a long quiet
     * period cannot dominate the state.
     */
    private fun fold(observation: Observation) {
        val bounded = observation.ratio.coerceIn(MIN_OBSERVATION_RATIO, MAX_OBSERVATION_RATIO)
        val elapsedHours = if (lastFoldMs <= 0L) {
            MIN_FOLD_SPACING_MINUTES / 60.0
        } else {
            ((observation.timestampMs - lastFoldMs) / 3_600_000.0)
                .coerceIn(MIN_FOLD_SPACING_MINUTES / 60.0, MAX_FOLD_ELAPSED_HOURS)
        }
        val alpha = 1.0 - exp(-elapsedHours / TAU_HOURS)
        ratio = (ratio + alpha * (bounded - ratio)).coerceIn(MIN_RATIO, MAX_RATIO)
        lastObservation = observation
        observationCount++
        lastFoldMs = observation.timestampMs
        saveState(observation.timestampMs)
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------

    private fun loadStateOnce(nowMs: Long) {
        if (stateLoaded) return
        stateLoaded = true
        runCatching {
            val file = storageHelper.getAimiFile(STATE_FILE_NAME)
            if (!file.exists() || file.length() == 0L) return@runCatching
            val json = JSONObject(file.readText())
            val savedAtMs = json.optLong("saved_at_ms", 0L)
            val ageMs = nowMs - savedAtMs
            // Too old to describe this patient, or stamped in the future by a clock change.
            if (savedAtMs <= 0L || ageMs > STALE_AFTER_DAYS * 24L * 3_600_000L || ageMs < -CLOCK_SKEW_TOLERANCE_MS) {
                return@runCatching
            }
            val savedRatio = json.optDouble("ratio", 1.0)
            if (!savedRatio.isFinite()) return@runCatching
            ratio = savedRatio.coerceIn(MIN_RATIO, MAX_RATIO)
            observationCount = json.optInt("observation_count", 0).coerceAtLeast(0)
            lastFoldMs = json.optLong("last_fold_ms", 0L).coerceAtLeast(0L)
        }
    }

    private fun saveState(nowMs: Long) {
        runCatching {
            val json = JSONObject()
                .put("ratio", ratio)
                .put("observation_count", observationCount)
                .put("last_fold_ms", lastFoldMs)
                .put("saved_at_ms", nowMs)
            storageHelper.saveFileSafe(storageHelper.getAimiFile(STATE_FILE_NAME), json.toString())
        }
    }

    companion object {

        /**
         * Situational modulation the observation is divided by, so the ratio measures sensitivity
         * and not the shape of the BG dependence. Normalised to 1 at [MODULATION_REFERENCE_MGDL].
         */
        fun bgModulation(bgMgdl: Double): Double {
            val safe = bgMgdl.coerceAtLeast(40.0)
            return ln(MODULATION_REFERENCE_MGDL / MODULATION_BASE_MGDL + 1) /
                ln(safe / MODULATION_BASE_MGDL + 1)
        }

        const val MODULATION_BASE_MGDL = 75.0
        const val MODULATION_REFERENCE_MGDL = 100.0

        /** Sensitivity moves over hours, not minutes. */
        const val TAU_HOURS = 12.0

        /** Two folds closer together than this describe the same episode. */
        const val MIN_FOLD_SPACING_MINUTES = 30.0

        /** Weight ceiling for one fold, so a lone window after a quiet night cannot take over. */
        const val MAX_FOLD_ELAPSED_HOURS = 2.0

        /** The commanded value stays inside the domain where the profile still means something. */
        const val MIN_RATIO = 0.5
        const val MAX_RATIO = 2.0

        /** A single window is never trusted beyond this, whatever it computes. */
        const val MIN_OBSERVATION_RATIO = 0.2
        const val MAX_OBSERVATION_RATIO = 3.0

        const val MIN_WINDOW_MINUTES = 36.0
        const val MAX_WINDOW_MINUTES = 63.0
        const val MIN_WINDOW_SAMPLES = 6
        const val QUIET_RUN_UP_MINUTES = 30L
        const val MIN_START_IOB_U = 0.8
        const val MIN_ACTED_U = 0.3
        const val RETENTION_MINUTES = 120L

        /** Saved state older than this is discarded: the patient may no longer be the same. */
        const val STALE_AFTER_DAYS = 7L

        const val STATE_FILE_NAME = "sensitivity_ratio_state.json"

        private const val CLOCK_SKEW_TOLERANCE_MS = 24L * 3_600_000L
    }
}
