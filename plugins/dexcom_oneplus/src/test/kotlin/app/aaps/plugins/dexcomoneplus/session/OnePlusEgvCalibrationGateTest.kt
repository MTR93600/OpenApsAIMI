package app.aaps.plugins.dexcomoneplus.session

import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClient
import app.aaps.plugins.dexcomoneplus.gatt.OnePlusGattClientUnimplemented
import app.aaps.plugins.dexcomoneplus.parse.OnePlusCalibrateRx
import app.aaps.plugins.dexcomoneplus.parse.OnePlusCalibrateTx
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The Control loop may build opcode 0x34 only after a fingerstick has been taken from the queue
 * and the transmitter clock is known. An empty queue, or a clock that is still unknown, writes
 * nothing of that opcode.
 */
class OnePlusEgvCalibrationGateTest {

    @Test
    fun `an empty queue writes no calibrate frame`() {
        val gatt = RecordingGatt()
        val session = OnePlusEgvSession(gatt = gatt, calibrationQueue = null)

        runOneTurn(session)

        assertThat(calibrateFrames(gatt)).isEmpty()
    }

    @Test
    fun `a queued value stays queued and unsent while the transmitter clock is unknown`() {
        val gatt = RecordingGatt()
        val queue = OnePlusCalibrationQueue()
        val now = System.currentTimeMillis()
        assertThat(queue.offer(glucoseMgdl = 150, bloodAtMs = now - 60_000L, nowMs = now)).isTrue()
        val session = OnePlusEgvSession(gatt = gatt, calibrationQueue = queue)

        runOneTurn(session)

        assertThat(calibrateFrames(gatt)).isEmpty()
        assertThat(queue.peek()?.glucoseMgdl).isEqualTo(150)
    }

    @Test
    fun `a known transmitter clock writes opcode 0x34 and then the slot is empty`() {
        val gatt = RecordingGatt()
        val queue = OnePlusCalibrationQueue()
        val now = System.currentTimeMillis()
        assertThat(queue.offer(glucoseMgdl = 150, bloodAtMs = now - 60_000L, nowMs = now)).isTrue()
        val session = OnePlusEgvSession(gatt = gatt, calibrationQueue = queue)
        setDexClock(session, seconds = 10_000, atMs = now - 30_000L)

        runOneTurn(session)

        val frames = calibrateFrames(gatt)
        assertThat(frames).hasSize(1)
        assertThat(frames.single()[0]).isEqualTo(OnePlusCalibrateTx.OPCODE)
        assertThat(queue.peek()).isNull()
    }

    @Test
    fun `a write that throws leaves no frame and reports no acceptance`() {
        val gatt = ThrowingGatt()
        val queue = OnePlusCalibrationQueue()
        val now = System.currentTimeMillis()
        assertThat(queue.offer(glucoseMgdl = 150, bloodAtMs = now - 60_000L, nowMs = now)).isTrue()
        val results = mutableListOf<OnePlusCalibrateRx?>()
        val session = OnePlusEgvSession(
            gatt = gatt,
            calibrationQueue = queue,
            onCalibrationResult = { results += it },
        )
        setDexClock(session, seconds = 10_000, atMs = now - 30_000L)

        runOneTurn(session)

        assertThat(gatt.writes).isEmpty()
        assertThat(queue.peek()).isNull()
        assertThat(results).containsExactly(null)
    }

    private fun runOneTurn(session: OnePlusEgvSession) {
        var entered = false
        session.run(
            shouldContinue = {
                if (!entered) {
                    entered = true
                    true
                } else {
                    false
                }
            },
            notifyTimeoutMs = 1L,
            rewriteIntervalMs = Long.MAX_VALUE,
        )
    }

    private fun calibrateFrames(gatt: RecordingGatt): List<ByteArray> =
        gatt.writes.filter { it.isNotEmpty() && it[0] == OnePlusCalibrateTx.OPCODE }

    private fun setDexClock(session: OnePlusEgvSession, seconds: Int, atMs: Long) {
        val secondsField = OnePlusEgvSession::class.java.getDeclaredField("lastDexTimeSeconds")
        secondsField.isAccessible = true
        secondsField.setInt(session, seconds)
        val atField = OnePlusEgvSession::class.java.getDeclaredField("lastDexTimeAtMs")
        atField.isAccessible = true
        atField.setLong(session, atMs)
    }

    private class RecordingGatt : OnePlusGattClient by OnePlusGattClientUnimplemented() {
        val writes = mutableListOf<ByteArray>()

        override fun isConnected(): Boolean = true

        override fun writeControl(payload: ByteArray?) {
            if (payload != null) writes.add(payload.copyOf())
        }

        override fun enableControlIndications() = Unit

        override fun enableControlNotifications() = Unit

        override fun awaitControlNotify(timeoutMs: Long): ByteArray? = null
    }

    private class ThrowingGatt : OnePlusGattClient by OnePlusGattClientUnimplemented() {
        val writes = mutableListOf<ByteArray>()

        override fun isConnected(): Boolean = true

        override fun writeControl(payload: ByteArray?) {
            if (payload != null && payload.isNotEmpty() && payload[0] == OnePlusCalibrateTx.OPCODE) {
                throw IllegalStateException("control busy")
            }
        }

        override fun enableControlIndications() = Unit

        override fun enableControlNotifications() = Unit

        override fun awaitControlNotify(timeoutMs: Long): ByteArray? = null
    }
}
