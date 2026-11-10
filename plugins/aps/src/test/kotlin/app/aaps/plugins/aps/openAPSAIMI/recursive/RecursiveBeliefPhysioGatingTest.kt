package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.BehavioralRiskPolicy
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapKind
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternReading
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RecursiveBeliefPhysioGatingTest {

    @BeforeEach
    fun resetMemory() {
        RecursiveBeliefMemory.clearForTests()
        RbtEpisodeMemory.clearForTests()
    }

    @Test
    fun wavelet_urgency_boost_suppressed_under_physio_cap() {
        val bands = WaveletBelief.Bands(high = 8.0, mid = 6.0, low = 4.0)
        val baseCtx = RecursiveBeliefMr7TestHelper.minimalCtx()
        val plain = baseCtx.copy(waveletBands = bands)
        val gated = baseCtx.copy(
            waveletBands = bands,
            behavioralRisk = BehavioralRiskPolicy.forPhase(
                PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL,
                0.85,
                "test",
            ),
        )
        val uPlain = RecursiveBeliefEngine.deviate(15, plain, 160.0, 0.7)
        val uGated = RecursiveBeliefEngine.deviate(15, gated, 160.0, 0.7)
        assertThat(uGated).isLessThan(uPlain)
    }

    @Test
    fun physio_hormonal_suppresses_hyper_vs_clearance_and_caps_v3() {
        val hormonal = BehavioralRiskPolicy.forPhase(
            PhysiologicalPhase.MALE_CIRCADIAN_HORMONAL,
            0.85,
            "morning cortisol",
        )
        val scales = listOf(
            scale(15, belief = 0.82, urgency = 1.8, terminal = 260.0),
            scale(60, belief = 0.76, urgency = 2.4, terminal = 401.0),
            scale(180, belief = 0.44, urgency = -0.3, terminal = 118.0),
        )
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            v3Smb = 2.5,
            replaceHtrRelease = true,
            behavioralRisk = hormonal,
        ).copy(
            v3SmbU = 2.5,
            stackingStance = InsulinStackingStance.Evaluation(
                kind = InsulinStackingStance.Kind.SURVEILLANCE_IOB,
                smbMultiplier = 0.7,
                smbAbsoluteCapU = 0.5,
                suppressRedCarpetRestore = true,
                tbrBoostFloor = 1.1,
                summary = "test stacking",
            ),
        )
        val snapshot = RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(ctx = ctx, scales = scales, authorityEnabled = true),
        )
        val hyperParadox = snapshot.paradoxes.first { it.id == BeliefParadoxId.HYPER_VS_CLEARANCE }
        assertThat(hyperParadox.suppressed).isTrue()
        assertThat(snapshot.resolutions.releaseAuthority).isEqualTo(ReleaseAuthority.NONE)
        assertThat(snapshot.resolutions.reasonCodes).contains("PHYSIO_RISK_CAP")
        assertThat(snapshot.resolutions.smbDemandU).isAtMost(0.55)
    }

    @Test
    fun uam_multi_hypothesis_downgrades_false_meal_release() {
        val scales = listOf(
            scale(15, belief = 0.82, urgency = 1.8, terminal = 250.0),
            scale(60, belief = 0.74, urgency = 1.4, terminal = 280.0),
            scale(180, belief = 0.30, urgency = -0.2, terminal = 118.0),
        )
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            v3Smb = 1.4,
            replaceHtrRelease = true,
            behavioralRisk = null,
            extended = RbtExtendedSignals(
                latentMealProb = 0.38,
                latentEndogenousGlucoseDrive = 0.84,
                uamHypothesisDominant = "DAWN_ENDOGENOUS",
                uamMealProb = 0.32,
                uamEndogenousProb = 0.84,
                uamStressProb = 0.14,
                uamPostHypoProb = 0.10,
                uamSuppressMealInterpretation = true,
            ),
        )
        val snapshot = RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(ctx = ctx, scales = scales, authorityEnabled = true),
        )

        assertThat(snapshot.resolutions.releaseAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(snapshot.resolutions.mealChannel).isEqualTo(MealChannelHint.SUPPRESS)
        assertThat(snapshot.resolutions.reasonCodes).contains("UAM_ALT_DAWN_ENDOGENOUS")
        assertThat(snapshot.resolutions.reasonCodes).doesNotContain("FIRST_WAVE")
    }

    @Test
    fun meal_dominant_pattern_only_hyper_cap_is_softened_in_strong_fast_meal_context() {
        val scales = listOf(
            scale(15, belief = 0.84, urgency = 1.9, terminal = 258.0),
            scale(60, belief = 0.80, urgency = 2.3, terminal = 338.0),
            scale(180, belief = 0.30, urgency = -0.1, terminal = 122.0),
        )
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            v3Smb = 1.6,
            replaceHtrRelease = true,
            behavioralRisk = null,
            extended = RbtExtendedSignals(
                latentMealProb = 0.84,
                uamMealProb = 0.82,
                causalMealConfidence = 0.80,
                causalProtectiveConfidence = 0.22,
                patientMode = "FAST_MEAL",
                patientModeMealBias = 0.90,
                patientModeProtectionBias = 0.18,
                uamSuppressMealInterpretation = false,
            ),
        ).copy(
            physiologicalPatterns = PhysiologicalPatternSnapshot(
                active = listOf(
                    PhysiologicalPatternReading(
                        id = PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
                        confidence = 0.82,
                        reason = "meal rise",
                    ),
                    PhysiologicalPatternReading(
                        id = PhysiologicalPatternId.SLEEP_DEBT,
                        confidence = 0.40,
                        reason = "residual recovery",
                    ),
                ),
                dominant = PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
                dominantConfidence = 0.82,
                suppressMealInterpretation = false,
                suppressHyperRelease = true,
                suppressWaveletBoost = false,
                smbCapU = 1.2,
                reasonSummary = "meal dominant with residual recovery",
            ),
        )

        val snapshot = RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(ctx = ctx, scales = scales, authorityEnabled = true),
        )

        assertThat(snapshot.resolutions.releaseAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(snapshot.resolutions.reasonCodes).contains("PHYSIO_PATTERN_SOFT_CAP")
        assertThat(snapshot.resolutions.reasonCodes).doesNotContain("PHYSIO_RISK_CAP")
    }

    @Test
    fun first_wave_boost_not_muted_by_soft_meal_proposal() {
        val scales = listOf(
            scale(15, belief = 0.84, urgency = 1.9, terminal = 258.0),
            scale(60, belief = 0.80, urgency = 2.3, terminal = 338.0),
            scale(180, belief = 0.30, urgency = -0.1, terminal = 122.0),
        )
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            v3Smb = 1.6,
            replaceHtrRelease = true,
            delta = 18.0,
            extended = RbtExtendedSignals(
                latentMealProb = 0.84,
                uamMealProb = 0.82,
                causalMealConfidence = 0.80,
                insulinIntent = "NEED_MORE_INSULIN",
                mealCertaintySupports = true,
                riseConfirmed = true,
            ),
        ).copy(
            mealAbsorption = RecursiveBeliefMr7TestHelper.minimalCtx(delta = 18.0).mealAbsorption?.copy(
                phase = MealAbsorptionPhase.FIRST_WAVE,
                mealDeliveryPriority = true,
                belief = 1.0,
            ),
            physiologicalPatterns = PhysiologicalPatternSnapshot(
                active = listOf(
                    PhysiologicalPatternReading(
                        id = PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
                        confidence = 0.88,
                        reason = "meal rise",
                    ),
                ),
                dominant = PhysiologicalPatternId.MEAL_UNDECLARED_FAST,
                dominantConfidence = 0.88,
                suppressMealInterpretation = false,
                suppressHyperRelease = false,
                suppressWaveletBoost = false,
                smbCapU = 1.20,
                smbCapKind = PatternCapKind.SOFT,
                reasonSummary = "soft meal proposal",
            ),
        )

        val snapshot = RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(ctx = ctx, scales = scales, authorityEnabled = true),
        )

        assertThat(snapshot.resolutions.reasonCodes).contains("FIRST_WAVE")
        assertThat(snapshot.resolutions.reasonCodes).doesNotContain("PATTERN_SMB_CAP_HARD")
        assertThat(snapshot.resolutions.smbDemandU).isGreaterThan(1.15)
    }

    private fun scale(tau: Int, belief: Double, urgency: Double, terminal: Double) =
        BeliefScaleNode(
            horizonMinutes = tau,
            belief = belief,
            terminalMgdl = terminal,
            urgency = urgency,
            leaves = emptyList(),
        )
}
