package app.aaps.plugins.aps.openAPSAIMI.physio

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SleepLiveDetectorTest {

    @Test
    fun therapy_sleep_declared_is_high_confidence() {
        val result = SleepLiveDetector.evaluate(
            SleepLiveDetector.Input(
                therapySleepTime = true,
                stepsLast15m = 500,
                hrNowBpm = 110,
            ),
        )

        assertThat(result.isAsleep).isTrue()
        assertThat(result.source).isEqualTo(SleepLiveDetector.Source.THERAPY)
        assertThat(result.confidence).isAtLeast(0.9)
    }

    @Test
    fun health_connect_ongoing_session_detects_asleep() {
        val result = SleepLiveDetector.evaluate(
            SleepLiveDetector.Input(
                hcSessionActive = true,
                stepsLast15m = 200,
            ),
        )

        assertThat(result.isAsleep).isTrue()
        assertThat(result.source).isEqualTo(SleepLiveDetector.Source.HEALTH_CONNECT)
    }

    @Test
    fun wearable_heuristic_detects_quiet_resting_hr() {
        val result = SleepLiveDetector.evaluate(
            SleepLiveDetector.Input(
                stepsLast15m = 0,
                stepsLast5m = 0,
                hrNowBpm = 58,
                rhrRestingBpm = 55,
                clockIsNight = true,
            ),
        )

        assertThat(result.isAsleep).isTrue()
        assertThat(result.source).isEqualTo(SleepLiveDetector.Source.WEARABLE)
    }

    @Test
    fun active_steps_block_wearable_asleep() {
        val result = SleepLiveDetector.evaluate(
            SleepLiveDetector.Input(
                stepsLast15m = 800,
                stepsLast5m = 200,
                hrNowBpm = 58,
                rhrRestingBpm = 55,
                clockIsNight = true,
            ),
        )

        assertThat(result.isAsleep).isFalse()
        assertThat(result.source).isEqualTo(SleepLiveDetector.Source.NONE)
    }

    @Test
    fun sleep_guard_covers_night_and_daytime_nap() {
        assertThat(SleepLiveDetector.sleepGuardActive(isNight = true, asleepLiveConfidence = 0.0)).isTrue()
        assertThat(SleepLiveDetector.sleepGuardActive(isNight = false, asleepLiveConfidence = 0.62)).isTrue()
        assertThat(SleepLiveDetector.sleepGuardActive(isNight = false, asleepLiveConfidence = 0.2)).isFalse()
    }

    @Test
    fun sleep_data_mtr_ongoing_session_bounds() {
        val now = 1_700_000_000_000L
        val sleep = SleepDataMTR(
            startTime = now - 8 * 60 * 60 * 1000L,
            endTime = now + 1 * 60 * 60 * 1000L,
            durationHours = 7.0,
            ongoingStartMs = now - 2 * 60 * 60 * 1000L,
            ongoingEndMs = now + 30 * 60 * 1000L,
        )

        assertThat(sleep.isOngoingAt(now)).isTrue()
        assertThat(sleep.isOngoingAt(now + 2 * 60 * 60 * 1000L)).isFalse()
    }
}
