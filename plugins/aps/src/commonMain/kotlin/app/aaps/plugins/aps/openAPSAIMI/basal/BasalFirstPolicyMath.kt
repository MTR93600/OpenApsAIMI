package app.aaps.plugins.aps.openAPSAIMI.basal

/**
 * Pure decision math of the Basal-First policy (SMB ceiling switched off, basal only).
 *
 * Extracted from `DetermineBasalaimiSMB2.applyBasalFirstPolicy` so it can be unit tested without
 * building a whole loop tick. The wrapper in the plugin only adds logging and writes the caps.
 *
 * The policy answers one question: below [BASAL_FIRST_BG_CEILING_MGDL], may the loop still use SMB?
 * Two protective branches say no (fragile BG, prudent learner) and several exemptions say yes.
 * [isAnticipatedRise] is the exemption that lets the loop meet a meal that starts from a normal BG:
 * it is driven by the rise rate and by the 30 min projection, never by the BG level alone, and it is
 * inert while BG falls because it needs `delta > 0` while the fragile branch needs `delta < 0`.
 */
internal object BasalFirstPolicyMath {

    /** Above this BG the policy never switches SMB off. */
    const val BASAL_FIRST_BG_CEILING_MGDL = 110.0

    /** Lowest BG at which an anticipated rise may keep the SMB ceiling open (mg/dL). */
    const val ANTICIPATED_RISE_MIN_BG_MGDL = 90.0

    /** Smallest measured 5 min rise that counts as fast (mg/dL per 5 min, i.e. 36 mg/dL per hour). */
    const val ANTICIPATED_RISE_MIN_DELTA = 3.0f

    /** Smallest combined (measured + predicted) 5 min rise that corroborates the fast rise. */
    const val ANTICIPATED_RISE_MIN_COMBINED_DELTA = 0.3f

    /** Projection horizon in CGM steps: 6 steps of 5 min = 30 min. */
    const val ANTICIPATED_RISE_HORIZON_STEPS = 6.0f

    /** How far above target the 30 min projection must land before the ceiling stays open (mg/dL). */
    const val ANTICIPATED_RISE_TARGET_MARGIN_MGDL = 20.0

    /** Why the policy switched SMB off, or why it did not. */
    enum class Reason {
        NOT_ACTIVE,
        FRAGILE_BG,
        LEARNER_PRUDENCE,
        UNKNOWN,
    }

    /** Result of one policy evaluation. [active] true means "SMB off, basal only". */
    data class Decision(
        val active: Boolean,
        val fragileBg: Boolean,
        val anticipatedRise: Boolean,
        val projectedBgMgdl: Double,
        val reason: Reason,
    )

    /** Straight line projection of BG over [ANTICIPATED_RISE_HORIZON_STEPS] using the current rise rate. */
    fun projectedBg(bg: Double, delta: Float): Double = bg + delta * ANTICIPATED_RISE_HORIZON_STEPS

    /**
     * True when the current rise is fast enough, corroborated by the combined delta, and projected to
     * land clearly above target within 30 min. This is the meal anticipation exemption.
     *
     * Needs `delta > 0`, so it can never fire while BG falls, and never cancels the fragile branch.
     */
    fun isAnticipatedRise(bg: Double, delta: Float, combinedDelta: Float, targetBg: Double): Boolean {
        if (!bg.isFinite() || !targetBg.isFinite() || !delta.isFinite() || !combinedDelta.isFinite()) return false
        // Falling or flat BG: nothing to anticipate, stay protected.
        if (delta <= 0f) return false
        if (delta < ANTICIPATED_RISE_MIN_DELTA) return false
        // The smoothed trend must agree with the single tick, so one noisy sample cannot open the ceiling.
        if (combinedDelta < ANTICIPATED_RISE_MIN_COMBINED_DELTA) return false
        // Below this BG a fast rise is usually a rebound out of a low, so keep it basal only.
        if (bg < ANTICIPATED_RISE_MIN_BG_MGDL) return false
        return projectedBg(bg, delta) > targetBg + ANTICIPATED_RISE_TARGET_MARGIN_MGDL
    }

    /**
     * Decide whether the Basal-First policy is active for this tick.
     *
     * @param learnerFactor reactivity factor of the unified learner (1.0 means neutral).
     * @param autosensRatio autosens ratio; below 0.8 means resistance, which cancels learner prudence.
     */
    fun decide(
        bg: Double,
        delta: Float,
        combinedDelta: Float,
        mealCob: Double,
        autosensRatio: Double,
        learnerFactor: Double,
        isMealAdvisorOneShot: Boolean,
        targetBg: Double,
        isConfirmedHighRise: Boolean,
    ): Decision {
        val fragileBg = bg < BASAL_FIRST_BG_CEILING_MGDL && delta < 0.0f
        val autosensResistance = autosensRatio < 0.8
        val learnerPrudent = learnerFactor < 0.75 && !autosensResistance
        val mealActive = mealCob > 0.1
        val heavyMeal = mealCob > 20.0
        val persistentRise = bg > targetBg && combinedDelta >= 0.3f
        // Mutually exclusive with fragileBg by the sign of delta; the extra guard makes that explicit.
        val anticipatedRise = !fragileBg && isAnticipatedRise(bg, delta, combinedDelta, targetBg)

        val active = (((learnerPrudent && !mealActive && !persistentRise) || (fragileBg && !heavyMeal)) && !isMealAdvisorOneShot) &&
            !isConfirmedHighRise &&
            !anticipatedRise &&
            bg < BASAL_FIRST_BG_CEILING_MGDL

        val reason = when {
            !active         -> Reason.NOT_ACTIVE
            fragileBg       -> Reason.FRAGILE_BG
            learnerPrudent  -> Reason.LEARNER_PRUDENCE
            else            -> Reason.UNKNOWN
        }
        return Decision(
            active = active,
            fragileBg = fragileBg,
            anticipatedRise = anticipatedRise,
            projectedBgMgdl = projectedBg(bg, delta),
            reason = reason,
        )
    }
}
