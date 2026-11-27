package app.aaps.plugins.dexcomoneplus.session

import app.aaps.plugins.dexcomoneplus.oem.DeviceProfileRegistry
import app.aaps.plugins.dexcomoneplus.oem.OemDeviceProfile
import app.aaps.plugins.dexcomoneplus.reconnect.OnePlusReconnectPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The stale bond rule and the user's repair, see [OnePlusBondRepair].
 */
class OnePlusBondRepairTest {

    @Test
    fun `full pairings on a bonded link that end before any Control traffic count up`() {
        var count = 0
        count = OnePlusBondRepair.staleCycles(count, fullPairing = true, osBonded = true, provedHealthy = false)
        count = OnePlusBondRepair.staleCycles(count, fullPairing = true, osBonded = true, provedHealthy = false)
        assertEquals(2, count)
        assertEquals(OnePlusBondRepairState.BROKEN, OnePlusBondRepair.next(OnePlusBondRepairState.NONE, count, provedHealthy = false))
    }

    @Test
    fun `one stale cycle is not enough`() {
        val count = OnePlusBondRepair.staleCycles(0, fullPairing = true, osBonded = true, provedHealthy = false)
        assertEquals(OnePlusBondRepairState.NONE, OnePlusBondRepair.next(OnePlusBondRepairState.NONE, count, provedHealthy = false))
    }

    @Test
    fun `a healthy cycle clears the count and the state`() {
        assertEquals(0, OnePlusBondRepair.staleCycles(5, fullPairing = true, osBonded = true, provedHealthy = true))
        assertEquals(OnePlusBondRepairState.NONE, OnePlusBondRepair.next(OnePlusBondRepairState.BROKEN, 0, provedHealthy = true))
        assertEquals(OnePlusBondRepairState.NONE, OnePlusBondRepair.next(OnePlusBondRepairState.REPAIRING, 0, provedHealthy = true))
    }

    @Test
    fun `a saved key or no bond does not count`() {
        // A sensor with no session also drops the link after the handshake: with a saved key that is no stale bond.
        assertEquals(1, OnePlusBondRepair.staleCycles(1, fullPairing = false, osBonded = true, provedHealthy = false))
        // A first pairing, or the pairing after a repair, has no bond yet.
        assertEquals(1, OnePlusBondRepair.staleCycles(1, fullPairing = true, osBonded = false, provedHealthy = false))
    }

    @Test
    fun `a repair stays until a healthy cycle, unless the bond goes stale again`() {
        assertEquals(OnePlusBondRepairState.REPAIRING, OnePlusBondRepair.next(OnePlusBondRepairState.REPAIRING, 1, provedHealthy = false))
        assertEquals(OnePlusBondRepairState.BROKEN, OnePlusBondRepair.next(OnePlusBondRepairState.REPAIRING, 2, provedHealthy = false))
    }

    private object NoRetryPause : OnePlusReconnectPolicy {
        override fun shouldRetry(attempt: Int, profile: OemDeviceProfile): Boolean = true
        override fun nextDelayMs(attempt: Int, profile: OemDeviceProfile): Long = 0L
    }

    /**
     * The field case: every full pairing is accepted, then the sensor closes the link before any
     * Control traffic. The user asks for a repair during call [repairAt]; the session stops at call
     * [stopAt].
     */
    private class AcceptedThenDropped(
        private val gatt: FakeOnePlusGatt,
        private val repairAt: Int,
        private val stopAt: Int,
    ) : OnePlusSessionAuth {

        var calls = 0
        lateinit var session: OnePlusBleSession

        override fun authenticate(pairingCode: String, savedSharedKey: ByteArray?): AuthResult {
            calls++
            if (calls == repairAt) session.requestBondRepair()
            if (calls >= stopAt) session.stop("test done")
            gatt.connected = false
            return AuthResult(ok = true, message = "keks_ok", sharedKey = ByteArray(16) { 1 })
        }
    }

