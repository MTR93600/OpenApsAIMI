package app.aaps.plugins.aps.openAPSAIMI.safety

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CorrectionAggressionBasalCapTest {

    private val reboundGuard = CorrectionAggressionGate.Decision(
        tier = CorrectionAggressionGate.Tier.REBOUND_GUARD,
        mealTierFull = false,
        allowGlobalHyperKicker = false,
        allowRocketBasalScale = false,
        allowRocketHypoOverride = false,
        maxBasalScaleCap = 1.5,
        reasonTag = "post_hypo_rebound_guard",
    )

    @Test
    fun reboundGuard_capsV3DirectTbr() {
        val result = CorrectionAggressionBasalCap.apply(
            requestedRateUph = 5.8,
            profileBasalUph = 0.8,
            gate = reboundGuard,
        )
        assertThat(result.wasCapped).isTrue()
        assertThat(result.cappedRateUph).isWithin(0.001).of(1.2)
    }

    @Test
    fun fullTier_doesNotCap() {
        val full = reboundGuard.copy(
            tier = CorrectionAggressionGate.Tier.FULL,
            allowRocketBasalScale = true,
            maxBasalScaleCap = 10.0,
        )
        val result = CorrectionAggressionBasalCap.apply(
            requestedRateUph = 5.8,
            profileBasalUph = 0.8,
            gate = full,
        )
        assertThat(result.wasCapped).isFalse()
        assertThat(result.cappedRateUph).isWithin(0.001).of(5.8)
    }

    @Test
    fun mergeUsesMinWhenReboundGuard() {
        val merged = CorrectionAggressionBasalCap.mergeEngineAndRtRates(
            engineRateUph = 5.8,
            rtRateUph = 1.11,
            gate = reboundGuard,
        )
        assertThat(merged).isWithin(0.001).of(1.11)
    }

    @Test
    fun mergeUsesMaxWhenFullTier() {
        val full = reboundGuard.copy(allowRocketBasalScale = true)
        val merged = CorrectionAggressionBasalCap.mergeEngineAndRtRates(
            engineRateUph = 5.8,
            rtRateUph = 1.11,
            gate = full,
        )
        assertThat(merged).isWithin(0.001).of(5.8)
    }
}
