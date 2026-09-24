package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PostHypoProjectionCapTest {

    @Test
    fun reboundMomentumTerminal_isBounded() {
        val result = PostHypoProjectionCap.capTerminalMgdl(
            bgMgdl = 119.0,
            targetBgMgdl = 100.0,
            deltaMgdl5m = 5.0,
            terminalMgdl = 339.0,
            minBgLookback75m = 54.0,
            hasIndependentMealEvidence = false,
        )
        assertThat(result.wasCapped).isTrue()
        assertThat(result.cappedTerminalMgdl).isLessThan(200.0)
        assertThat(result.cappedTerminalMgdl).isGreaterThan(119.0)
    }

    @Test
    fun independentMealEvidence_skipsCap() {
        val result = PostHypoProjectionCap.capTerminalMgdl(
            bgMgdl = 119.0,
            targetBgMgdl = 100.0,
            deltaMgdl5m = 5.0,
            terminalMgdl = 339.0,
            minBgLookback75m = 54.0,
            hasIndependentMealEvidence = true,
        )
        assertThat(result.wasCapped).isFalse()
        assertThat(result.cappedTerminalMgdl).isWithin(0.001).of(339.0)
    }

    @Test
    fun noRecentHypo_skipsCap() {
        val result = PostHypoProjectionCap.capTerminalMgdl(
            bgMgdl = 145.0,
            targetBgMgdl = 100.0,
            deltaMgdl5m = 6.0,
            terminalMgdl = 220.0,
            minBgLookback75m = 95.0,
            hasIndependentMealEvidence = false,
        )
        assertThat(result.wasCapped).isFalse()
        assertThat(result.cappedTerminalMgdl).isWithin(0.001).of(220.0)
    }

    @Test
    fun reboundAlreadyAboveCeiling_skipsCapWithoutCrash() {
        val result = PostHypoProjectionCap.capTerminalMgdl(
            bgMgdl = 208.0,
            targetBgMgdl = 100.0,
            deltaMgdl5m = 5.0,
            terminalMgdl = 401.0,
            minBgLookback75m = 54.0,
            hasIndependentMealEvidence = false,
        )
        assertThat(result.wasCapped).isFalse()
        assertThat(result.cappedTerminalMgdl).isWithin(0.001).of(401.0)
    }
}
