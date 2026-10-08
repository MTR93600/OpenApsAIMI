package app.aaps.pump.omnipod.dashctl.ios.store

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Keychain codec roundtrip (pure Kotlin, runs on JVM).
 *
 * The Keychain itself is iOS-only; the binary format must survive
 * encode→decode with zero loss, including the LTK byte-exact.
 */
class DashKeychainCodecTest {

    @Test
    fun `full state roundtrips byte-exact`() {
        val state = DashPodState(
            ltk = ByteArray(16) { (it * 7).toByte() },
            eapAkaSequenceNumber = 123456789012345L,
            messageSequenceNumber = 15,
            uniqueId = 987654321L,
            activationProgress = ActivationProgress.COMPLETED,
            bluetoothAddress = "AA:BB:CC:DD:EE:FF"
        )
        val decoded = DashKeychainStateStore.decode(DashKeychainStateStore.encode(state))
        assertEquals(state, decoded)
        assertContentEquals(state.ltk, decoded.ltk)
    }

    @Test
    fun `empty state roundtrips`() {
        val state = DashPodState()
        val decoded = DashKeychainStateStore.decode(DashKeychainStateStore.encode(state))
        assertEquals(state, decoded)
        assertNull(decoded.ltk)
        assertNull(decoded.uniqueId)
        assertNull(decoded.bluetoothAddress)
    }

    @Test
    fun `partial state roundtrips`() {
        val state = DashPodState(
            ltk = ByteArray(16),
            eapAkaSequenceNumber = 1L,
            bluetoothAddress = "00:11:22:33:44:55"
        )
        val decoded = DashKeychainStateStore.decode(DashKeychainStateStore.encode(state))
        assertEquals(state, decoded)
    }

    @Test
    fun `truncated bytes fail closed`() {
        val state = DashPodState(ltk = ByteArray(16) { 1 })
        val encoded = DashKeychainStateStore.encode(state)
        // Truncate: decode must throw, load() maps it to null.
        try {
            DashKeychainStateStore.decode(encoded.copyOf(encoded.size - 5))
            error("expected failure on truncated input")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }

    @Test
    fun `wrong version fails closed`() {
        val encoded = DashKeychainStateStore.encode(DashPodState()).copyOf()
        encoded[0] = 99
        try {
            DashKeychainStateStore.decode(encoded)
            error("expected failure on bad version")
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}
