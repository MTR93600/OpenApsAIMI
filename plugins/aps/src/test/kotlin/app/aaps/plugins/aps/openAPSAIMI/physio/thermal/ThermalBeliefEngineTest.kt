package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import app.aaps.plugins.aps.openAPSAIMI.wcycle.CyclePhase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

class ThermalBeliefEngineTest {

    @AfterEach
    fun tearDown() {
        ThermalBaselineStore.resetForTests()
    }

    @Test
    fun build_detects_inflammatory_drift_from_warming_slope() {
        val nowMs = 1_718_000_000_000L
        val samples = (0 until 8).map { index ->
            ThermalSampleMTR(
                timestampMs = nowMs - (7 - index) * 60 * 60_000L,
                deltaCelsius = index * 0.12,
                measurementLocation = "WRIST",
                dataOrigin = "com.garmin.android.apps.healthconnect",
            )
        }
        val digest = ThermalBeliefEngine.build(
            window = ThermalDataWindowMTR(skinSamples = samples, fetchedAtMs = nowMs),
            hrNowBpm = 102,
            rhrRestingBpm = 68,
            sleepDebtMinutes = 90,
            // Was hrvRmssd = 22.0. The engine now takes the person-relative reading, and
            // the old band turned 22.0 into exactly this, so the assertions below are unchanged.
            hrvDepression = 0.2666666667,
            wCyclePhase = null,
        )

        assertThat(digest.hypothesis).isEqualTo(ThermalHypothesis.INFLAMMATORY_DRIFT)
        assertThat(digest.inflammationIndex).isGreaterThan(0.35)
        assertThat(digest.narrative).contains("warming")
    }

    @Test
    fun build_aligns_cycle_bbt_with_luteal_phase() {
        val nowMs = 1_718_000_000_000L
        val digest = ThermalBeliefEngine.build(
            window = ThermalDataWindowMTR(
                skinSamples = listOf(
                    ThermalSampleMTR(nowMs - 3_600_000L, 0.25, "FINGER", "com.ouraring.oura"),
                    ThermalSampleMTR(nowMs, 0.42, "FINGER", "com.ouraring.oura"),
                ),
                basalBodyTemperature = BasalBodyTemperatureMTR(
                    timestampMs = nowMs,
                    temperatureCelsius = 36.8,
                    dataOrigin = "com.ouraring.oura",
                ),
                fetchedAtMs = nowMs,
            ),
            hrNowBpm = 74,
            rhrRestingBpm = 68,
            sleepDebtMinutes = 0,
            // Was hrvRmssd = 42.0. The engine now takes the person-relative reading, and
            // the old band turned 42.0 into exactly this, so the assertions below are unchanged.
            hrvDepression = 0.0,
            wCyclePhase = CyclePhase.LUTEAL,
        )

        assertThat(digest.hypothesis).isEqualTo(ThermalHypothesis.CYCLE_BBT_RISE)
        assertThat(digest.wCycleHint).isEqualTo("LUTEAL_BBT_RISE")
        assertThat(digest.narrative).contains("luteal")
    }

    @Test
    fun build_ignores_sub_noise_floor_deltas_as_stable() {
        val nowMs = 1_718_000_000_000L
        val samples = (0 until 6).map { index ->
            ThermalSampleMTR(
                timestampMs = nowMs - (5 - index) * 60 * 60_000L,
                deltaCelsius = if (index % 2 == 0) 0.01 else 0.02,
                measurementLocation = "WRIST",
                dataOrigin = "com.garmin.android.apps.healthconnect",
            )
        }
        val digest = ThermalBeliefEngine.build(
            window = ThermalDataWindowMTR(skinSamples = samples, fetchedAtMs = nowMs),
            hrNowBpm = 70,
            rhrRestingBpm = 68,
            sleepDebtMinutes = 0,
            // Was hrvRmssd = 40.0. The engine now takes the person-relative reading, and
            // the old band turned 40.0 into exactly this, so the assertions below are unchanged.
            hrvDepression = 0.0,
            wCyclePhase = null,
        )

        assertThat(digest.hypothesis).isEqualTo(ThermalHypothesis.BASELINE_STABLE)
        assertThat(digest.deltaVsBaselineC).isWithin(0.001)
        assertThat(digest.inflammationIndex).isLessThan(0.15)
    }

    @Test
    fun build_caps_confidence_for_inferred_recovery_proxy() {
        val nowMs = 1_718_000_000_000L
        val samples = (0 until 6).map { index ->
            ThermalSampleMTR(
                timestampMs = nowMs - (5 - index) * 24 * 3_600_000L,
                deltaCelsius = index * 0.15,
                measurementLocation = "WRIST",
                dataOrigin = "${ThermalDataOrigins.HC_INFERRED}:Garmin",
            )
        }
        val digest = ThermalBeliefEngine.build(
            window = ThermalDataWindowMTR(
                skinSamples = samples,
                fetchedAtMs = nowMs,
                sourceTier = ThermalSourceTier.INFERRED,
                resolvedSource = "${ThermalDataOrigins.HC_INFERRED}:Garmin",
            ),
            hrNowBpm = 95,
            rhrRestingBpm = 65,
            sleepDebtMinutes = 120,
            // Was hrvRmssd = 18.0. The engine now takes the person-relative reading, and
            // the old band turned 18.0 into exactly this, so the assertions below are unchanged.
            hrvDepression = 0.4,
            wCyclePhase = null,
        )

        assertThat(digest.sourceTier).isEqualTo(ThermalSourceTier.INFERRED)
        assertThat(digest.confidence).isAtMost(0.50)
        assertThat(digest.narrative).contains("inferred")
    }
}
