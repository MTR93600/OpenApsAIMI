package app.aaps.core.interfaces.aps

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SmbIntervalGateTest {

    private val now = 1_700_000_000_000L
    private val interval = 3
    private val intervalMs = interval * 60_000L

    @Test
    fun `a bolus inside the interval is too soon`() {
        assertThat(SmbIntervalGate.tooSoon(now - 60_000L, interval, now)).isTrue()
    }

    @Test
    fun `a bolus 7 s short of the interval is too soon`() {
        assertThat(SmbIntervalGate.tooSoon(now - intervalMs + 7_000L, interval, now)).isTrue()
    }

    @Test
    fun `a bolus exactly at the tolerance is not too soon`() {
        assertThat(SmbIntervalGate.tooSoon(now - intervalMs + SmbIntervalGate.TOLERANCE_MS, interval, now)).isFalse()
    }

    @Test
    fun `a bolus 5 s short of the interval is not too soon`() {
        assertThat(SmbIntervalGate.tooSoon(now - intervalMs + 5_000L, interval, now)).isFalse()
    }

    @Test
    fun `a bolus older than the interval is not too soon`() {
        assertThat(SmbIntervalGate.tooSoon(now - intervalMs - 1L, interval, now)).isFalse()
    }

    @Test
    fun `no bolus at all is not too soon`() {
        assertThat(SmbIntervalGate.tooSoon(0L, 10, now)).isFalse()
    }
}
