package app.aaps.plugins.dexcomoneplus

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Who may hand a fingerstick to a sensor.
 *
 * The two refusals checked here are the ones that cannot be undone if they are wrong: a sensor keeps
 * a calibration for good, so sending one to the pre-soak sensor would spoil a sensor that has not
 * even started its life with the loop yet.
 */
class OnePlusCgmDriverCalibrationTest {

    private val now = System.currentTimeMillis()

    @Test
    fun `a pre-soak sensor is never calibrated`() {
        val staging = OnePlusCgmDriverReal(storeNamespace = OnePlusCgmDrivers.STAGING_NAMESPACE)

        assertThat(staging.offerCalibration(glucoseMgdl = 209, bloodAtMs = now)).isFalse()
        assertThat(staging.calibrationPending()).isFalse()
        assertThat(staging.lastCalibrationOutcome()).isNull()
    }

    @Test
    fun `nothing is queued while no session is up`() {
        // Without a live link the value would sit in the slot and go stale, and the user would be
        // told it was on its way. Refusing now is what lets them try again later.
        val production = OnePlusCgmDriverReal()

        assertThat(production.offerCalibration(glucoseMgdl = 209, bloodAtMs = now)).isFalse()
        assertThat(production.calibrationPending()).isFalse()
    }

    @Test
    fun `the stub driver takes nothing and says so`() {
        val stub = OnePlusCgmDriverStub()

        assertThat(stub.offerCalibration(glucoseMgdl = 209, bloodAtMs = now)).isFalse()
        assertThat(stub.lastCalibrationOutcome()).isNull()
        assertThat(stub.calibrationPending()).isFalse()
    }
}
