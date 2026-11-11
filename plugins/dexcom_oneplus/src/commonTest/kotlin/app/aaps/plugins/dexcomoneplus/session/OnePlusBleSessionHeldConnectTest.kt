package app.aaps.plugins.dexcomoneplus.session

import app.aaps.plugins.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.dexcomoneplus.oem.OemDeviceProfile
import app.aaps.plugins.dexcomoneplus.reconnect.OnePlusReconnectPolicy
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The iPhone reconnect: a connect that iOS holds open while the app sleeps, and never a scan wait.
 *
 * A scan wait ends when iOS suspends the app, and nothing wakes it again, so with the screen off the
 * glucose stopped. See `OemDeviceProfile.holdsPendingConnect`.
 */
class OnePlusBleSessionHeldConnectTest {

    private val sensor = "PERIPHERAL-1"

    private object NoRetryPause : OnePlusReconnectPolicy {
        override fun shouldRetry(attempt: Int, profile: OemDeviceProfile): Boolean = true
        override fun nextDelayMs(attempt: Int, profile: OemDeviceProfile): Long = 0L
    }

    /** Fails every handshake like a link lost in the middle, and stops the session at call [stopAt]. */
    private class FailingAuth(private val stopAt: Int) : OnePlusSessionAuth {
        var calls = 0
        lateinit var session: OnePlusBleSession
        override fun authenticate(pairingCode: String, savedSharedKey: ByteArray?): AuthResult {
            calls++
            if (calls >= stopAt) session.stop("test done")
            return AuthResult(ok = false, message = "ONEPLUS_AUTH: GATT disconnected")
        }
    }

    private class Run(val gatt: FakeOnePlusGatt, val auth: FailingAuth, val scanWaits: List<Long>)

    private fun run(profile: OemDeviceProfile, stopAt: Int): Run {
        val gatt = FakeOnePlusGatt()
        val auth = FailingAuth(stopAt)
        val scanWaits = mutableListOf<Long>()
        val session = OnePlusBleSessionSkeleton(
            gatt = gatt,
            auth = auth,
            reconnectPolicy = NoRetryPause,
            profile = profile,
            // A sensor that was already running: the reconnect path, not a new start.
            requestNewSensorStart = false,
            beforeConnect = { _, _, scanMs ->
                if (scanMs != null) scanWaits += scanMs
                if (profile.holdsPendingConnect) OnePlusConnectPrep(pendingConnect = true) else OnePlusConnectPrep(advFresh = true)
            },
        )
        auth.session = session
        session.startWithPairingCode(sensor, "1234")
        return Run(gatt, auth, scanWaits)
    }

    @Test
    fun `on iPhone the first connect is a held connect with no scan`() {
        val run = run(DeviceProfileRegistry.AppleDefault, stopAt = 1)

        assertEquals(1, run.gatt.connects)
        assertEquals(listOf(true), run.gatt.autoConnects)
        assertEquals(emptyList(), run.scanWaits)
    }

    @Test
    fun `on iPhone a failed connect is held again and never turns into a scan wait`() {
        val run = run(DeviceProfileRegistry.AppleDefault, stopAt = 3)

        assertEquals(3, run.auth.calls)
        assertEquals(listOf(true, true, true), run.gatt.autoConnects)
        assertEquals(emptyList(), run.scanWaits)
    }

    @Test
    fun `an Android profile still waits for the advertisement with scans`() {
        val run = run(DeviceProfileRegistry.SamsungDefault, stopAt = 3)

        assertEquals(3, run.auth.calls)
        // The first failure is followed by one autoConnect park without a scan; after the park fails
        // too, the persistent wait scans for the next advertisement, as before.
        assertEquals(listOf(OnePlusBleSessionCyclePolicy.PERSISTENT_ADV_SCAN_MS), run.scanWaits)
    }
}
