package app.aaps.plugins.aps.openAPSAIMI.recursive

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class RecursiveBeliefHarmoniaBasalFirstTest {

    @Test
    fun resolve_selectsHarmoniaBasalFirst_whenEligibleAndT3cAbsent() {
        val snapshot = resolveWith(
            RbtExtendedSignals(
                harmoniaActive = true,
                harmoniaDecisionEligible = true,
                harmoniaAction = "BASAL_FIRST",
                harmoniaBranch = "RESISTANCE_PROBABLE",
                harmoniaBasalDemandRateUph = 1.8,
                harmoniaBasalMaxRateUph = 5.0,
            ),
        )

        assertThat(snapshot.resolutions.basalFirstChannel)
            .isEqualTo(BasalFirstChannel.HARMONIA_PRODUCTION_BASAL_FIRST)
        assertThat(snapshot.resolutions.harmoniaBasalFirst?.eligible).isTrue()
        assertThat(snapshot.resolutions.harmoniaBasalFirst?.boundedRateUph).isEqualTo(1.8)
        assertThat(snapshot.resolutions.harmoniaBasalFirst?.reasonCodes)
            .contains("HARMONIA_BASAL_FIRST_READY")
    }

    @Test
    fun resolve_keepsT3cPriority_whenBothBasalFirstBranchesAreEligible() {
        val snapshot = resolveWith(
            RbtExtendedSignals(
                t3cActive = true,
                t3cBasalDemandRateUph = 2.0,
                t3cBasalMaxRateUph = 5.0,
                harmoniaActive = true,
                harmoniaDecisionEligible = true,
                harmoniaAction = "BASAL_FIRST",
                harmoniaBranch = "RESISTANCE_PROBABLE",
                harmoniaBasalDemandRateUph = 1.8,
                harmoniaBasalMaxRateUph = 5.0,
            ),
        )

        assertThat(snapshot.resolutions.basalFirstChannel)
            .isEqualTo(BasalFirstChannel.T3C_BASAL_FIRST)
        assertThat(snapshot.resolutions.t3cBasalFirst?.eligible).isTrue()
        assertThat(snapshot.resolutions.harmoniaBasalFirst?.eligible).isTrue()
    }

    @Test
    fun resolve_selectsHarmoniaBasalFirst_whenSoftAuthorityAndDigestionMealSupport() {
        // Field failure mode: SOFT (meal bypass / aggressive rise) + eligible MEAL_SUPPORT on
        // DIGESTION_ACTIVE used to force basalFirstChannel=NONE → rbt_no_harmonia_channel.
        val snapshot = resolveWith(
            ext = RbtExtendedSignals(
                harmoniaActive = true,
                harmoniaDecisionEligible = true,
                harmoniaAction = "MEAL_SUPPORT",
                harmoniaBranch = "DIGESTION_ACTIVE",
                harmoniaBasalDemandRateUph = 2.2,
                harmoniaBasalMaxRateUph = 5.0,
                harmoniaSmbDemandU = 0.6,
                harmoniaSmbMaxU = 1.0,
            ),
            releaseScales = true,
        )

        assertThat(snapshot.resolutions.releaseAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(snapshot.resolutions.basalFirstChannel)
            .isEqualTo(BasalFirstChannel.HARMONIA_PRODUCTION_BASAL_FIRST)
        assertThat(snapshot.resolutions.harmoniaBasalFirst?.eligible).isTrue()
        // Mutex: basal owner wins → Harmonia SMB modulator stands down this tick.
        assertThat(snapshot.resolutions.harmoniaSmb?.eligible).isFalse()
    }

    @Test
    fun resolve_keepsSmbModulatorPath_whenSoftAuthorityButBranchIsNotDigestion() {
        val snapshot = resolveWith(
            ext = RbtExtendedSignals(
                harmoniaActive = true,
                harmoniaDecisionEligible = true,
                harmoniaAction = "MEAL_SUPPORT",
                harmoniaBranch = "MEAL_PROBABLE",
                harmoniaBasalDemandRateUph = 1.8,
                harmoniaBasalMaxRateUph = 5.0,
                harmoniaSmbDemandU = 1.5,
                harmoniaSmbMaxU = 2.0,
            ),
            releaseScales = true,
        )

        assertThat(snapshot.resolutions.releaseAuthority).isEqualTo(ReleaseAuthority.SOFT)
        assertThat(snapshot.resolutions.basalFirstChannel).isEqualTo(BasalFirstChannel.NONE)
        assertThat(snapshot.resolutions.harmoniaSmb?.eligible).isTrue()
    }

    @Test
    fun export_containsHarmoniaBasalFirstBlocker() {
        val snapshot = resolveWith(
            RbtExtendedSignals(
                harmoniaActive = true,
                harmoniaDecisionEligible = true,
                harmoniaAction = "BASAL_FIRST",
                harmoniaBranch = "RECOVERY",
                harmoniaBasalDemandRateUph = 1.4,
                harmoniaBasalMaxRateUph = 5.0,
                harmoniaPostHypoBlock = true,
                harmoniaBlockReason = "POST_HYPO",
            ),
        )
        val export = UnfoldExporter.toJsonObject(
            UnfoldExporter.toExport(
                snapshot = snapshot,
                shadowOnly = false,
                authorityApplied = false,
            ),
        )
        val harmonia = export
            .getJSONObject("resolution")
            .getJSONObject("harmonia_basal_first")

        assertThat(snapshot.resolutions.basalFirstChannel).isEqualTo(BasalFirstChannel.NONE)
        assertThat(harmonia.getBoolean("eligible")).isFalse()
        assertThat(harmonia.getString("dominant_blocker")).isEqualTo("POST_HYPO")
        assertThat(harmonia.getJSONArray("reason_codes").toString())
            .contains("HARMONIA_POST_HYPO_BLOCK")
    }

    private fun resolveWith(
        ext: RbtExtendedSignals,
        releaseScales: Boolean = false,
    ): RecursiveBeliefSnapshot {
        val ctx = RecursiveBeliefMr7TestHelper.minimalCtx(
            bg = if (releaseScales) 226.0 else 140.0,
            delta = if (releaseScales) 5.0 else 0.5,
            v3Smb = 0.0,
            replaceHtrRelease = false,
            hypoMinPredIgnored = false,
            extended = ext,
        )
        val scales = if (releaseScales) {
            listOf(
                beliefScale(15, belief = 0.72, urgency = 0.75, terminal = 245.0),
                beliefScale(60, belief = 0.80, urgency = 1.25, terminal = 285.0),
                beliefScale(180, belief = 0.40, urgency = 0.10, terminal = 180.0),
            )
        } else {
            listOf(
                beliefScale(15, belief = 0.45, urgency = 0.1, terminal = 135.0),
                beliefScale(60, belief = 0.50, urgency = 0.1, terminal = 145.0),
                beliefScale(180, belief = 0.35, urgency = 0.0, terminal = 125.0),
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
