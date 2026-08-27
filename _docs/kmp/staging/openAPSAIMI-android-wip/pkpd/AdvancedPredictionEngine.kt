package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.data.configuration.Constants
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.OapsProfileAimi
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Provides a unified prediction model that mirrors the same parameters used during SMB/basal decisions.
 */
object AdvancedPredictionEngine {

    private const val NUMERIC_FLOOR = 39.0
    private const val NUMERIC_CEILING = 401.0
    private const val CARB_ABSORPTION_MINUTES = 180.0
    private const val STEP_MINUTES = 5

    // 🩸 Réversion endogène anti-absorbante (EGP). Sans ce terme, les courbes déclinent jusqu'au
    // plancher absorbant 39 et y restent alors que le BG réel ne touche pas l'hypo.
    // Wave4 H3: même physique sur IOB/COB/UAM/ZT **et** hybrid (une vérité graphe + path-min + dose).
    // Gate: |impact insuline/pas| négligeable + sous baseline → dérive lente vers ≈80.
    //
    // Field correction 2026-07-22 (support-package 1784724586473, 24 h) — deux garde-fous sécurité :
    //  • Guard A : l'ancre de réversion est plafonnée par le BG courant (jamais > BG quand BG < 80),
    //    pour ne pas *inventer* un rebond au-dessus de la valeur mesurée sur un plateau bas
    //    (observé : BG=70 à plat → EGP prédisait 80). Corrige toujours l'artefact plancher 39.
    //  • Guard B : réversion totalement suspendue quand le BG chute franchement (delta ≤ -3), pour
    //    laisser le path-min de sécurité rester pessimiste pendant une vraie descente (observé : -11).
    private const val ENDO_REVERSION_BASELINE_MGDL = 80.0
    private const val ENDO_REVERSION_RATE = 0.06            // fraction du gap comblée par pas de 5 min (lent)
    private const val ENDO_INSULIN_NEGLIGIBLE_MGDL = 0.3    // |impact insuline/pas| en dessous = insuline épuisée

    /** Guard B — sous ce delta (mg/dL/5 min) le BG chute franchement : on suspend l'EGP.
     * Aligné sur [app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile.MAX_NEG_DELTA_MGDL]. */
    private const val ENDO_REVERSION_FALLING_HARD_DELTA_MGDL = -3.0

    /** F1-B — fraction of the (BG − floor) gap the active stack must be able to cover (IOB×ISF) before the
     *  hyper floor is treated as unsafe and suspended (only while BG is no longer rising). 1.0 = the stack
     *  alone can reach the floor. Gated by the stack-aware Guard B toggle (default off). */
    private const val ENDO_REVERSION_STACK_HEADROOM_FRACTION = 1.0

    /** Hyper-reversion (2026-07-26) — root fix for the undeclared-meal false-hypo. The legacy gate only
     * lets EGP revert once |insulinImpact| is negligible; with high IOB + long learned DIA that never
     * happens inside the horizon, so the insulin-only path crashes to the 39 floor while real BG sits at
     * ~200 (COB=0). When the patient is *clearly hyper now*, a projected crash to 39 is non-physiological:
     * allow the reversion even while insulin is active. Guard A (baseline cap ≤ 80 ≤ currentBG) keeps it
     * from ever predicting a rise, and Guard B (falling-hard) still suspends it — so this never becomes
     * optimistic and never touches euglycemic/low BG. */
    private const val HYPER_REVERSION_LEVEL_MGDL = 160.0

    /**
     * Predict the BG evolution using the final ISF/sensitivity applied by the decision engine.
     * Returns the **hybrid** curve (legacy single-path behaviour).
     */
    @JvmStatic
    @JvmOverloads
    fun predict(
        currentBG: Double,
        iobArray: Array<IobTotal>,
        finalSensitivity: Double,
        cobG: Double,
        profile: OapsProfileAimi,
        delta: Double = 0.0,
        horizonMinutes: Int = 240,
        modulation: PredictionPhysioModulation = PredictionPhysioModulation(),
    ): List<Double> =
        predictCurves(
            currentBG = currentBG,
            iobArray = iobArray,
            finalSensitivity = finalSensitivity,
            cobG = cobG,
            profile = profile,
            delta = delta,
            horizonMinutes = horizonMinutes,
            modulation = modulation,
        ).hybrid

