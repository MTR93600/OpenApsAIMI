package app.aaps.plugins.aps.openAPSAIMI

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AutodriveBasalPolicyTest {

    @Test
    fun `early tier remains soft outside deep hyper`() {
        val factor = AutodriveBasalPolicy.tierFactor(
            stateReason = "Early: Bg≥120 & EffDelta≥2",
            bgMgdl = 178.0,
            targetBgMgdl = 100.0,
        )

        assertThat(factor).isEqualTo(0.5)
    }

    @Test
    fun `early tier promotes to full cap in severe hyper`() {
        val factor = AutodriveBasalPolicy.tierFactor(
            stateReason = "Early: Bg≥120 & EffDelta≥2",
            bgMgdl = 293.0,
            targetBgMgdl = 100.0,
        )

        assertThat(factor).isEqualTo(1.0)
    }

    @Test
    fun `direct correction tbr keeps explicit cap in severe hyper`() {
        val adaptive = AutodriveBasalPolicy.adaptiveMultiplierForDirectTbr(
            requestedRateUph = 9.0,
            bgMgdl = 293.0,
            targetBgMgdl = 100.0,
            profileMaxBasalUph = 9.0,
            learnedAdaptiveMultiplier = 0.88,
        )

        assertThat(adaptive).isEqualTo(1.0)
    }

    @Test
    fun `learned adaptive damping remains active away from cap pressure`() {
        val adaptive = AutodriveBasalPolicy.adaptiveMultiplierForDirectTbr(
            requestedRateUph = 6.0,
            bgMgdl = 210.0,
            targetBgMgdl = 100.0,
            profileMaxBasalUph = 9.0,
            learnedAdaptiveMultiplier = 0.88,
        )

        assertThat(adaptive).isWithin(0.0001).of(0.88)
    }
}
