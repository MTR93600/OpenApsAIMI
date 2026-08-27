package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt0
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import kotlin.math.max
import kotlin.math.min

/**
 * Decision layer: distinguishes **active correction** (real rise, need insulin now) from
 * **IOB surveillance** (plateau / falling prediction while IOB is already substantial → prefer waiting + TBR).
 *
 * Pure logic — unit-testable, no Android deps.
 */
object InsulinStackingStance {

    /** Above this rise (mg/dL/5m or short-avg), meal context bypasses surveillance so SMB stays aligned with carb absorption. */
    private const val MEAL_RISE_DELTA_BYPASS = 2.0
    private const val MEAL_RISE_SHORTAVG_BYPASS = 2.5
    private const val SECOND_WAVE_DELTA_BYPASS = 1.2
    private const val INTER_WAVE_SMB_MULT = 0.65
    private const val INTER_WAVE_SMB_CAP_U = 0.55

    /**
     * OpenAPS / temp-target artifacts can yield nonsensical eventual BG (e.g. 400+ mg/dL).
     * For **stacking drop detection only**, treat those as unknown so they do not distort surveillance.
     */
    fun sanitizeEventualMgdlForStackingSignals(bg: Double, eventualBg: Double?): Double? {
        val e = eventualBg?.takeIf { it.isFinite() } ?: return null
        if (e > 300.0 && e > bg + 80.0) return null
        return e
    }

    enum class Kind {
        /** SMB pipeline proceeds normally; no stacking-specific dampening. */
        CORRECTION_ACTIVE,

        /**
         * High stacking risk: predictions show imminent drop or eventual well below current BG,
         * velocity is no longer a sharp rise, and IOB is already meaningful.
         */
        SURVEILLANCE_IOB
    }

    data class Evaluation(
        val kind: Kind,
        /** Applied to SMB after PKPD throttle (multiplicative). */
        val smbMultiplier: Double,
        /** Hard ceiling (U) on SMB for this tick when [Kind.SURVEILLANCE_IOB]. */
        val smbAbsoluteCapU: Double,
        /** When true, Red-Carpet must not restore bolus toward raw proposed. */
        val suppressRedCarpetRestore: Boolean,
        /** Multiply existing pkpdPreferTbrBoost floor (e.g. 1.12 = +12% TBR bias). */
        val tbrBoostFloor: Double,
        /** Structured log / export fragment (ASCII). */
        val summary: String,
        /**
         * When [Kind.CORRECTION_ACTIVE], optional machine reason for analysts (JSON / tuning).
         * Null for surveillance or default active path without a specific tag.
         */
        val activeReason: String? = null
    ) {
        companion object {
            val ACTIVE_DEFAULT = Evaluation(
                kind = Kind.CORRECTION_ACTIVE,
                smbMultiplier = 1.0,
                smbAbsoluteCapU = Double.MAX_VALUE,
                suppressRedCarpetRestore = false,
                tbrBoostFloor = 1.0,
                summary = "",
                activeReason = null
            )
        }
    }

    fun iobFloorU(maxIob: Double): Double {
        val maxIobSafe = maxIob.coerceAtLeast(0.5)
        return max(3.2, maxIobSafe * 0.26)
    }

    /**
     * Static text for JSONL / support: what to change in code when tuning this layer.
     */
    fun tuningReferenceAscii(): String =
        "Pref: BooleanKey.OApsAIMIIobSurveillanceGuard (key_aimi_iob_surveillance_guard). " +
            "Logic: InsulinStackingStance.kt. IOB floor=max(3.2,0.26*maxIob). " +
            "Plateau: delta<=2.25 AND shortAvgDelta<=4. Sharp-rise escape: delta/shortAvg>=4.5 OR dual gate (3.2/3.0). " +
            "BG band: need bg>=target+18; extreme hyper escape bg>target+85 with upward delta. " +
            "Signals: eventual<bg-6 OR minPred<bg-10 OR trajEnergy>2. SMB damp: mult=0.32 cap=0.38U redCarpet=off TBR floor>=1.12. " +
            "Meal alignment: if mealPriorityContext AND (delta>=${MEAL_RISE_DELTA_BYPASS} OR shortAvg>=${MEAL_RISE_SHORTAVG_BYPASS}), surveillance off (absorption rise). " +
            "JSONL: smb_u_after_cap_smb_dose, smb_u_final_for_delivery (pump-aligned), smb_final_source (red_carpet|standard_safe_cap)."

