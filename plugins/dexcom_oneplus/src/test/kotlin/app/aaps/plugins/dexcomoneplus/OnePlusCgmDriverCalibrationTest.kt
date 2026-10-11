package app.aaps.plugins.dexcomoneplus

import app.aaps.plugins.dexcomoneplus.session.OnePlusBleSession
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
    fun `nothing is queued without a session`() {
        // No sensor is being followed at all, so nothing would ever take the value. Refusing now is
        // what lets the user try again later.
        val production = OnePlusCgmDriverReal()

        assertThat(production.offerCalibration(glucoseMgdl = 209, bloodAtMs = now)).isFalse()
        assertThat(production.calibrationPending()).isFalse()
    }

    @Test
    fun `a session between two radio windows still takes the value`() {
        // A G7 / ONE+ is connected a few seconds every 5 minutes. Between two windows the session is
        // not "up", and refusing there refused almost every fingerstick (field report 2026-10-11).
        val production = OnePlusCgmDriverReal()
        setSession(production, FakeSession(up = false))

        assertThat(production.offerCalibration(glucoseMgdl = 94, bloodAtMs = now)).isTrue()
        assertThat(production.calibrationPending()).isTrue()
    }

    @Test
    fun `a promoted pre-soak instance takes the value`() {
        // A promotion keeps the instance and its session, and only rebinds the store to production.
        val promoted = OnePlusCgmDriverReal(storeNamespace = OnePlusCgmDrivers.STAGING_NAMESPACE)
        setSession(promoted, FakeSession(up = false))
        promoted.rebindStore(null)

        assertThat(promoted.offerCalibration(glucoseMgdl = 94, bloodAtMs = now)).isTrue()
        assertThat(promoted.calibrationPending()).isTrue()
    }

    @Test
    fun `a pre-soak with a running session is still never calibrated`() {
        val staging = OnePlusCgmDriverReal(storeNamespace = OnePlusCgmDrivers.STAGING_NAMESPACE)
        setSession(staging, FakeSession(up = true))

        assertThat(staging.offerCalibration(glucoseMgdl = 94, bloodAtMs = now)).isFalse()
        assertThat(staging.calibrationPending()).isFalse()
    }

    /** The real session needs Android BLE; only its presence matters to [OnePlusCgmDriverReal.offerCalibration]. */
    private class FakeSession(private val up: Boolean) : OnePlusBleSession {
        override fun startWithPairingCode(deviceAddress: String, pairingCode: String) = Unit
        override fun stop(reason: String?) = Unit
        override fun isUp(): Boolean = up
        override fun warmupState(): OnePlusWarmupState = OnePlusWarmupState(phase = OnePlusWarmupState.Phase.READY)
    }

    private fun setSession(driver: OnePlusCgmDriverReal, session: OnePlusBleSession) {
        OnePlusCgmDriverReal::class.java.getDeclaredField("session").apply { isAccessible = true }.set(driver, session)
    }

    @Test
    fun `the stub driver takes nothing and says so`() {
        val stub = OnePlusCgmDriverStub()

        assertThat(stub.offerCalibration(glucoseMgdl = 209, bloodAtMs = now)).isFalse()
        assertThat(stub.lastCalibrationOutcome()).isNull()
        assertThat(stub.calibrationPending()).isFalse()
    }
}
