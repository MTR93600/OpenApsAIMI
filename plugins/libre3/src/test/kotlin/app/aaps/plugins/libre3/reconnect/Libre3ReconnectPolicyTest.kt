package app.aaps.plugins.libre3.reconnect

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** How long the driver waits, and when it stops trying and asks for a new NFC scan. */
class Libre3ReconnectPolicyTest {

    @Test
    fun `the first retry is quick, so the next radio window is caught`() {
        assertThat(Libre3ReconnectPolicy.nextDelayMs(1)).isEqualTo(Libre3ReconnectPolicy.FIRST_RETRY_MS)
    }

    @Test
    fun `waits grow but never pass the upper bound`() {
        var previous = 0L
        for (attempt in 1..20) {
            val delay = Libre3ReconnectPolicy.nextDelayMs(attempt)

            assertThat(delay).isAtMost(Libre3ReconnectPolicy.MAX_DELAY_MS)
            assertThat(delay).isAtLeast(previous)
            previous = delay
        }
    }

    @Test
    fun `a few early failures only lead to another short reconnect`() {
        val action = Libre3ReconnectPolicy.actionAfterFailure(attempt = 2, handshakeReached = false)

        assertThat(action).isEqualTo(Libre3RecoveryAction.RETRY_CACHED_RECONNECT)
    }

    @Test
    fun `a sensor that answered and then refused is not retried with the same key`() {
        val action = Libre3ReconnectPolicy.actionAfterFailure(attempt = 1, handshakeReached = true)

        assertThat(action).isEqualTo(Libre3RecoveryAction.ASK_FOR_NFC_SCAN)
    }

    @Test
    fun `after enough failures the user is asked to scan the sensor again`() {
        val action = Libre3ReconnectPolicy.actionAfterFailure(
            attempt = Libre3ReconnectPolicy.MAX_ATTEMPTS,
            handshakeReached = false,
        )

        assertThat(action).isEqualTo(Libre3RecoveryAction.ASK_FOR_NFC_SCAN)
    }

    @Test
    fun `giving up never means trying a first pairing`() {
        // There are only two ways out, and neither of them is the first pairing command.
        assertThat(Libre3RecoveryAction.entries).hasSize(2)
        assertThat(Libre3RecoveryAction.entries.map { it.name })
            .containsExactly("RETRY_CACHED_RECONNECT", "ASK_FOR_NFC_SCAN")
    }
}
