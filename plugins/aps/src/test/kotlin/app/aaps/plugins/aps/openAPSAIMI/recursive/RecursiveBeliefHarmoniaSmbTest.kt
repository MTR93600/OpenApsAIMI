package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapKind
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PatternCapProposal
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternId
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternReading
import app.aaps.plugins.aps.openAPSAIMI.physio.pattern.PhysiologicalPatternSnapshot
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecursiveBeliefHarmoniaSmbTest {

    @Test
    fun resolve_blocksHarmoniaSmb_whenRbtHasNoSmbAuthority() {
        val snapshot = resolveWith(
            ext = harmoniaMealSupport(),
            releaseScales = false,
        )

        assertThat(snapshot.resolutions.releaseAuthority).isEqualTo(ReleaseAuthority.NONE)
        assertThat(snapshot.resolutions.smbDemandU).isEqualTo(0.0)
        assertThat(snapshot.resolutions.harmoniaSmb?.eligible).isFalse()
        // The authority is NONE here, so that is what blocks the SMB: `eligible` fails on it before
        // the basal-first owner is ever looked at. The blocker must name the binding term. The
        // basal-first owner is still reported, but as a reason code, not as the blocker.
        assertThat(snapshot.resolutions.harmoniaSmb?.dominantBlocker)
            .isEqualTo("NO_RBT_SMB_AUTHORITY")
        assertThat(snapshot.resolutions.harmoniaSmb?.reasonCodes)
            .contains("HARMONIA_SMB_BASAL_FIRST_OWNER")
    }

    @Test
    fun resolve_liftsRbtSmbDemand_whenHarmoniaMealSupportIsSafeAndAuthorityExists() {
        val snapshot = resolveWith(
            ext = harmoniaMealSupport(targetSmbU = 1.8, maxSmbU = 3.0),
            releaseScales = true,
        )

        val harmonia = snapshot.resolutions.harmoniaSmb
        assertThat(snapshot.resolutions.releaseAuthority).isNotEqualTo(ReleaseAuthority.NONE)
        assertThat(harmonia?.eligible).isTrue()
        assertThat(harmonia?.appliedToRbtDemand).isTrue()
        assertThat(harmonia?.demandAfterU).isAtLeast(1.8)
        assertThat(harmonia?.reasonCodes).contains("HARMONIA_SMB_APPLIED_TO_RBT")
        assertThat(snapshot.resolutions.reasonCodes).contains("HARMONIA_SMB_SUPPORT")
    }

    @Test
    fun resolve_blocksHarmoniaSmb_whenPostHypoGuardIsActive() {
        val snapshot = resolveWith(
            ext = harmoniaMealSupport(postHypoBlock = true),
            releaseScales = true,
        )

        assertThat(snapshot.resolutions.harmoniaSmb?.eligible).isFalse()
        assertThat(snapshot.resolutions.harmoniaSmb?.dominantBlocker).isEqualTo("POST_HYPO")
        assertThat(snapshot.resolutions.harmoniaSmb?.reasonCodes)
            .contains("HARMONIA_SMB_POST_HYPO_BLOCK")
    }

    @Test
    fun export_containsHarmoniaSmbDecisionDetails() {
        val snapshot = resolveWith(
            ext = harmoniaMealSupport(targetSmbU = 1.5, maxSmbU = 2.0),
            releaseScales = true,
        )
        val export = UnfoldExporter.toJsonObject(
            UnfoldExporter.toExport(
                snapshot = snapshot,
                shadowOnly = false,
                authorityApplied = true,
            ),
        )
        val harmonia = export
            .getJSONObject("resolution")
            .getJSONObject("harmonia_smb")

        assertThat(harmonia.getBoolean("active")).isTrue()
        assertThat(harmonia.getBoolean("eligible")).isTrue()
        assertThat(harmonia.getBoolean("applied_to_rbt_demand")).isTrue()
        assertThat(harmonia.getDouble("bounded_smb_u")).isAtMost(2.0)
        assertThat(harmonia.getJSONArray("reason_codes").toString())
            .contains("HARMONIA_SMB_SUPPORT_READY")
    }

    @Test
    fun resolve_liftsSoftMealCap_whenRiseConfirmedAndMpcAboveProposal() {
        val softMeal = PhysiologicalPatternSnapshot(
            active = listOf(
                PhysiologicalPatternReading(
                    PhysiologicalPatternId.MEAL_FIRST_WAVE,
                    0.85,
                    "first_wave",
                ),
            ),
            dominant = PhysiologicalPatternId.MEAL_FIRST_WAVE,
            dominantConfidence = 0.85,
            suppressMealInterpretation = false,
            suppressHyperRelease = false,
            suppressWaveletBoost = false,
            smbCapU = 1.20,
            smbCapKind = PatternCapKind.SOFT,
            mealPatternCap = PatternCapProposal(
                proposedCapU = 1.20,
                kind = PatternCapKind.SOFT,
                sourceId = PhysiologicalPatternId.MEAL_FIRST_WAVE,
            ),
            reasonSummary = "MEAL_FIRST_WAVE@0.85",
        )
        val snapshot = resolveWith(
            ext = harmoniaMealSupport(
                targetSmbU = 0.66,
                maxSmbU = 2.2,
            ).copy(
                insulinIntent = "NEED_MORE_INSULIN",
                mealCertaintySupports = true,
                riseConfirmed = true,
            ),
            releaseScales = true,
            v3SmbU = 2.2,
            physiologicalPatterns = softMeal,
            maxSmbEffectiveU = 2.2,
        )

        assertThat(snapshot.resolutions.smbDemandU).isAtLeast(2.0)
        assertThat(snapshot.resolutions.harmoniaSmb?.authorityMode)
            .isEqualTo("LIFT_WITHIN_ENVELOPE")
        assertThat(snapshot.resolutions.harmoniaSmb?.addsSmbAuthority).isTrue()
        assertThat(snapshot.resolutions.reasonCodes).contains("HARMONIA_SMB_LIFT_WITHIN_ENVELOPE")
    }

    @Test
    fun resolve_hardProtectivePattern_stillBindsBelowMpc() {
        val hardProtective = PhysiologicalPatternSnapshot(
            active = listOf(
                PhysiologicalPatternReading(
                    PhysiologicalPatternId.IOB_STACKING_SURVEILLANCE,
                    0.90,
                    "stacking",
                ),
            ),
            dominant = PhysiologicalPatternId.IOB_STACKING_SURVEILLANCE,
            dominantConfidence = 0.90,
            suppressMealInterpretation = false,
            suppressHyperRelease = false,
            suppressWaveletBoost = false,
            smbCapU = 0.50,
            smbCapKind = PatternCapKind.HARD,
            reasonSummary = "IOB_STACKING_SURVEILLANCE@0.90",
        )
        val snapshot = resolveWith(
            ext = harmoniaMealSupport(targetSmbU = 1.8, maxSmbU = 2.2).copy(
                insulinIntent = "NEED_MORE_INSULIN",
                mealCertaintySupports = true,
                riseConfirmed = true,
            ),
            releaseScales = true,
            v3SmbU = 2.2,
            physiologicalPatterns = hardProtective,
            maxSmbEffectiveU = 2.2,
        )

        assertThat(snapshot.resolutions.smbDemandU).isAtMost(0.50 + 1e-6)
        assertThat(snapshot.resolutions.reasonCodes).contains("PATTERN_SMB_CAP_HARD")
        assertThat(snapshot.resolutions.harmoniaSmb?.authorityMode)
            .isNotEqualTo("LIFT_WITHIN_ENVELOPE")
    }

    private fun harmoniaMealSupport(
        targetSmbU: Double = 1.6,
        maxSmbU: Double = 3.0,
        postHypoBlock: Boolean = false,
    ): RbtExtendedSignals =
        RbtExtendedSignals(
            harmoniaActive = true,
            harmoniaDecisionEligible = true,
            harmoniaAction = "MEAL_SUPPORT",
            harmoniaBranch = "MEAL_RISE",
            harmoniaBasalDemandRateUph = 1.6,
            harmoniaBasalMaxRateUph = 5.0,
            harmoniaSmbDemandU = targetSmbU,
            harmoniaSmbMaxU = maxSmbU,
            harmoniaPostHypoBlock = postHypoBlock,
            harmoniaBlockReason = if (postHypoBlock) "POST_HYPO" else null,
            insulinIntent = "MEAL_SUPPORT",
            mealCertaintySupports = true,
            riseConfirmed = true,
        )

    private fun resolveWith(
        ext: RbtExtendedSignals,
        releaseScales: Boolean,
        v3SmbU: Double? = null,
        physiologicalPatterns: PhysiologicalPatternSnapshot? = null,
        maxSmbEffectiveU: Double = 3.0,
    ): RecursiveBeliefSnapshot {
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            v3Smb = v3SmbU ?: 0.0,
            bg = 226.0,
            delta = if (releaseScales) 5.0 else 0.5,
            iob = 1.0,
            maxIob = 12.0,
            replaceHtrRelease = false,
            hypoMinPredIgnored = false,
            extended = ext,
        ).copy(
            maxSmbEffectiveU = maxSmbEffectiveU,
            physiologicalPatterns = physiologicalPatterns,
        )
        val scales = if (releaseScales) {
            listOf(
                beliefScale(15, belief = 0.72, urgency = 0.75, terminal = 245.0),
                beliefScale(60, belief = 0.80, urgency = 1.25, terminal = 285.0),
                beliefScale(180, belief = 0.40, urgency = 0.10, terminal = 180.0),
            )
        } else {
            listOf(
                beliefScale(15, belief = 0.45, urgency = 0.10, terminal = 135.0),
                beliefScale(60, belief = 0.50, urgency = 0.10, terminal = 145.0),
                beliefScale(180, belief = 0.35, urgency = 0.00, terminal = 125.0),
            )
        }
        return RecursiveBeliefResolver.resolve(
            RecursiveBeliefResolver.Input(
                ctx = ctx,
                scales = scales,
                authorityEnabled = true,
            ),
        )
    }

    private fun beliefScale(tau: Int, belief: Double, urgency: Double, terminal: Double) =
        BeliefScaleNode(
            horizonMinutes = tau,
            belief = belief,
            terminalMgdl = terminal,
            urgency = urgency,
            leaves = emptyList(),
        )
}
