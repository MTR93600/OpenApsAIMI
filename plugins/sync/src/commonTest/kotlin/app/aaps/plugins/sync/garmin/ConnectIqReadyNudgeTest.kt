package app.aaps.plugins.sync.garmin

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectIqReadyNudgeTest {

    private var now = 0L
    private val nudge = ConnectIqReadyNudge(nowMs = { now })

    @Test
    fun aWatchThatBecomesReadyIsNotRegisteredAgain() {
        assertTrue(nudge.onStatus("WATCH", ConnectIqDeviceStatus.CONNECTED))
        now = 10_000L
        nudge.onStatus("WATCH", ConnectIqDeviceStatus.READY)
        now = 20_000L
        assertFalse(nudge.shouldReregister("WATCH"))
    }

    @Test
    fun aWatchLeftConnectedIsRegisteredAgainOnce() {
        assertTrue(nudge.onStatus("WATCH", ConnectIqDeviceStatus.CONNECTED))
        assertFalse(nudge.onStatus("WATCH", ConnectIqDeviceStatus.CONNECTED))
        now = 14_999L
        assertFalse(nudge.shouldReregister("WATCH"))
        now = 15_000L
        assertTrue(nudge.shouldReregister("WATCH"))
        assertFalse(nudge.shouldReregister("WATCH"))
    }
}
