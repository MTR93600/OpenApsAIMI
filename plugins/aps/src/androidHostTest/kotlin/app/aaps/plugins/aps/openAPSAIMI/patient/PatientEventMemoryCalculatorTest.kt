package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.TimestampedBgSample
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class PatientEventMemoryCalculatorTest {

  @Test
  fun sustainedHyper_withoutRecentLow_doesNotPegHypoLoad() {
    val nowMs = 1_718_000_000_000L
    val samples = (0 until 24).map { i ->
      TimestampedBgSample(
        timestampMs = nowMs - i * 5 * 60_000L,
        bgMgdl = 250.0 + i,
      )
    }
    val memory = PatientEventMemoryCalculator.compute(
      currentBgMgdl = 301.0,
      windowedSamples = samples,
      hypoFloor75m = 240.0,
      latentState = null,
      recoveryBurden = 0.0,
      nowMs = nowMs,
    )

    assertThat(memory.recentHypoLoad).isLessThan(0.05)
    assertThat(memory.recentHyperLoad).isGreaterThan(0.55)
  }

  @Test
  fun recentHypoInWindow_decaysWithTime() {
    val nowMs = 1_718_000_000_000L
    val samples = listOf(
      TimestampedBgSample(nowMs, 120.0),
      TimestampedBgSample(nowMs - 30 * 60_000L, 62.0),
      TimestampedBgSample(nowMs - 60 * 60_000L, 110.0),
    )
    val fresh = PatientEventMemoryCalculator.compute(
      currentBgMgdl = 120.0,
      windowedSamples = samples,
      hypoFloor75m = 62.0,
      latentState = null,
      recoveryBurden = 0.0,
      nowMs = nowMs,
    )
    val aged = PatientEventMemoryCalculator.compute(
      currentBgMgdl = 120.0,
      windowedSamples = samples,
      hypoFloor75m = 62.0,
      latentState = null,
      recoveryBurden = 0.0,
      nowMs = nowMs + 120 * 60_000L,
    )

    assertThat(fresh.recentHypoLoad).isGreaterThan(aged.recentHypoLoad)
    assertThat(aged.recentHypoLoad).isLessThan(0.25)
  }

  @Test
  fun decayHypoLoad_noRecentLow_returnsZero() {
    val decayed = PatientEventMemoryCalculator.decayHypoLoad(
      rawHypoLoad = 1.0,
      minutesSinceLow = Double.POSITIVE_INFINITY,
      halfLifeMinutes = PatientEventMemoryCalculator.HYPO_DECAY_HALF_LIFE_MINUTES,
    )
    assertThat(decayed).isEqualTo(0.0)
  }
}
