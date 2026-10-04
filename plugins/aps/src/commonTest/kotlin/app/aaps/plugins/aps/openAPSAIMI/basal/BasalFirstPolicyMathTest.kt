package app.aaps.plugins.aps.openAPSAIMI.basal

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Basal-First policy: below BG 110 the loop may switch SMB off and use basal only.
 *
 * Same cases as `BasalFirstPolicyMathTest` on `dev_OAPSAIMI` @ `f87d25e02419`
 * (`plugins/aps/src/test/kotlin/app/aaps/plugins/aps/openAPSAIMI/BasalFirstPolicyMathTest.kt`).
 * JUnit assertions are translated to `kotlin.test` so the same inputs run on the JVM target
 * and on `iosSimulatorArm64Test`. The numbers come from the 24 h decision export of 2026-08-13,
 * where the SMB ceiling was zero on the first tick of every meal. Each case names the tick it protects.
 *
 * `assertEquals(expected, actual, delta)` from JUnit accepts a difference up to and including [delta].
 * [assertNear] does the same.
 */
class BasalFirstPolicyMathTest {

    /** Learner factor below 0.75 makes the prudence branch fire; that was the live state in the export. */
    private val prudentLearner = 0.70

    private fun assertNear(expected: Double, actual: Double, delta: Double = 0.001) {
        assertTrue(abs(expected - actual) <= delta, "expected $expected ± $delta but was $actual")
    }

    private fun decide(
        bg: Double,
        delta: Float,
        combinedDelta: Float,
        targetBg: Double,
        mealCob: Double = 0.0,
        autosensRatio: Double = 1.0,
        learnerFactor: Double = prudentLearner,
        isMealAdvisorOneShot: Boolean = false,
        isConfirmedHighRise: Boolean = false,
    ) = BasalFirstPolicyMath.decide(
        bg = bg,
        delta = delta,
        combinedDelta = combinedDelta,
        mealCob = mealCob,
        autosensRatio = autosensRatio,
        learnerFactor = learnerFactor,
        isMealAdvisorOneShot = isMealAdvisorOneShot,
        targetBg = targetBg,
        isConfirmedHighRise = isConfirmedHighRise,
    )

    @Test
    fun fastRiseBelowBg110KeepsSmbAuthority_breakfastTick0806() {
        // 13 Aug 08:06: BG 103.1, delta +6.12, combined +1.56, target 110. Projection 30 min = 139.8.
        val d = decide(bg = 103.1, delta = 6.12f, combinedDelta = 1.56f, targetBg = 110.0)
        assertTrue(d.anticipatedRise, "fast rise projected to 140 must be treated as an anticipated meal")
        assertFalse(d.active, "SMB must stay available on the first tick of the breakfast rise")
        assertEquals(BasalFirstPolicyMath.Reason.NOT_ACTIVE, d.reason)
    }

    @Test
    fun fastRiseBelowBg110KeepsSmbAuthority_lunchTick1316() {
        // 12 Aug 13:16: BG 91.3, delta +6.70, combined +4.48, target 100. Projection 30 min = 131.5.
        val d = decide(bg = 91.3, delta = 6.70f, combinedDelta = 4.48f, targetBg = 100.0)
        assertTrue(d.anticipatedRise)
        assertFalse(d.active, "SMB must stay available on the first tick of the lunch rise")
    }

    @Test
    fun fastRiseKeepsSmbAuthorityEvenWhenBgIsStillBelowTarget() {
        // The old rise exemption needed bg > targetBg, so it was dead during the anticipatory phase.
        val d = decide(bg = 100.0, delta = 8.0f, combinedDelta = 4.0f, targetBg = 115.0)
        assertTrue(d.anticipatedRise, "BG 100 under target 115 with delta +8 must still exempt")
        assertFalse(d.active)
    }