    @Test
    fun `the session reports a broken bond and removes it only when asked, between two links`() {
        val gatt = FakeOnePlusGatt()
        val auth = AcceptedThenDropped(gatt, repairAt = 3, stopAt = 4)
        val states = mutableListOf<OnePlusBondRepairState>()
        val session = OnePlusBleSessionSkeleton(
            gatt = gatt,
            auth = auth,
            reconnectPolicy = NoRetryPause,
            profile = DeviceProfileRegistry.GenericFallback,
            requestNewSensorStart = false,
            beforeConnect = { _, _, _ -> OnePlusConnectPrep(advFresh = true) },
            postCycleAdvGuardMs = 1L,
            onBondRepair = { states += it },
        )
        auth.session = session

        session.startWithPairingCode("AA:BB:CC:DD:EE:FF", "1234")

        // Said at start, broken after the second stale cycle, repairing once the bond was removed after the third.
        assertEquals(listOf(OnePlusBondRepairState.NONE, OnePlusBondRepairState.BROKEN, OnePlusBondRepairState.REPAIRING), states)
        // Removed once, and never while a link was up.
        assertEquals(listOf(false), gatt.bondRemovalsWhileConnected)
        assertTrue(!gatt.bonded)
    }

    @Test
    fun `the session never removes the bond by itself`() {
        val gatt = FakeOnePlusGatt()
        val auth = AcceptedThenDropped(gatt, repairAt = Int.MAX_VALUE, stopAt = 5)
        val session = OnePlusBleSessionSkeleton(
            gatt = gatt,
            auth = auth,
            reconnectPolicy = NoRetryPause,
            profile = DeviceProfileRegistry.GenericFallback,
            requestNewSensorStart = false,
            beforeConnect = { _, _, _ -> OnePlusConnectPrep(advFresh = true) },
            postCycleAdvGuardMs = 1L,
        )
        auth.session = session

        session.startWithPairingCode("AA:BB:CC:DD:EE:FF", "1234")

        assertEquals(OnePlusBondRepairState.BROKEN, session.bondRepairState())
        assertEquals(emptyList(), gatt.bondRemovalsWhileConnected)
    }

    /** Every handshake fails while the link stays up, as after a failed cycle that is not the stale case. */
    private class RefusedLinkUp(private val repairAt: Int, private val stopAt: Int) : OnePlusSessionAuth {

        var calls = 0
        lateinit var session: OnePlusBleSession

        override fun authenticate(pairingCode: String, savedSharedKey: ByteArray?): AuthResult {
            calls++
            if (calls == repairAt) session.requestBondRepair()
            if (calls >= stopAt) session.stop("test done")
            return AuthResult(ok = false, message = "test handshake failed")
        }
    }

    @Test
    fun `a new session starts from the state of the session before it, and says it`() {
        val gatt = FakeOnePlusGatt()
        val auth = RefusedLinkUp(repairAt = Int.MAX_VALUE, stopAt = 1)
        val states = mutableListOf<OnePlusBondRepairState>()
        val session = session(gatt, auth, initial = OnePlusBondRepairState.BROKEN, states = states)
        auth.session = session

        session.startWithPairingCode("AA:BB:CC:DD:EE:FF", "1234")

        assertEquals(OnePlusBondRepairState.BROKEN, states.first())
        assertEquals(OnePlusBondRepairState.BROKEN, session.bondRepairState())
    }

    private fun session(
        gatt: FakeOnePlusGatt,
        auth: OnePlusSessionAuth,
        initial: OnePlusBondRepairState = OnePlusBondRepairState.NONE,
        states: MutableList<OnePlusBondRepairState> = mutableListOf(),
    ) = OnePlusBleSessionSkeleton(
        gatt = gatt,
        auth = auth,
        reconnectPolicy = NoRetryPause,
        profile = DeviceProfileRegistry.GenericFallback,
        requestNewSensorStart = false,
        beforeConnect = { _, _, _ -> OnePlusConnectPrep(advFresh = true) },
        postCycleAdvGuardMs = 1L,
        onBondRepair = { states += it },
        initialBondRepair = initial,
    )
}
