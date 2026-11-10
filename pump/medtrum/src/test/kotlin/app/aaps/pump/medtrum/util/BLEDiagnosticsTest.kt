package app.aaps.pump.medtrum.util

import android.bluetooth.BluetoothGatt
import app.aaps.core.interfaces.logging.AAPSLogger
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.MockitoAnnotations
import org.mockito.kotlin.mock

class BLEDiagnosticsTest {

    @Mock
    private lateinit var aapsLogger: AAPSLogger

    private lateinit var diagnostics: BLEDiagnostics

    @BeforeEach
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        diagnostics = BLEDiagnostics(aapsLogger)
    }

    @Test
    fun checkForZombieState_returnsFalseForRecentConnectingActivity() {
        val now = System.currentTimeMillis()
        val result = diagnostics.checkForZombieState(
            gatt = mock(),
            isConnected = false,
            isConnecting = true,
            lastActivityTimestamp = now - 5_000L,
            connectingStaleThresholdMs = 30_000L
        )
        assertFalse(result)
    }

    @Test
    fun checkForZombieState_detectsConnectingZombie() {
        val now = System.currentTimeMillis()
        val result = diagnostics.checkForZombieState(
            gatt = mock(),
            isConnected = false,
            isConnecting = true,
            lastActivityTimestamp = now - 60_000L,
            connectingStaleThresholdMs = 30_000L
        )
        assertTrue(result)
    }

    @Test
    fun checkForZombieState_detectsConnectedStaleZombie() {
        val now = System.currentTimeMillis()
        val result = diagnostics.checkForZombieState(
            gatt = mock(),
            isConnected = true,
            isConnecting = false,
            lastActivityTimestamp = now - 120_000L,
            connectedStaleThresholdMs = 90_000L
        )
        assertTrue(result)
    }

    @Test
    fun checkForZombieState_detectsInconsistentGattState() {
        val result = diagnostics.checkForZombieState(
            gatt = mock(),
            isConnected = false,
            isConnecting = false,
            lastActivityTimestamp = System.currentTimeMillis()
        )
        assertTrue(result)
    }
}
