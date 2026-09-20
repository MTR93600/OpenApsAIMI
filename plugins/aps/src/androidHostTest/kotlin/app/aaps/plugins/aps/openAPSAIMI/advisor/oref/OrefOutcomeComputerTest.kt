package app.aaps.plugins.aps.openAPSAIMI.advisor.oref

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class OrefOutcomeComputerTest {

    @Test
    fun outcomeAtIndex_detects_hypo_within_4h() {
        val t0 = 1_000_000L
        val step = 300_000L // 5 min
        val ts = LongArray(15) { i -> t0 + i * step }
        val bg = doubleArrayOf(
            120.0, 118.0, 115.0, 110.0, 100.0,
            90.0, 85.0, 75.0, 68.0, 65.0,
            70.0, 80.0, 90.0, 100.0, 110.0,
        )
        val o = OrefOutcomeComputer.outcomeAtIndex(ts, bg, 0)
        assertThat(o.hypo4h).isEqualTo(1.0)
        assertThat(o.minBg4h).isLessThan(70.0)
    }
}