    @Test
    fun fallingBgBelow110StaysBasalFirstWithSmbOff() {
        // 12 Aug 20:41: BG 106.6, delta -10.48, combined -6.28, target 100.
        val d = decide(bg = 106.6, delta = -10.48f, combinedDelta = -6.28f, targetBg = 100.0)
        assertTrue(d.fragileBg)
        assertFalse(d.anticipatedRise, "the rise exemption must be inert while BG falls")
        assertTrue(d.active, "falling BG below 110 must keep SMB off")
        assertEquals(BasalFirstPolicyMath.Reason.FRAGILE_BG, d.reason)
    }

    @Test
    fun slowlyFallingBgBelow110StaysBasalFirstWithSmbOff() {
        // 12 Aug 20:46: BG 107.1, delta -3.98, combined -7.75, target 100.
        val d = decide(bg = 107.1, delta = -3.98f, combinedDelta = -7.75f, targetBg = 100.0)
        assertTrue(d.active)
        assertEquals(BasalFirstPolicyMath.Reason.FRAGILE_BG, d.reason)
    }

    @Test
    fun riseExemptionIsInertForEveryNegativeDelta() {
        var delta = -0.1f
        while (delta > -25.0f) {
            val d = decide(bg = 105.0, delta = delta, combinedDelta = 5.0f, targetBg = 90.0)
            assertFalse(d.anticipatedRise, "delta $delta must never open the ceiling")
            assertTrue(d.active, "delta $delta must stay basal first")
            delta -= 0.5f
        }
    }

    @Test
    fun flatBgBelow110StaysBasalFirstWithSmbOff() {
        // 13 Aug 08:01: BG 93.8, delta +1.77, combined -0.52, target 110. Rise not yet readable.
        val d = decide(bg = 93.8, delta = 1.77f, combinedDelta = -0.52f, targetBg = 110.0)
        assertFalse(d.anticipatedRise, "a slow drift is not a meal")
        assertTrue(d.active)
        assertEquals(BasalFirstPolicyMath.Reason.LEARNER_PRUDENCE, d.reason)
    }

    @Test
    fun exactlyFlatBgBelow110StaysBasalFirstWithSmbOff() {
        val d = decide(bg = 100.0, delta = 0.0f, combinedDelta = 0.0f, targetBg = 100.0)
        assertFalse(d.anticipatedRise)
        assertTrue(d.active)
    }

    @Test
    fun fastRiseOutOfALowStaysBasalFirstBecauseItIsProbablyARebound() {
        // 13 Aug 01:11: BG 79.2, delta +6.29, combined +3.48, target 115. Rebound out of a 55 mg/dL low.
        val d = decide(bg = 79.2, delta = 6.29f, combinedDelta = 3.48f, targetBg = 115.0)
        assertFalse(d.anticipatedRise, "below 90 mg/dL a fast rise must stay basal only")
        assertTrue(d.active)
    }

    @Test
    fun riseWithoutCombinedDeltaCorroborationStaysBasalFirst() {
        val d = decide(bg = 100.0, delta = 6.0f, combinedDelta = 0.0f, targetBg = 100.0)
        assertFalse(d.anticipatedRise, "one noisy tick alone must not open the ceiling")
        assertTrue(d.active)
    }

    @Test
    fun riseNotProjectedClearlyAboveTargetStaysBasalFirst() {
        // BG 95, delta +3.5 projects to 116, which is not more than target 110 plus the 20 mg/dL margin.
        val d = decide(bg = 95.0, delta = 3.5f, combinedDelta = 3.0f, targetBg = 110.0)
        assertNear(116.0, d.projectedBgMgdl)
        assertFalse(d.anticipatedRise)
        assertTrue(d.active)
    }

