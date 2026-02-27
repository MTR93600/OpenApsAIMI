package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.math.abs
import kotlin.math.round

/**
 * =====================================================================
 * AIMI Dynamic Basal Controller — Predictive PI v2
 * =====================================================================
 *
 * Key change from v1:
 *   Error term = predictedBg - target  (NOT current bg - target)
 *
 * Rationale: rapid-acting insulin has a 45-90 min onset. If we react
 * to where we ARE, we always arrive too late. We must dose for where
 * we will BE when the insulin starts to act.
 *
 * Example at BG=120, delta=+10, target=100, ISF=50:
 *   predictedBg (60 min) = 120 + 10 × 12 = 240
 *   error = 240 - 100 = +140 mg/dL
 *   multiplier ≈ 5-7× profile basal  ← aggressive but necessary
 *
 * Scale: 0% (suspend) to 1000% (10× profile basal)
 * The pump's own max_basal caps the absolute U/h output.
 *
 * @author MTR
 */
object DynamicBasalController {

    // ── Gain constants ───────────────────────────────────────────────────────
    private const val KP = 0.45   // Proportional: multiplier per unit of predicted error / ISF
    private const val KI = 0.015  // Integral: multiplier per minute of accumulated hyper / ISF
    private const val KD = 0.10   // Derivative: multiplier per unit of delta velocity / ISF

    // ── Scale bounds ─────────────────────────────────────────────────────────
    private const val MIN_FRACTION  = 0.0   // Suspend allowed
    private const val MAX_FRACTION  = 10.0  // 1000% = 10× profile basal

    // ── Hypo guards (based on current BG, not predicted) ────────────────────
    private const val HYPO_HARD  = 70.0
    private const val HYPO_SOFT  = 85.0

    // ── Dead-band: if prediction within ±8 of target AND delta calm → hold ──
    private const val DEAD_BAND_MG    = 8.0
    private const val DEAD_BAND_DELTA = 1.2

    // ── Prediction horizon ───────────────────────────────────────────────────
    // Insulin action onset: ~60 min → project 12 × 5-min readings
    private const val PREDICTION_INTERVALS = 12

    data class Input(
        val bg: Double,
        val targetBg: Double,
        val delta: Double,
        val shortAvgDelta: Double,
        val longAvgDelta: Double = 0.0,
        val iob: Double,
        val maxIob: Double,
        val profileBasal: Double,
        val variableSensitivity: Double,    // ISF in mg/dL/U
        val duraISFminutes: Double = 0.0,   // Accumulated time above ISF threshold
        val predictedBgOverride: Double? = null, // Supply eventualBg if available
        val mode: Mode = Mode.STANDARD
    )

    enum class Mode {
        STANDARD,
        T3C
    }

    data class Decision(
        val rate: Double,
        val durationMin: Int,
        val reason: String,
        val zone: Zone
    )

    enum class Zone {
        HYPO_HARD,
        HYPO_SOFT,
        DEAD_BAND,
        DYNAMIC_PI
    }

