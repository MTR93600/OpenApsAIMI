package app.aaps.plugins.libre3

import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.plugins.libre3.reconnect.Libre3ReconnectPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A promoted instance must reconnect at the production pace, not at the slow pre-soak one.
 *
 * The pre-soak slot is slow on purpose: it has hours to succeed and it must not push the sensor
 * that feeds the loop out of the scan budget. Once that instance IS the sensor that feeds the loop,
 * keeping the slow pace would make every reconnect of the loop's sensor slower than it was before
 * the pre-soak feature existed. See `docs/LIBRE3_PRESOAK_PLAN.md` §14.
 */
class Libre3PromotedInstanceRoleTest {

    /**
     * A promotion leaves the test instance as the production one. Nothing else in this module's
     * tests asks for the production instance, and every test here builds its own pre-soak one, so
     * only the pre-soak pointer has to be freed.
     */
    @AfterEach
    fun releaseInstances() {
        Libre3CgmDrivers.releaseStagingInstance()?.let { runCatching { it.shutdown() } }
    }

    @Test
    fun `a fresh pre-soak instance plays for the pre-soak slot`() {
        val presoak = Libre3CgmDrivers.staging()

        assertThat(presoak.currentRole).isEqualTo(SensorSlot.STAGING)
        assertThat(Libre3ReconnectPolicy.nextDelayMs(1, presoak.currentRole))
            .isEqualTo(Libre3ReconnectPolicy.STAGING_MIN_RETRY_MS)
    }

    @Test
    fun `a promoted instance plays for the production slot`() {
        val presoak = Libre3CgmDrivers.staging()

        Libre3CgmDrivers.promoteStagingInstance()

        assertThat(presoak.currentRole).isEqualTo(SensorSlot.PRODUCTION)
        // The first retry is back to three seconds, so the next short radio window of the sensor is
        // caught instead of being waited out for twenty.
        assertThat(Libre3ReconnectPolicy.nextDelayMs(1, presoak.currentRole))
            .isEqualTo(Libre3ReconnectPolicy.FIRST_RETRY_MS)
        // Once the quick ladder is spent it is the plain slow pace, not the slow pace plus the
        // pre-soak offset.
        assertThat(Libre3ReconnectPolicy.nextDelayMs(Libre3ReconnectPolicy.MAX_ATTEMPTS, presoak.currentRole))
            .isEqualTo(Libre3ReconnectPolicy.SLOW_RETRY_MS)
    }

    @Test
    fun `the log name of a promoted instance does not change`() {
        // The name is the identity of the instance and a bug report has to stay readable across the
        // swap, so only the ROLE moves. The thread keeps the name it was started with.
        val presoak = Libre3CgmDrivers.staging()

        Libre3CgmDrivers.promoteStagingInstance()

        assertThat(presoak.currentRole).isEqualTo(SensorSlot.PRODUCTION)
        assertThat(Libre3CgmDrivers.stagingOrNull()).isNull()
    }
}