    @Test
    fun exemptionLeavesCeilingAtConfiguredPreferenceAndNeverRaisesIt() {
        // `applyBasalFirstPolicy` writes the caps in exactly one place: it sets them to 0 when the
        // policy is active. The exemption only skips that write, so the ceiling stays at whatever the
        // preference driven selection produced and can never end up above it.
        val preferenceMaxSmb = 1.8
        var maxSmb = preferenceMaxSmb

        val d = decide(bg = 103.1, delta = 6.12f, combinedDelta = 1.56f, targetBg = 110.0)
        if (d.active) maxSmb = 0.0

        assertFalse(d.active)
        assertNear(preferenceMaxSmb, maxSmb, 0.001)
    }

    @Test
    fun activePolicyStillZeroesTheCeiling() {
        val preferenceMaxSmb = 1.8
        var maxSmb = preferenceMaxSmb

        val d = decide(bg = 106.6, delta = -10.48f, combinedDelta = -6.28f, targetBg = 100.0)
        if (d.active) maxSmb = 0.0

        assertTrue(d.active)
        assertNear(0.0, maxSmb)
    }

    @Test
    fun bgAboveTheBandIsNeverBasalFirst() {
        val d = decide(bg = 110.0, delta = -5.0f, combinedDelta = -5.0f, targetBg = 100.0)
        assertFalse(d.active, "the policy only applies below BG 110")
    }

    @Test
    fun neutralLearnerWithoutFragileBgIsNeverBasalFirst() {
        val d = decide(bg = 100.0, delta = 1.0f, combinedDelta = 1.0f, targetBg = 100.0, learnerFactor = 1.0)
        assertFalse(d.active)
    }

    @Test
    fun autosensResistanceCancelsLearnerPrudence() {
        val d = decide(bg = 100.0, delta = 1.0f, combinedDelta = 1.0f, targetBg = 100.0, autosensRatio = 0.7)
        assertFalse(d.active)
    }

    @Test
    fun declaredCarbsCancelLearnerPrudence() {
        val d = decide(bg = 100.0, delta = 1.0f, combinedDelta = 1.0f, targetBg = 100.0, mealCob = 5.0)
        assertFalse(d.active)
    }

    @Test
    fun heavyMealCancelsFragileBg() {
        val d = decide(bg = 100.0, delta = -5.0f, combinedDelta = -5.0f, targetBg = 100.0, mealCob = 25.0)
        assertTrue(d.fragileBg)
        assertFalse(d.active)
    }

    @Test
    fun persistentRiseAboveTargetCancelsLearnerPrudence() {
        val d = decide(bg = 105.0, delta = 1.0f, combinedDelta = 1.0f, targetBg = 100.0)
        assertFalse(d.active)
    }

    @Test
    fun confirmedHighRiseCancelsBasalFirst() {
        val d = decide(bg = 105.0, delta = -5.0f, combinedDelta = -5.0f, targetBg = 100.0, isConfirmedHighRise = true)
        assertFalse(d.active)
    }

    @Test
    fun mealAdvisorOneShotCancelsBasalFirst() {
        val d = decide(bg = 105.0, delta = -5.0f, combinedDelta = -5.0f, targetBg = 100.0, isMealAdvisorOneShot = true)
        assertFalse(d.active)
    }

    @Test
    fun projectionUsesA30MinuteHorizon() {
        assertNear(130.0, BasalFirstPolicyMath.projectedBg(100.0, 5.0f))
        assertNear(70.0, BasalFirstPolicyMath.projectedBg(100.0, -5.0f))
    }

    @Test
    fun nonFiniteInputsNeverOpenTheCeiling() {
        assertFalse(BasalFirstPolicyMath.isAnticipatedRise(Double.NaN, 6.0f, 6.0f, 100.0))
        assertFalse(BasalFirstPolicyMath.isAnticipatedRise(100.0, Float.NaN, 6.0f, 100.0))
        assertFalse(BasalFirstPolicyMath.isAnticipatedRise(100.0, 6.0f, Float.NaN, 100.0))
        assertFalse(BasalFirstPolicyMath.isAnticipatedRise(100.0, 6.0f, 6.0f, Double.NaN))
    }
}
