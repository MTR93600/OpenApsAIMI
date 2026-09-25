package app.aaps.plugins.source

import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.core.interfaces.source.StagingState
import app.aaps.plugins.libre3.Libre3GlucoseSample
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * The rules of the Libre 3 pre-soak slot, without Android.
 *
 * See `docs/LIBRE3_PRESOAK_PLAN.md` §7, §8 and §15.1.
 */
class Libre3StagingTest {

    private val now = 1_777_216_508_000L
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour

    private fun sample(mgdl: Double, lifeCount: Int) =
        Libre3GlucoseSample(mgdl = mgdl, timestampMs = now, lifeCount = lifeCount)

    // ---- computeLifecycle ----

    @Test
    fun `lifecycle is null when the activation time is not known`() {
        assertThat(Libre3Staging.computeLifecycle(SensorSlot.PRODUCTION, 0L, null, now)).isNull()
    }

    @Test
    fun `a sensor one hour old is early life and not end of life`() {
        val lifecycle = Libre3Staging.computeLifecycle(SensorSlot.PRODUCTION, now - hour, null, now)!!

        assertThat(lifecycle.earlyLife).isTrue()
        assertThat(lifecycle.endOfLife).isFalse()
        assertThat(lifecycle.ageMs).isEqualTo(hour)
    }

    @Test
    fun `a sensor thirteen hours old is no longer early life`() {
        val lifecycle = Libre3Staging.computeLifecycle(SensorSlot.STAGING, now - 13 * hour, null, now)!!

        assertThat(lifecycle.earlyLife).isFalse()
        assertThat(lifecycle.slot).isEqualTo(SensorSlot.STAGING)
    }

    @Test
    fun `a sensor within twelve hours of its end is end of life`() {
        // Placed so that six hours are left of life plus grace, which is inside the twelve hour
        // end of life window.
        val activatedAt = now - (Libre3Staging.DEFAULT_LIFE_MS + Libre3Staging.SENSOR_GRACE_MS - 6 * hour)
        val lifecycle = Libre3Staging.computeLifecycle(SensorSlot.PRODUCTION, activatedAt, null, now)!!

        assertThat(lifecycle.endOfLife).isTrue()
        assertThat(lifecycle.earlyLife).isFalse()
    }

    @Test
    fun `an unknown wear time falls back to fourteen days`() {
        val activatedAt = now - hour
        val lifecycle = Libre3Staging.computeLifecycle(SensorSlot.PRODUCTION, activatedAt, null, now)!!

        assertThat(lifecycle.expiresAtEpochMs)
            .isEqualTo(activatedAt + 14 * day + Libre3Staging.SENSOR_GRACE_MS)
    }

    @Test
    fun `a Libre 3 Plus wear time moves the end out by one day`() {
        val activatedAt = now - hour
        val plus = Libre3Staging.computeLifecycle(SensorSlot.PRODUCTION, activatedAt, 15 * 24 * 60, now)!!
        val normal = Libre3Staging.computeLifecycle(SensorSlot.PRODUCTION, activatedAt, null, now)!!

        assertThat(plus.expiresAtEpochMs!! - normal.expiresAtEpochMs!!).isEqualTo(day)
    }

    @Test
    fun `a wear time of zero falls back too`() {
        // The NFC scan reports 0 when the sensor did not tell it, so 0 must not mean "already over".
        val activatedAt = now - hour
        val lifecycle = Libre3Staging.computeLifecycle(SensorSlot.PRODUCTION, activatedAt, 0, now)!!

        assertThat(lifecycle.expiresAtEpochMs)
            .isEqualTo(activatedAt + 14 * day + Libre3Staging.SENSOR_GRACE_MS)
    }

    // ---- computeStagingState ----

    @Test
    fun `the slot is absent when there is no pre-soak sensor`() {
        assertThat(Libre3Staging.computeStagingState(present = false, warming = false, validReadingCount = 99))
            .isEqualTo(StagingState.ABSENT)
    }

    @Test
    fun `the slot is in warm-up while it is warming`() {
        assertThat(Libre3Staging.computeStagingState(present = true, warming = true, validReadingCount = 0))
            .isEqualTo(StagingState.WARMUP)
    }

    @Test
    fun `the slot is settling below five readings`() {
        assertThat(Libre3Staging.computeStagingState(present = true, warming = false, validReadingCount = 4))
            .isEqualTo(StagingState.SETTLING)
    }

    @Test
    fun `the slot is ready at exactly five readings and stays ready above`() {
        assertThat(Libre3Staging.computeStagingState(present = true, warming = false, validReadingCount = 5))
            .isEqualTo(StagingState.READY)
        assertThat(Libre3Staging.computeStagingState(present = true, warming = false, validReadingCount = 500))
            .isEqualTo(StagingState.READY)
    }

