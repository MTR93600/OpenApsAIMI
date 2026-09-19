package app.aaps.plugins.aps.openAPSAIMI.safety

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

    /** Lowest the floor may go, whatever the user's own dosing scale is. */
    const val IOB_FLOOR_MIN_U = 1.0

    /** Fraction of the user's own `maxIob` that counts as "enough insulin on board to watch". */
    const val IOB_FLOOR_MAX_IOB_FRACTION = 0.26

    /**
     * Insulin on board above which this layer starts watching for stacking.
     *
     * It used to be `max(3.2, maxIob * 0.26)`: a fixed 3.2 U, the same for someone using 20 units a
     * day and someone using 80, and in practice 3.2 always won, because `maxIob * 0.26` only passes
     * it above a `maxIob` of 12.3 U. Everything below 3.2 U of IOB was therefore delivered with no
     * stacking brake at all — which is exactly the band a user sat in when a stress episode stacked
     * more than 2 U on top of 2 U already active (field report 2026-09-18).
     *
     * The floor now follows the user's own scale. With a `maxIob` of 7 U it is 1.8 U instead of 3.2.
     *
     * ⚠️ `MealAbsorptionPhaseEngine` has a copy of the OLD formula on purpose. There the same number
     * means the opposite thing — it is a condition for the PEAK_CORRECTION phase, which SWITCHES OFF
     * this surveillance — so lowering it there would remove protection instead of adding it. The two
     * must not be changed together again.
     */
    fun iobFloorU(maxIob: Double): Double {
        val maxIobSafe = maxIob.coerceAtLeast(0.5)
        return max(IOB_FLOOR_MIN_U, maxIobSafe * IOB_FLOOR_MAX_IOB_FRACTION)
    }

    /** Glucose band where a rise is acceptable but must not be answered at full strength. */
    const val CAUTION_BAND_MIN_MGDL = 70.0
    const val CAUTION_BAND_MAX_MGDL = 130.0

    /**
     * Static text for JSONL / support: what to change in code when tuning this layer.
     */
    fun tuningReferenceAscii(): String =
        "Pref: BooleanKey.OApsAIMIIobSurveillanceGuard (key_aimi_iob_surveillance_guard). " +
            "Logic: InsulinStackingStance.kt. IOB floor=max(3.2,0.26*maxIob). " +
            "Plateau: delta<=2.25 AND shortAvgDelta<=4. Sharp-rise escape: delta/shortAvg>=4.5 OR dual gate (3.2/3.0). " +
            "BG band: need bg>=target+18, or bg in [${CAUTION_BAND_MIN_MGDL}, ${CAUTION_BAND_MAX_MGDL}] with no meal mode / priority / phase; " +
            "extreme hyper escape bg>target+85 with upward delta. " +
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
        mealModeActive: Boolean = false,
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

        // Between 70 and 130 a rise is not an emergency, and answering it at full strength is how a
        // hypo is built. So when nothing says "meal" — no meal mode, no meal priority, no absorption
        // phase — the surveillance may also work below the usual target+18 band. It still needs
        // everything else: a plateau velocity AND a prediction that glucose is coming down. A real
        // rise escapes above, long before this point.
        val cautionBand = bg >= CAUTION_BAND_MIN_MGDL &&
            bg <= CAUTION_BAND_MAX_MGDL &&
            !mealModeActive &&
            !mealPriorityContext &&
            !mealAbsorptionPhase.isActive
        if (bg < targetBg + 18 && !cautionBand) {
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
            append("iob=${"%.2f".format(iobSafe)}U floor=${"%.2f".format(iobFloor)} | ")
            append("Δ=${"%.1f".format(delta)} sΔ=${"%.1f".format(shortAvgDelta)} plateau_ok | ")
            if (ev != null) append("ev=${"%.0f".format(ev)} ")
            if (mn != null) append("minPred=${"%.0f".format(mn)} ")
            if (trajectoryEnergy != null) append("trajE=${"%.2f".format(trajectoryEnergy)} ")
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
