package app.aaps.plugins.dexcomoneplus

import app.aaps.plugins.dexcomoneplus.session.OnePlusBleSession
import app.aaps.plugins.dexcomoneplus.session.OnePlusCalibrationQueue
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Who may hand a fingerstick to a sensor.
 *
 * The refusals checked here cannot be undone if they are wrong: a sensor keeps a calibration for
 * good. Each refusal asserts the queue is still empty, or still holding the value that was already
 * waiting — nothing new was accepted.
 *
 * Provenance: ref `OnePlusCgmDriverCalibrationTest` at `3dd0ca64772`. The session-up cases are the
 * plan's range and occupied-queue refusals, which the driver only reaches once a session is up.
 */
class OnePlusCgmDriverCalibrationTest {

    private val now = System.currentTimeMillis()

    @Test
    fun `a pre-soak sensor is never calibrated`() {
        val staging = OnePlusCgmDriverReal(storeNamespace = OnePlusCgmDrivers.STAGING_NAMESPACE)
        markSessionUp(staging)

        assertThat(staging.offerCalibration(glucoseMgdl = 209, bloodAtMs = now)).isFalse()
        assertThat(staging.calibrationPending()).isFalse()
        assertThat(staging.lastCalibrationOutcome()).isNull()
        assertThat(queueOf(staging).peek()).isNull()
    }

    @Test
    fun `a promoted instance reads the live namespace`() {
        val staging = OnePlusCgmDriverReal(storeNamespace = OnePlusCgmDrivers.STAGING_NAMESPACE)
        markSessionUp(staging)
        assertThat(staging.offerCalibration(glucoseMgdl = 209, bloodAtMs = now)).isFalse()
        assertThat(queueOf(staging).peek()).isNull()

        staging.rebindStore(null)

        assertThat(staging.offerCalibration(glucoseMgdl = 209, bloodAtMs = now)).isTrue()
        assertThat(staging.calibrationPending()).isTrue()
        assertThat(queueOf(staging).peek()?.glucoseMgdl).isEqualTo(209)
    }

    @Test
    fun `nothing is queued without a session`() {
        // No sensor is being followed at all, so nothing would ever take the value.
        val production = OnePlusCgmDriverReal()

        assertThat(production.offerCalibration(glucoseMgdl = 209, bloodAtMs = now)).isFalse()
        assertThat(production.calibrationPending()).isFalse()
        assertThat(production.lastCalibrationOutcome()).isNull()
        assertThat(queueOf(production).peek()).isNull()
    }

    @Test
    fun `a session between two radio windows still takes the value`() {
        // A G7 / ONE+ is connected a few seconds every 5 minutes. Between two windows the session is
        // not "up", and refusing there refused almost every fingerstick (field report 2026-10-11).
        val production = OnePlusCgmDriverReal()
        setSession(production, DownSession)

        assertThat(production.offerCalibration(glucoseMgdl = 94, bloodAtMs = now)).isTrue()
        assertThat(production.calibrationPending()).isTrue()
    }

    @Test
    fun `a promoted pre-soak instance takes the value`() {
        // A promotion keeps the instance and its session, and only rebinds the store to production.
        val promoted = OnePlusCgmDriverReal(storeNamespace = OnePlusCgmDrivers.STAGING_NAMESPACE)
        setSession(promoted, DownSession)
        promoted.rebindStore(null)

        assertThat(promoted.offerCalibration(glucoseMgdl = 94, bloodAtMs = now)).isTrue()
        assertThat(promoted.calibrationPending()).isTrue()
    }

    @Test
    fun `a value outside 40 to 400 is not queued`() {
        val production = OnePlusCgmDriverReal()
        markSessionUp(production)

        assertThat(production.offerCalibration(glucoseMgdl = 39, bloodAtMs = now)).isFalse()
        assertThat(production.offerCalibration(glucoseMgdl = 401, bloodAtMs = now)).isFalse()
        assertThat(production.calibrationPending()).isFalse()
        assertThat(production.lastCalibrationOutcome()).isNull()
        assertThat(queueOf(production).peek()).isNull()
    }

    @Test
    fun `a second fingerstick does not replace the one already waiting`() {
        val production = OnePlusCgmDriverReal()
        markSessionUp(production)

        assertThat(production.offerCalibration(glucoseMgdl = 150, bloodAtMs = now)).isTrue()
        assertThat(production.offerCalibration(glucoseMgdl = 160, bloodAtMs = now)).isFalse()
        assertThat(production.calibrationPending()).isTrue()
        assertThat(production.lastCalibrationOutcome()).isEqualTo(OnePlusCalibrationOutcome.Pending)
        assertThat(queueOf(production).peek()?.glucoseMgdl).isEqualTo(150)
    }

    @Test
    fun `the stub driver takes nothing and says so`() {
        val stub = OnePlusCgmDriverStub()

        assertThat(stub.offerCalibration(glucoseMgdl = 209, bloodAtMs = now)).isFalse()
        assertThat(stub.lastCalibrationOutcome()).isNull()
        assertThat(stub.calibrationPending()).isFalse()
    }

    private fun markSessionUp(driver: OnePlusCgmDriverReal) = setSession(driver, UpSession)

    private fun setSession(driver: OnePlusCgmDriverReal, session: OnePlusBleSession) {
        val field = OnePlusCgmDriverReal::class.java.getDeclaredField("session")
        field.isAccessible = true
        field.set(driver, session)
    }

    private fun queueOf(driver: OnePlusCgmDriverReal): OnePlusCalibrationQueue {
        val field = OnePlusCgmDriverReal::class.java.getDeclaredField("calibrationQueue")
        field.isAccessible = true
        return field.get(driver) as OnePlusCalibrationQueue
    }

    private object UpSession : FakeSession(up = true)

    private object DownSession : FakeSession(up = false)

    private open class FakeSession(private val up: Boolean) : OnePlusBleSession {
        override fun startWithPairingCode(deviceAddress: String, pairingCode: String) = Unit
        override fun stop(reason: String?) = Unit
        override fun isUp(): Boolean = up
        override fun warmupState(): OnePlusWarmupState =
            OnePlusWarmupState(phase = OnePlusWarmupState.Phase.READY)
    }
}
