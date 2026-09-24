package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.plugins.aps.openAPSAIMI.patient.PatientMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostHypoDeliveryAuthorityTest {

    private fun aggressionInput(
        bg: Double = 119.0,
        targetBg: Double = 100.0,
        delta: Double = 5.0,
        combined: Double = 2.5,
        minBg75: Double = 54.0,
        cob: Double = 0.0,
        uam: Double = 0.7,
        ra: Double = 0.9,
    ) = CorrectionAggressionGate.Input(
        bg = bg,
        targetBg = targetBg,
        deltaMgdl5m = delta,
        shortAvgDelta = 4.0,
        combinedDelta = combined,
        cob = cob,
        minBgLookback75m = minBg75,
        estimatedCarbs = 0.0,
        estimatedCarbsAgeMin = Double.MAX_VALUE,
        uamConfidence = uam,
        estimatedRa = ra,
        explicitMealMode = false,
        hasRecentMealEstimate = false,
        isConfirmedHighRise = false,
    )

    private fun reboundGuardGate(): CorrectionAggressionGate.Decision =
        CorrectionAggressionGate.evaluate(aggressionInput())

    @Test
    fun thomasIncident_reboundGuardPostHypoRecovery_arbitratesDelivery() {
        val decision = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = reboundGuardGate(),
                patientMode = PatientMode.POST_HYPO_RECOVERY,
                aggressionInput = aggressionInput(),
            ),
        )

        assertTrue(decision.active)
        assertTrue(decision.forceMealInterpretationSuppressed)
        assertTrue(decision.suppressMealDelivery)
        assertEquals(0.0, decision.maxSmbU, 0.001)
        assertEquals(0.0, decision.capSmbU(0.77), 0.001)
    }

    @Test
    fun inactive_whenTierFull() {
        val fullGate = CorrectionAggressionGate.evaluate(
            aggressionInput(bg = 165.0, minBg75 = 90.0).copy(isConfirmedHighRise = true),
        )
        val decision = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = fullGate,
                patientMode = PatientMode.POST_HYPO_RECOVERY,
                aggressionInput = aggressionInput(),
            ),
        )

        assertFalse(decision.active)
        assertEquals(PostHypoDeliveryAuthority.REASON_TIER_NOT_REBOUND, decision.reasonTag)
        assertEquals(0.77, decision.capSmbU(0.77), 0.001)
    }

    @Test
    fun inactive_whenIndependentMealEvidence() {
        val decision = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = reboundGuardGate(),
                patientMode = PatientMode.POST_HYPO_RECOVERY,
                aggressionInput = aggressionInput(cob = 12.0),
            ),
        )

        assertFalse(decision.active)
        assertEquals(PostHypoDeliveryAuthority.REASON_MEAL_EVIDENCE, decision.reasonTag)
    }

    @Test
    fun inactive_whenPatientModeNotPostHypoRecovery() {
        val decision = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = reboundGuardGate(),
                patientMode = PatientMode.FAST_MEAL,
                aggressionInput = aggressionInput(),
            ),
        )

        assertFalse(decision.active)
        assertEquals(PostHypoDeliveryAuthority.REASON_MODE_NOT_POST_HYPO, decision.reasonTag)
    }

    @Test
    fun export_namesTheDecliningConditionAndTheCapEffect() {
        val declined = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = reboundGuardGate(),
                patientMode = PatientMode.POST_HYPO_RECOVERY,
                aggressionInput = aggressionInput(cob = 12.0),
            ),
        )
        val declinedJson = PostHypoDeliveryAuthority.toJsonObject(declined, 0.77, 0.77)
        assertFalse(declinedJson.getBoolean("active"))
        assertEquals(PostHypoDeliveryAuthority.REASON_MEAL_EVIDENCE, declinedJson.getString("reason_tag"))
        assertTrue(declinedJson.isNull("max_smb_u"))
        assertEquals(0.77, declinedJson.getDouble("smb_before_cap_u"), 0.001)
        assertEquals(0.77, declinedJson.getDouble("smb_after_cap_u"), 0.001)

        val applied = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = reboundGuardGate(),
                patientMode = PatientMode.POST_HYPO_RECOVERY,
                aggressionInput = aggressionInput(),
            ),
        )
        val appliedJson = PostHypoDeliveryAuthority.toJsonObject(applied, 0.77, applied.capSmbU(0.77))
        assertTrue(appliedJson.getBoolean("active"))
        assertEquals(0.0, appliedJson.getDouble("max_smb_u"), 0.001)
        assertEquals(0.77, appliedJson.getDouble("smb_before_cap_u"), 0.001)
        assertEquals(0.0, appliedJson.getDouble("smb_after_cap_u"), 0.001)
    }

    @Test
    fun inactive_whenAggressiveRiseExit_targetPlus30_andDeltaAbove15() {
        val decision = PostHypoDeliveryAuthority.evaluate(
            PostHypoDeliveryAuthority.Input(
                gate = reboundGuardGate(),
                patientMode = PatientMode.POST_HYPO_RECOVERY,
                aggressionInput = aggressionInput(bg = 140.0, targetBg = 100.0, delta = 16.0, minBg75 = 54.0),
            ),
        )

        assertFalse(decision.active)
        assertEquals("post_hypo_aggressive_rise_exit", decision.reasonTag)
        assertEquals(0.77, decision.capSmbU(0.77), 0.001)
    }
}

