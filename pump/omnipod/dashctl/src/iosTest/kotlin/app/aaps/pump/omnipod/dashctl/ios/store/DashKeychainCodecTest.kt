package app.aaps.pump.omnipod.dashctl.ios.store

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.session.DashPodState
import app.aaps.pump.omnipod.dashctl.session.DashPodStateRead
import platform.Security.errSecInteractionNotAllowed
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import kotlin.test.Test
import kotlin.test.assertIs
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

    /** Only "no item" is "no pod". A locked Keychain after a reboot must not look like it. */
    @Test
    fun `only item not found is an empty store`() {
        assertEquals(DashPodStateRead.Empty, DashKeychainStateStore.readResult(errSecItemNotFound, null))
    }

    @Test
    fun `a Keychain read refused before the first unlock is unreadable and not empty`() {
        val read = DashKeychainStateStore.readResult(errSecInteractionNotAllowed, null)

        assertIs<DashPodStateRead.Unreadable>(read)
        assertEquals("Keychain read status $errSecInteractionNotAllowed", read.reason)
    }

    @Test
    fun `stored bytes that do not decode are unreadable and not empty`() {
        assertIs<DashPodStateRead.Unreadable>(DashKeychainStateStore.readResult(errSecSuccess, byteArrayOf(99, 1, 2)))
        assertIs<DashPodStateRead.Unreadable>(DashKeychainStateStore.readResult(errSecSuccess, null))
    }

    @Test
    fun `stored bytes that decode are loaded`() {
        val state = DashPodState(ltk = ByteArray(16) { 3 }, uniqueId = 5L)

        val read = DashKeychainStateStore.readResult(errSecSuccess, DashKeychainStateStore.encode(state))

        assertIs<DashPodStateRead.Loaded>(read)
        assertEquals(state, read.state)
    }
}
