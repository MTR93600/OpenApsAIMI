package app.aaps.plugins.aps.openAPSAIMI.smb

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SmbIntervalPolicyTest {

    @Test
    fun low_bg_applies_floor_without_pkpd_cumulative_boost() {
        val interval = SmbIntervalPolicy.applyLowBgFloorAndPkpdBoost(
            intervalAfterModes = 2,
            bgMgdl = 110f,
            pkpdThrottleIntervalAdd = 4,
        )
        assertThat(interval).isEqualTo(5)
    }

    @Test
    fun above_low_bg_adds_pkpd_boost_capped_at_max() {
        val interval = SmbIntervalPolicy.applyLowBgFloorAndPkpdBoost(
            intervalAfterModes = 3,
            bgMgdl = 145f,
            pkpdThrottleIntervalAdd = 4,
        )
        assertThat(interval).isEqualTo(7)
    }

    @Test
    fun above_low_bg_pkpd_boost_respects_max_interval() {
        val interval = SmbIntervalPolicy.applyLowBgFloorAndPkpdBoost(
            intervalAfterModes = 8,
            bgMgdl = 160f,
            pkpdThrottleIntervalAdd = 4,
        )
        assertThat(interval).isEqualTo(10)
    }

    @Test
    fun low_bg_keeps_interval_at_least_floor_when_already_higher() {
        val interval = SmbIntervalPolicy.applyLowBgFloorAndPkpdBoost(
            intervalAfterModes = 7,
            bgMgdl = 95f,
            pkpdThrottleIntervalAdd = 3,
        )
        assertThat(interval).isEqualTo(7)
    }
}
