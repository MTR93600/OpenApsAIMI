package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.interfaces.logging.AAPSLogger
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ContextInfluenceEngineTest {

    private val engine = ContextInfluenceEngine(mockk<AAPSLogger>(relaxed = true))
    private val t0 = 1_720_000_000_000L

    private fun influence(intents: List<ContextIntent>, tsMs: Long = t0, bg: Double = 160.0, iob: Double = 0.5) =
        engine.computeInfluence(ContextSnapshot.from(tsMs, intents), currentBG = bg, iob = iob, cob = 0.0)

    @Test
    fun hypoRecovery_suppressesSmbAndPrefersBasal() {
        val inf = influence(listOf(ContextIntent.HypoRecovery(startTimeMs = t0, intensity = ContextIntent.Intensity.MEDIUM)))
        assertThat(inf.suppressSmb).isTrue()
        assertThat(inf.preferBasal).isTrue()
        assertThat(inf.smbFactorClamp).isAtMost(0.60f)
        assertThat(inf.extraIntervalMin).isAtLeast(8)
    }

    @Test
    fun slowCarb_earlyPhase_capsSmbWithAbsoluteCeiling() {
        val inf = influence(listOf(ContextIntent.SlowCarbMeal(startTimeMs = t0, intensity = ContextIntent.Intensity.MEDIUM)))
        assertThat(inf.smbCeilingU).isNotNull()
        assertThat(inf.smbCeilingU!!).isWithin(0.01).of(1.0)   // MEDIUM early ceiling
        assertThat(inf.smbFactorClamp).isWithin(0.01f).of(0.60f)
        assertThat(inf.suppressSmb).isFalse()
        assertThat(inf.preferBasal).isFalse()
        assertThat(inf.extraIntervalMin).isAtLeast(8)
    }

    @Test
    fun slowCarb_absorbingPhase_higherCeiling() {
        // 91 min elapsed → past the 90-min absorption delay → ABSORBING
        val inf = influence(
            listOf(ContextIntent.SlowCarbMeal(startTimeMs = t0, intensity = ContextIntent.Intensity.MEDIUM)),
            tsMs = t0 + 91 * 60_000L,
        )
        assertThat(inf.smbCeilingU!!).isWithin(0.01).of(2.0)   // MEDIUM absorbing ceiling
        assertThat(inf.smbFactorClamp).isGreaterThan(0.60f)     // less damped than early
    }

    @Test
    fun slowCarb_deferredHypoGuard_tightensFactorAndCeiling() {
        val early = influence(listOf(ContextIntent.SlowCarbMeal(startTimeMs = t0, intensity = ContextIntent.Intensity.MEDIUM)), bg = 160.0)
        val low = influence(listOf(ContextIntent.SlowCarbMeal(startTimeMs = t0, intensity = ContextIntent.Intensity.MEDIUM)), bg = 95.0)
        assertThat(low.smbCeilingU!!).isLessThan(early.smbCeilingU!!) // ceiling ×0.60 when BG<100
        assertThat(low.smbFactorClamp).isLessThan(early.smbFactorClamp)
    }

    @Test
    fun hypoRecovery_winsOverSlowCarb_whenBothActive() {
        val inf = influence(
            listOf(
                ContextIntent.SlowCarbMeal(startTimeMs = t0, intensity = ContextIntent.Intensity.HIGH),
                ContextIntent.HypoRecovery(startTimeMs = t0, intensity = ContextIntent.Intensity.MEDIUM),
            )
        )
        assertThat(inf.suppressSmb).isTrue()
        assertThat(inf.preferBasal).isTrue()
    }

    @Test
    fun noContext_isNeutral() {
        val inf = engine.computeInfluence(ContextSnapshot.empty(t0), currentBG = 160.0, iob = 0.5, cob = 0.0)
        assertThat(inf.suppressSmb).isFalse()
        assertThat(inf.smbCeilingU).isNull()
        assertThat(inf.smbFactorClamp).isEqualTo(1.0f)
    }
}
