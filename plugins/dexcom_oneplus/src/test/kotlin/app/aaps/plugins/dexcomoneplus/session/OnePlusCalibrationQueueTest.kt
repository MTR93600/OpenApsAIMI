package app.aaps.plugins.dexcomoneplus.session

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The one slot a fingerstick waits in, and the transmitter time it is sent with. */
class OnePlusCalibrationQueueTest {

    private val now = 1_700_000_000_000L
    private val minute = 60L * 1000L

    @Test
    fun `a fresh fingerstick in range is taken, once`() {
        val queue = OnePlusCalibrationQueue()

        assertThat(queue.offer(glucoseMgdl = 150, bloodAtMs = now - 2 * minute, nowMs = now)).isTrue()
        assertThat(queue.offer(glucoseMgdl = 160, bloodAtMs = now, nowMs = now)).isFalse()

        val taken = queue.take()
        assertThat(taken?.glucoseMgdl).isEqualTo(150)
        assertThat(queue.take()).isNull()
        assertThat(queue.offer(glucoseMgdl = 160, bloodAtMs = now, nowMs = now)).isTrue()
    }

    @Test
    fun `out of range, in the future, or too old is refused at the door`() {
        val queue = OnePlusCalibrationQueue()

        assertThat(queue.offer(glucoseMgdl = 39, bloodAtMs = now, nowMs = now)).isFalse()
        assertThat(queue.offer(glucoseMgdl = 401, bloodAtMs = now, nowMs = now)).isFalse()
        assertThat(queue.offer(glucoseMgdl = 150, bloodAtMs = now + minute, nowMs = now)).isFalse()
        assertThat(queue.offer(glucoseMgdl = 150, bloodAtMs = now - 61 * minute, nowMs = now)).isFalse()
        assertThat(queue.peek()).isNull()
    }

    @Test
    fun `the sent time is the time of the blood, not of the sending`() {
        val dex = OnePlusCalibrationQueue.dexTimeForBlood(
            bloodAtMs = now - 5 * minute,
            nowMs = now,
            lastDexTimeSeconds = 10_000,
            lastDexTimeAtMs = now - 30 * 1000L,
        )

        assertThat(dex).isEqualTo(9_730)
    }

    @Test
    fun `without a known transmitter clock nothing is sent`() {
        assertThat(
            OnePlusCalibrationQueue.dexTimeForBlood(
                bloodAtMs = now - minute,
                nowMs = now,
                lastDexTimeSeconds = 0,
                lastDexTimeAtMs = now,
            ),
        ).isNull()
        assertThat(
            OnePlusCalibrationQueue.dexTimeForBlood(
                bloodAtMs = now - minute,
                nowMs = now,
                lastDexTimeSeconds = 10_000,
                lastDexTimeAtMs = 0L,
            ),
        ).isNull()
    }

    @Test
    fun `a fingerstick older than the transmitter session cannot be dated`() {
        assertThat(
            OnePlusCalibrationQueue.dexTimeForBlood(
                bloodAtMs = now - 50 * minute,
                nowMs = now,
                lastDexTimeSeconds = 60,
                lastDexTimeAtMs = now,
            ),
        ).isNull()
    }

    @Test
    fun `an hour is the limit`() {
        assertThat(OnePlusCalibrationQueue.isTooOld(now - 59 * minute, now)).isFalse()
        assertThat(OnePlusCalibrationQueue.isTooOld(now - 61 * minute, now)).isTrue()
    }
}