    /**
     * @param trajectoryEnergy optional phase-space energy; values > 2 suggest stacking (TrajectoryGuard).
     * @param mealPriorityContext true when AIMI meal-priority branch is active (COB / UAM / meal mode); cleared for surveillance only if rise is strong enough to suggest active absorption.
     */
    @Suppress("LongParameterList")
    fun evaluate(
        bg: Double,
        delta: Double,
        shortAvgDelta: Double,
        targetBg: Double,
        iob: Double,
        maxIob: Double,
        eventualBg: Double?,
        minPredBg: Double?,
        trajectoryEnergy: Double?,
        isExplicitUserAction: Boolean,
        enabled: Boolean,
        mealPriorityContext: Boolean = false,
        endogenousCounterRegulatory: Boolean = false,
        mealAbsorptionPhase: MealAbsorptionPhase = MealAbsorptionPhase.NONE,
    ): Evaluation {
        fun active(reason: String?) = Evaluation(
            kind = Kind.CORRECTION_ACTIVE,
            smbMultiplier = 1.0,
            smbAbsoluteCapU = Double.MAX_VALUE,
            suppressRedCarpetRestore = false,
            tbrBoostFloor = 1.0,
            summary = "",
            activeReason = reason
        )
        if (!enabled || isExplicitUserAction || !bg.isFinite() || !targetBg.isFinite()) {
            return active("disabled_explicit_or_invalid_input")
        }
        if (!endogenousCounterRegulatory && mealAbsorptionPhase.bypassesIobSurveillance) {
            return active("meal_absorption_${mealAbsorptionPhase.name.lowercase()}")
        }
        if (mealPriorityContext &&
            !endogenousCounterRegulatory &&
            (
                delta >= MEAL_RISE_DELTA_BYPASS ||
                    shortAvgDelta >= MEAL_RISE_SHORTAVG_BYPASS ||
                    (
                        mealAbsorptionPhase == MealAbsorptionPhase.SECOND_WAVE &&
                            delta >= SECOND_WAVE_DELTA_BYPASS
                        )
                )
        ) {
            return active("meal_absorption_rise_priority")
        }
        val iobSafe = iob.coerceAtLeast(0.0)
        val maxIobSafe = maxIob.coerceAtLeast(0.5)
        val iobFloor = if (endogenousCounterRegulatory) {
            max(1.0, iobFloorU(maxIob) * 0.35)
        } else {
            iobFloorU(maxIob)
        }
        if (iobSafe < iobFloor) {
            return active("iob_below_floor")
        }

        // Clear acceleration → still in correction phase (do not patienter).
        if (delta >= 4.5 || shortAvgDelta >= 4.5) {
            return active("sharp_rise_escape")
        }
        if (delta >= 3.2 && shortAvgDelta >= 3.0 && bg < targetBg + 95) {
            return active("dual_rise_gate")
        }

        // Extreme hyper with continued upward pressure — keep correction authority.
        if (bg > targetBg + 85 && (delta > 0.8 || shortAvgDelta > 1.2)) {
            return active("extreme_hyper_upward_pressure")
        }

        if (bg < targetBg + 18) {
            return active("bg_below_surveillance_band")
        }

        val plateauVelocity =
            delta <= 2.25 && shortAvgDelta <= 4.0

        if (!plateauVelocity) {
            return active("not_plateau_velocity")
        }

        val ev = sanitizeEventualMgdlForStackingSignals(bg, eventualBg)
        val mn = minPredBg?.takeIf { it.isFinite() }

        val eventualSignalsDrop = ev != null && ev < bg - 6.0
        val minPredSignalsDrop = mn != null && mn < bg - 10.0
        val trajectorySignalsStack = trajectoryEnergy != null && trajectoryEnergy.isFinite() && trajectoryEnergy > 2.0

        if (!eventualSignalsDrop && !minPredSignalsDrop && !trajectorySignalsStack) {
            return active("no_stacking_prediction_signal")
        }

        val mult = if (mealAbsorptionPhase.attenuatesIobSurveillance) INTER_WAVE_SMB_MULT else 0.32
        val cap = if (mealAbsorptionPhase.attenuatesIobSurveillance) INTER_WAVE_SMB_CAP_U else 0.38
        val summary = buildString {
            append("SURVEILLANCE_IOB | ")
            append("risk=stacked_IOB+predicted_drop | ")
            append("iob=${aimiFmt2(iobSafe)}U floor=${aimiFmt2(iobFloor)} | ")
            append("Δ=${aimiFmt1(delta)} sΔ=${aimiFmt1(shortAvgDelta)} plateau_ok | ")
            if (ev != null) append("ev=${aimiFmt0(ev)} ")
            if (mn != null) append("minPred=${aimiFmt0(mn)} ")
            if (trajectoryEnergy != null) append("trajE=${aimiFmt2(trajectoryEnergy)} ")
            append("| action=smb×${mult} cap${cap}U TBR_bias≥12% no_RedCarpet_restore | ")
            append("analyst_tune=AIMI_Decisions.jsonl#iob_surveillance.tuning_reference")
        }

        return Evaluation(
            kind = Kind.SURVEILLANCE_IOB,
            smbMultiplier = mult,
            smbAbsoluteCapU = cap,
            suppressRedCarpetRestore = true,
            tbrBoostFloor = 1.12,
            summary = summary,
            activeReason = null
        )
    }
}