    /**
     * Phase 4A — distinct IOB-only, COB+insulin, UAM+momentum, ZT (IOB mirror), and hybrid paths.
     */
    @JvmStatic
    @JvmOverloads
    fun predictCurves(
        currentBG: Double,
        iobArray: Array<IobTotal>,
        finalSensitivity: Double,
        cobG: Double,
        profile: OapsProfileAimi,
        delta: Double = 0.0,
        horizonMinutes: Int = 240,
        modulation: PredictionPhysioModulation = PredictionPhysioModulation(),
        endogenousReversionEnabled: Boolean = false,
        hyperReversionEnabled: Boolean = false,
        stackAwareGuardBEnabled: Boolean = false,
    ): AdvancedPredictionCurves {
        val effectiveHorizonMinutes = maxOf(Constants.PREDICTION_GRAPH_MIN_MINUTES, horizonMinutes)
        val iobSeries = mutableListOf(currentBG)
        val cobSeries = mutableListOf(currentBG)
        val uamSeries = mutableListOf(currentBG)
        val ztSeries = mutableListOf(currentBG)
        val hybridSeries = mutableListOf(currentBG)
        if (effectiveHorizonMinutes <= 0) {
            return AdvancedPredictionCurves(
                iob = iobSeries,
                cob = cobSeries,
                uam = uamSeries,
                zt = ztSeries,
                hybrid = hybridSeries,
            )
        }

        val steps = maxOf(1, effectiveHorizonMinutes / STEP_MINUTES)
        val now = System.currentTimeMillis()
        val carbRatio = profile.carb_ratio.takeIf { it > 0 } ?: 10.0
        val effectiveSensitivity = modulation.effectiveSensitivityMgdlPerU
            .takeIf { it.isFinite() && it > 0.0 }
            ?: finalSensitivity
        val csf = finalSensitivity / carbRatio

        val currentEntry = iobArray.minByOrNull { abs(it.time - now) }
        val currentActivity = currentEntry?.activity ?: 0.0
        val expectedDeltaFromInsulin = -(currentActivity * effectiveSensitivity * STEP_MINUTES.toDouble() * modulation.insulinImpactFactor)
        val initialCarbImpact = if (cobG > 0) {
            ((cobG * csf) / (CARB_ABSORPTION_MINUTES / STEP_MINUTES)) * modulation.carbImpactFactor
        } else {
            0.0
        }
        val expectedDelta = expectedDeltaFromInsulin + initialCarbImpact
        val deviation = delta - expectedDelta
        var hybridMomentum = deviation * modulation.hybridMomentumFactor

        val iobDampingFactor = when {
            delta > 10.0 -> 0.40
            delta > 5.0 -> 0.60
            delta > 2.0 -> 0.80
            else -> 1.0
        }
        val momentumDecay = ((if (delta > 3.0) 0.92 else 0.85) * modulation.momentumDecayFactor)
            .coerceIn(0.72, 0.96)

        var lastIob = currentBG
        var lastCob = currentBG
        var lastUam = currentBG
        var lastZt = currentBG
        var lastHybrid = currentBG
        var uamMomentum = deviation * modulation.uamMomentumFactor
        var rawInsulinPathMin = Double.POSITIVE_INFINITY
        var softInsulinPathMin = Double.POSITIVE_INFINITY
        var endoAppliedOnInsulin = false
        // Diagnostic mirror of the IOB path with no clip, so the published minimum can be read as
        // either a forecast or a saturation. See AdvancedPredictionCurves.insulinPathMinUnclippedMgdl.
        var unclippedIob = currentBG
        var unclippedInsulinPathMin = Double.POSITIVE_INFINITY
        var numericFloorClippedSteps = 0

        // Guard A — cap the reversion anchor at the current BG so EGP never predicts a rise above
        // where the patient actually sits (still lifts the absorbing floor-39 artefact when BG > 80).
        val endoBaseline = min(ENDO_REVERSION_BASELINE_MGDL, maxOf(currentBG, NUMERIC_FLOOR))
        // Guard B — suspend EGP entirely while BG is falling hard: keep the safety path-min pessimistic.
        // Fail-closed on a non-finite delta: an unknown trend suspends EGP (never lifts on garbage).
        // F1-B (opt-in) — a large active stack can breach the floor even when the momentary trend is flat:
        // if IOB×ISF exceeds the (BG − floor) gap AND BG is no longer rising (delta < 0), the floor is
        // masking a real low, so suspend EGP on capacity too (not just on delta). The delta < 0 bound keeps
        // it from ever backing off a genuine rise. Fail-safe: gated by stackAwareGuardBEnabled (default off).
        val currentIob = currentEntry?.iob ?: 0.0
        val stackCanBreachFloor = stackAwareGuardBEnabled && delta < 0.0 &&
            effectiveSensitivity > 0.0 &&
            currentIob * effectiveSensitivity > ENDO_REVERSION_STACK_HEADROOM_FRACTION * (currentBG - endoBaseline)
        val endoSuppressedByFallingTrend = endogenousReversionEnabled &&
            (!delta.isFinite() || delta <= ENDO_REVERSION_FALLING_HARD_DELTA_MGDL || stackCanBreachFloor)
        val endoActive = endogenousReversionEnabled && !endoSuppressedByFallingTrend
        // Clearly hyper now → a projected crash to the 39 floor is an artefact of the insulin-only
        // path; allow reversion even while insulin is still active (Guards A/B remain in force).
        val clearHyperContext = hyperReversionEnabled && currentBG >= HYPER_REVERSION_LEVEL_MGDL

        repeat(steps) { stepIndex ->
            val minutesInFuture = (stepIndex + 1) * STEP_MINUTES
            val targetTime = now + (minutesInFuture * 60_000L)
            val entry = iobArray.minByOrNull { abs(it.time - targetTime) }
            val activityRate = entry?.activity ?: 0.0
            val insulinImpact =
                activityRate * effectiveSensitivity * STEP_MINUTES.toDouble() * modulation.insulinImpactFactor
            val carbImpact = if (cobG > 0 && minutesInFuture < CARB_ABSORPTION_MINUTES) {
                ((cobG * csf) / (CARB_ABSORPTION_MINUTES / STEP_MINUTES)) * modulation.carbImpactFactor
            } else {
                0.0
            }

            // Diagnostic only, and deliberately before the clip below: the same subtraction with no
            // bound, so the export can say by how much the path ran past the floor.
            unclippedIob -= insulinImpact
            unclippedInsulinPathMin = min(unclippedInsulinPathMin, unclippedIob)
            val iobBeforeClip = lastIob - insulinImpact
            if (iobBeforeClip < NUMERIC_FLOOR) numericFloorClippedSteps++

            lastIob = iobBeforeClip.coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            lastCob = (lastCob - insulinImpact + carbImpact).coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            lastUam = (lastUam - insulinImpact * iobDampingFactor + uamMomentum)
                .coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            uamMomentum *= momentumDecay

            // Pre-EGP insulin-only path-min (study / raw JSON).
            rawInsulinPathMin = min(rawInsulinPathMin, min(lastIob, min(lastCob, lastUam)))

            val insulinNegligible = abs(insulinImpact) < ENDO_INSULIN_NEGLIGIBLE_MGDL
            if (endoActive && clearHyperContext) {
                // Clear-hyper counter-regulation floor: the insulin-only path may crash to the 39
                // artefact even though a hyperglycemic patient will be counter-regulated well before
                // hypo. Hold the *soft* curves at the baseline anchor (≤ 80 ≤ currentBG, so never
                // optimistic) after the raw path-min has been captured above. Suspended by Guard B on
                // a hard fall (endoActive already gates that).
                val beforeIob = lastIob
                val beforeCob = lastCob
                val beforeUam = lastUam
                lastIob = max(lastIob, endoBaseline)
                lastCob = max(lastCob, endoBaseline)
                lastUam = max(lastUam, endoBaseline)
                if (lastIob > beforeIob + 1e-6 || lastCob > beforeCob + 1e-6 || lastUam > beforeUam + 1e-6) {
                    endoAppliedOnInsulin = true
                }
            } else if (endoActive && insulinNegligible) {
                val beforeIob = lastIob
                val beforeCob = lastCob
                val beforeUam = lastUam
                lastIob = applyEndogenousReversion(lastIob, endoBaseline)
                lastCob = applyEndogenousReversion(lastCob, endoBaseline)
                lastUam = applyEndogenousReversion(lastUam, endoBaseline)
                if (lastIob > beforeIob + 1e-6 || lastCob > beforeCob + 1e-6 || lastUam > beforeUam + 1e-6) {
                    endoAppliedOnInsulin = true
                }
            }
            lastZt = lastIob

            lastHybrid = (lastHybrid - insulinImpact * iobDampingFactor + carbImpact + hybridMomentum)
                .coerceIn(NUMERIC_FLOOR, NUMERIC_CEILING)
            if (endoActive && clearHyperContext) {
                lastHybrid = max(lastHybrid, endoBaseline)
            } else if (endoActive && insulinNegligible) {
                lastHybrid = applyEndogenousReversion(lastHybrid, endoBaseline)
            }
            hybridMomentum *= momentumDecay

            softInsulinPathMin = min(softInsulinPathMin, min(lastIob, min(lastCob, min(lastUam, lastZt))))

            iobSeries.add(lastIob)
            cobSeries.add(lastCob)
            uamSeries.add(lastUam)
            ztSeries.add(lastZt)
            hybridSeries.add(lastHybrid)
        }

        val rawMin = rawInsulinPathMin.takeIf { it.isFinite() }
        val softMin = softInsulinPathMin.takeIf { it.isFinite() }
        return AdvancedPredictionCurves(
            iob = iobSeries,
            cob = cobSeries,
            uam = uamSeries,
            zt = ztSeries,
            hybrid = hybridSeries,
            insulinPathMinRawMgdl = rawMin,
            insulinPathMinSoftMgdl = softMin,
            endogenousReversionOnInsulinCurves = endoAppliedOnInsulin,
            endogenousReversionSuppressedByTrend = endoSuppressedByFallingTrend,
            insulinPathMinUnclippedMgdl = unclippedInsulinPathMin.takeIf { it.isFinite() },
            numericFloorClippedSteps = numericFloorClippedSteps,
            effectiveSensitivityUsedMgdlPerU = effectiveSensitivity.takeIf { it.isFinite() },
        )
    }

    /** Drift [value] toward [baseline] (Guard A cap) when below it; no-op once at/above baseline. */
    private fun applyEndogenousReversion(value: Double, baseline: Double): Double {
        if (!value.isFinite() || value >= baseline) return value
        return (value + (baseline - value) * ENDO_REVERSION_RATE)
            .coerceAtMost(baseline)
    }
}