    fun compute(input: Input): Decision {
        val profile = input.profileBasal
        if (profile <= 0.0) return Decision(0.0, 30, "DynBasal: profile=0, skip", Zone.HYPO_HARD)

        val isf = input.variableSensitivity.coerceAtLeast(10.0)

        // ── Safety guard 1: Hard Hypo (current BG) ───────────────────────────
        if (input.bg <= HYPO_HARD || (input.bg <= HYPO_SOFT + 5 && input.delta < -3.5)) {
            return Decision(
                rate = 0.0, durationMin = 30,
                reason = "DynBasal🔴 HardHypo[BG=${"%.0f".format(input.bg)},Δ=${"%.1f".format(input.delta)}] → SUSPEND",
                zone = Zone.HYPO_HARD
            )
        }

        // ── Safety guard 2: Soft Hypo ─────────────────────────────────────────
        if (input.bg <= HYPO_SOFT) {
            val softRate = if (input.delta > 0) profile * 0.25 else 0.0
            return Decision(
                rate = softRate.coerceIn(0.0, profile),
                durationMin = 30,
                reason = "DynBasal🟡 SoftHypo[BG=${"%.0f".format(input.bg)},Δ=${"%.1f".format(input.delta)}] → ${"%.2f".format(softRate)}U/h",
                zone = Zone.HYPO_SOFT
            )
        }

        // ── Predict BG at insulin action horizon (60 min) ────────────────────
        // Blend short and long avg deltas for robust projection
        val effectiveDelta = (input.shortAvgDelta * 0.55 + input.delta * 0.30 + input.longAvgDelta * 0.15)
        val predictedBg = input.predictedBgOverride
            ?: (input.bg + effectiveDelta * PREDICTION_INTERVALS).coerceAtLeast(40.0)

        val predictedError = predictedBg - input.targetBg

        // ── Dead band: prediction inside target range AND calm delta ──────────
        if (abs(predictedError) <= DEAD_BAND_MG && abs(effectiveDelta) <= DEAD_BAND_DELTA) {
            return Decision(
                rate = profile,
                durationMin = 30,
                reason = "DynBasal⚪ DeadBand[pred=${"%.0f".format(predictedBg)},err=${"%.0f".format(predictedError)}] → profile",
                zone = Zone.DEAD_BAND
            )
        }

        // ── IOB safety brake: BG falling + high IOB → pull back ──────────────
        val iobOverMax = input.iob > input.maxIob * 0.85
        val iobBrake = if (iobOverMax && predictedError < 0) 0.35 else 1.0

        // ── PI(D) Controller on predicted error ───────────────────────────────
        // Proportional: how far above/below target is the predicted BG
        val pTerm = KP * (predictedError / isf)

        // Integral: penalize prolonged hyperglycemia (duraISFminutes proxy)
        val iTerm = if (predictedError > 0) KI * (input.duraISFminutes / isf) else 0.0

        // Derivative: how fast is BG moving right now (velocity signal)
        val dTerm = KD * (effectiveDelta / isf)

        var multiplier = (1.0 + pTerm + iTerm + dTerm) * iobBrake

        // ── Apply mode-specific bounds ─────────────────────────────────────────
        val maxMult = when (input.mode) {
            Mode.T3C     -> MAX_FRACTION   // T3c also gets full 1000% — pump's max_basal is the real cap
            Mode.STANDARD -> MAX_FRACTION
        }
        multiplier = multiplier.coerceIn(MIN_FRACTION, maxMult)

        // Quantize to 0.05 U/h steps
        val rawRate = profile * multiplier
        val rate = (rawRate / 0.05).let { round(it) * 0.05 }

        val direction = when {
            multiplier > 1.20 -> "↑"
            multiplier < 0.80 -> "↓"
            else -> "→"
        }
        val modeTag = if (input.mode == Mode.T3C) "[T3c]" else ""
        val pct = (multiplier * 100).toInt()

        val reason = buildString {
            append("DynBasal$modeTag$direction ")
            append("now=${"%.0f".format(input.bg)} ")
            append("pred=${"%.0f".format(predictedBg)}/T${"%.0f".format(input.targetBg)} ")
            append("err=${"%.0f".format(predictedError)} ")
            append("Δ=${"%.1f".format(effectiveDelta)} ")
            append("P=${"%.2f".format(pTerm)}+I=${"%.2f".format(iTerm)}+D=${"%.2f".format(dTerm)} ")
            append("→ ${pct}% × ${"%.2f".format(profile)} = ${"%.2f".format(rate)}U/h")
            if (iobOverMax) append(" [IOB⚠️]")
        }

        return Decision(rate, 30, reason, Zone.DYNAMIC_PI)
    }

    /**
     * Convenience wrapper for T3c brittle mode.
     * Accepts eventualBg directly to use it as the predicted BG override.
     */
    fun computeT3c(
        bg: Double,
        targetBg: Double,
        delta: Float,
        shortAvgDelta: Double,
        longAvgDelta: Double = 0.0,
        iob: Double,
        maxIob: Double,
        profileBasal: Double,
        isf: Double,
        duraISFminutes: Double,
        eventualBg: Double? = null
    ): Decision = compute(
        Input(
            bg = bg,
            targetBg = targetBg,
            delta = delta.toDouble(),
            shortAvgDelta = shortAvgDelta,
            longAvgDelta = longAvgDelta,
            iob = iob,
            maxIob = maxIob,
            profileBasal = profileBasal,
            variableSensitivity = isf,
            duraISFminutes = duraISFminutes,
            predictedBgOverride = eventualBg,
            mode = Mode.T3C
        )
    )
}
