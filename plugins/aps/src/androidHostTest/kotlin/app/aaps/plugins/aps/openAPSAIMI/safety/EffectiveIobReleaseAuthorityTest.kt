package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EffectiveIobReleaseAuthorityTest {

    private fun input(
        enabled: Boolean = true,
        ledger: Double = 5.0,
        effective: Double? = 3.0,
        postHypoActive: Boolean = false,
        postHypoOrdinal: Int = 0,
        postHypoProb: Double? = null,
        minBg: Double = 130.0,
    ) = EffectiveIobReleaseAuthority.Input(
        enabled = enabled,
        iobLedgerU = ledger,
        iobEffectiveU = effective,
        postHypoAuthorityActive = postHypoActive,
        postHypoStateOrdinal = postHypoOrdinal,
        postHypoProb = postHypoProb,
        minBgRecentMgdl = minBg,
    )

    @Test
    fun disabledIsNoOp() {
        val d = EffectiveIobReleaseAuthority.evaluate(input(enabled = false))
        assertEquals(0.0, d.theta, 0.0)
        assertEquals(5.0, d.iobForGateU, 0.0) // == ledger
        assertEquals("disabled", d.reasonTag)
    }

    @Test
    fun effectiveUnavailableIsNoOp() {
        val d = EffectiveIobReleaseAuthority.evaluate(input(effective = null))
        assertEquals(5.0, d.iobForGateU, 0.0)
        assertEquals("effective_unavailable", d.reasonTag)
    }

    @Test
    fun slowInsulinEffectiveGeLedgerIsNoOp() {
        // effective >= ledger → never raise the gate IOB here (release-only)
        val d = EffectiveIobReleaseAuthority.evaluate(input(ledger = 4.0, effective = 5.0))
        assertEquals(0.0, d.theta, 0.0)
        assertEquals(4.0, d.iobForGateU, 0.0)
        assertEquals("no_gap_effective_ge_ledger", d.reasonTag)
    }

    @Test
    fun postHypoAuthorityActiveRetractsFully() {
        val d = EffectiveIobReleaseAuthority.evaluate(input(postHypoActive = true))
        assertEquals(0.0, d.theta, 0.0)
        assertEquals(5.0, d.iobForGateU, 0.0)
        assertEquals("post_hypo_active", d.reasonTag)
    }

    @Test
    fun recentHypoFloorRetractsFully() {
        val d = EffectiveIobReleaseAuthority.evaluate(input(minBg = 70.0))
        assertEquals(0.0, d.theta, 0.0)
        assertEquals(5.0, d.iobForGateU, 0.0)
        assertEquals("recent_hypo_floor", d.reasonTag)
    }

    @Test
    fun cleanHistoryReleasesUpToThetaMax() {
        // minBg well above margin (105) → full THETA_MAX; gap = 5-3 = 2 → release = 0.5*2 = 1.0
        val d = EffectiveIobReleaseAuthority.evaluate(input(ledger = 5.0, effective = 3.0, minBg = 130.0))
        assertEquals(EffectiveIobReleaseAuthority.THETA_MAX, d.theta, 1e-9)
        assertEquals(1.0, d.releasedU, 1e-9)
        assertEquals(4.0, d.iobForGateU, 1e-9)
        // never below the effective IOB, never above the ledger
        assertTrue(d.iobForGateU in 3.0..5.0)
    }

    @Test
    fun rampScalesReleaseNearHypoFloor() {
        // minBg exactly midway (90) between 75 and 105 → ramp 0.5 → theta = 0.25
        val d = EffectiveIobReleaseAuthority.evaluate(input(minBg = 90.0))
        assertEquals(0.25, d.theta, 1e-9)
        assertTrue(d.releasedU > 0.0)
    }

    @Test
    fun postHypoStateDampsRelease() {
        val clean = EffectiveIobReleaseAuthority.evaluate(input(minBg = 130.0, postHypoOrdinal = 0))
        val rebound = EffectiveIobReleaseAuthority.evaluate(input(minBg = 130.0, postHypoOrdinal = 1))
        assertTrue(rebound.theta < clean.theta)
        assertEquals(clean.theta * EffectiveIobReleaseAuthority.POST_HYPO_STATE_DAMP, rebound.theta, 1e-9)
    }

    @Test
    fun gateFlipHappensOnlyWhenReleaseCrossesMaxIob() {
        // ledger 5.05 would block at maxIob 5.0; released to 4.0 → would allow
        val d = EffectiveIobReleaseAuthority.evaluate(input(ledger = 5.05, effective = 3.0, minBg = 130.0))
        val maxIob = 5.0
        val flips = d.iobLedgerU > maxIob && d.iobForGateU <= maxIob
        assertTrue(flips)
        assertFalse(d.iobForGateU > maxIob)
    }
}