    @Test
    fun `the slot state has no time argument, so no soak gate can creep back in`() {
        // Decision D1: the user already pays real sensor wear time for the soak, so the app must
        // not veto on top of that. A time argument here would be the first step back to a gate.
        val parameterNames = Libre3Staging::class.java
            .methods
            .first { it.name.startsWith("computeStagingState") }
            .parameterTypes
            .map { it.simpleName }

        assertThat(parameterNames).containsExactly("boolean", "boolean", "int").inOrder()
    }

    // ---- applyWarmupPhase ----

    @Test
    fun `a latched warm-up stays done through a reconnect`() {
        val decision = Libre3Staging.applyWarmupPhase(warmupDoneBefore = true, readyPhase = false)

        assertThat(decision.warmupDone).isTrue()
        assertThat(decision.warming).isFalse()
    }

    @Test
    fun `the first ready phase latches the warm-up`() {
        val decision = Libre3Staging.applyWarmupPhase(warmupDoneBefore = false, readyPhase = true)

        assertThat(decision.warmupDone).isTrue()
        assertThat(decision.warming).isFalse()
    }

    @Test
    fun `a slot that never warmed up still counts as warming on a silent radio`() {
        val decision = Libre3Staging.applyWarmupPhase(warmupDoneBefore = false, readyPhase = false)

        assertThat(decision.warmupDone).isFalse()
        assertThat(decision.warming).isTrue()
    }

    // ---- isSameSensor ----

    @Test
    fun `the same serial is the same sensor even with another MAC`() {
        assertThat(
            Libre3Staging.isSameSensor("MH0123456", "AA:BB:CC:DD:EE:01", "MH0123456", "AA:BB:CC:DD:EE:99")
        ).isTrue()
    }

    @Test
    fun `the same MAC is the same sensor even with another serial`() {
        assertThat(
            Libre3Staging.isSameSensor("MH0123456", "AA:BB:CC:DD:EE:01", "MH0999999", "AA:BB:CC:DD:EE:01")
        ).isTrue()
    }

    @Test
    fun `two different sensors are not the same sensor`() {
        assertThat(
            Libre3Staging.isSameSensor("MH0123456", "AA:BB:CC:DD:EE:01", "MH0999999", "AA:BB:CC:DD:EE:99")
        ).isFalse()
    }

    @Test
    fun `capitals do not make a different sensor`() {
        assertThat(
            Libre3Staging.isSameSensor("mh0123456", "aa:bb:cc:dd:ee:01", "MH0123456", "ZZ:ZZ:ZZ:ZZ:ZZ:ZZ")
        ).isTrue()
        assertThat(
            Libre3Staging.isSameSensor("ZZZ", "aa:bb:cc:dd:ee:01", "YYY", "AA:BB:CC:DD:EE:01")
        ).isTrue()
    }

    @Test
    fun `a missing or blank value never matches`() {
        assertThat(Libre3Staging.isSameSensor(null, null, "MH0123456", "AA:BB:CC:DD:EE:01")).isFalse()
        assertThat(Libre3Staging.isSameSensor("MH0123456", "AA:BB:CC:DD:EE:01", null, null)).isFalse()
        // A blank must never match a blank, or two empty stores would look like one sensor.
        assertThat(Libre3Staging.isSameSensor("  ", "  ", "  ", "  ")).isFalse()
    }

    // ---- acceptForCurve ----

    @Test
    fun `the first pre-soak sample is taken`() {
        assertThat(Libre3Staging.acceptForCurve(-1, sample(120.0, 70))).isTrue()
    }

    @Test
    fun `the same life counter is not taken twice`() {
        assertThat(Libre3Staging.acceptForCurve(70, sample(120.0, 70))).isFalse()
    }

    @Test
    fun `a lower life counter is refused`() {
        assertThat(Libre3Staging.acceptForCurve(70, sample(120.0, 69))).isFalse()
    }

    @Test
    fun `a number a sensor may not report is refused`() {
        assertThat(Libre3Staging.acceptForCurve(-1, sample(Libre3Ingest.MIN_MGDL - 1.0, 70))).isFalse()
        assertThat(Libre3Staging.acceptForCurve(-1, sample(Libre3Ingest.MAX_MGDL + 1.0, 70))).isFalse()
        assertThat(Libre3Staging.acceptForCurve(-1, sample(Libre3Ingest.MIN_MGDL, 70))).isTrue()
        assertThat(Libre3Staging.acceptForCurve(-1, sample(Libre3Ingest.MAX_MGDL, 70))).isTrue()
    }
}
