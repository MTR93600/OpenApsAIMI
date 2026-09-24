package app.aaps.plugins.aps.openAPSAIMI.ISF

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Holds the stress ISF floor on the sensitivity the dose really uses.
 *
 * The numbers come from package 1790063419538, tick 1790021540129 (2026-09-21 22:12): the signature
 * was active, the profile block of that time of day was 60 mg/dL/U, and the sensitivity the dose used
 * was 38.0 while the commanded one read 60. Before this change the floor moved only the commanded
 * value, which no SMB path reads.
 */
class WorkingIsfStressFloorTest {

    /** The sensitivity the dose used on that tick, mg/dL per U. */
    private val workingIsf = 38.0

    /** The profile block of that time of day, which is also the floor at 1.0 x profile. */
    private val floor = 60.0

    @BeforeEach
    fun setUp() {
        WorkingIsf.resetLastApplied()
    }

    @Test
    fun `the floor raises the sensitivity the dose uses`() {
        val result = WorkingIsf.finalize(
            workingIsfMgdlPerU = workingIsf,
            physioIsfFactor = 1.0,
            stressFloorIsfMgdlPerU = floor,
        )

        assertThat(result).isWithin(1e-9).of(60.0)
        assertThat(WorkingIsf.lastApplied!!.raised).isTrue()
        assertThat(WorkingIsf.lastApplied!!.beforeMgdlPerU).isWithin(1e-9).of(38.0)
        assertThat(WorkingIsf.lastApplied!!.afterMgdlPerU).isWithin(1e-9).of(60.0)
        assertThat(WorkingIsf.lastApplied!!.floorMgdlPerU).isWithin(1e-9).of(60.0)
    }

    @Test
    fun `no floor leaves the same tick exactly where it was`() {
        val result = WorkingIsf.finalize(
            workingIsfMgdlPerU = workingIsf,
            physioIsfFactor = 1.0,
            stressFloorIsfMgdlPerU = null,
        )

        assertThat(result).isWithin(1e-9).of(38.0)
        assertThat(WorkingIsf.lastApplied!!.raised).isFalse()
        assertThat(WorkingIsf.lastApplied!!.floorMgdlPerU).isNull()
    }

    @Test
    fun `the floor never lowers a sensitivity that is already higher`() {
        val result = WorkingIsf.finalize(
            workingIsfMgdlPerU = 80.0,
            physioIsfFactor = 1.0,
            stressFloorIsfMgdlPerU = floor,
        )

        assertThat(result).isWithin(1e-9).of(80.0)
        assertThat(WorkingIsf.lastApplied!!.raised).isFalse()
    }

    @Test
    fun `the floor is applied after the heart-rate trend has pulled the other way`() {
        // HeartRateTrendIsf lowers the same member by 10 % on nearly the same signature, and it runs
        // earlier in the tick. Its multiplier is applied first here, exactly as the engine applies it.
        val trend = HeartRateTrendIsf.multiplier(
            steps10m = 0,
            avgBpm10 = 90.0,
            avgBpm60 = 70.0,
            baselineIsReal = true,
            bgMgdl = 160.0,
            deltaMgdl5m = 2.0,
        )
        assertThat(trend).isWithin(1e-9).of(HeartRateTrendIsf.ISF_MULTIPLIER)

        val afterTrend = workingIsf * trend
        assertThat(afterTrend).isLessThan(workingIsf)

        val result = WorkingIsf.finalize(
            workingIsfMgdlPerU = afterTrend,
            physioIsfFactor = 1.0,
            stressFloorIsfMgdlPerU = floor,
        )

        // `isAtLeast(floor)` would hold whatever the order is, so it proves nothing. What the order
        // decides is whether the trend can still bite AFTER the floor: applied in the wrong order the
        // result would be floor x 0.9, ten per cent below the protection the floor is supposed to give.
        val wrongOrder = floor * trend
        assertThat(result).isWithin(1e-9).of(floor)
        assertThat(result).isGreaterThan(wrongOrder)
    }

    @Test
    fun `the floor is applied after the physiological factor, not before it`() {
        // 0.85 is the lower bound of the physiological ISF factor. Applied after the floor it would
        // take the value back under it, which is the whole failure this order prevents.
        val result = WorkingIsf.finalize(
            workingIsfMgdlPerU = workingIsf,
            physioIsfFactor = 0.85,
            stressFloorIsfMgdlPerU = floor,
        )

        assertThat(result).isWithin(1e-9).of(60.0)
    }

    @Test
    fun `with the key off the two historical steps are untouched`() {
        // What the engine did before: bounds, then the physiological factor, and nothing else.
        val expected = workingIsf.coerceIn(WorkingIsf.MIN_MGDL_PER_U, WorkingIsf.MAX_MGDL_PER_U) * 0.95

        val result = WorkingIsf.finalize(
            workingIsfMgdlPerU = workingIsf,
            physioIsfFactor = 0.95,
            stressFloorIsfMgdlPerU = null,
        )

        assertThat(result).isWithin(1e-9).of(expected)
    }

    @Test
    fun `a floor that is not a usable number changes nothing`() {
        val expected = WorkingIsf.finalize(workingIsf, 1.0, null)

        assertThat(WorkingIsf.finalize(workingIsf, 1.0, 0.0)).isWithin(1e-9).of(expected)
        assertThat(WorkingIsf.finalize(workingIsf, 1.0, -60.0)).isWithin(1e-9).of(expected)
        assertThat(WorkingIsf.finalize(workingIsf, 1.0, Double.NaN)).isWithin(1e-9).of(expected)
        assertThat(WorkingIsf.finalize(workingIsf, 1.0, Double.POSITIVE_INFINITY)).isWithin(1e-9).of(expected)
    }

    @Test
    fun `the bounds still hold before the floor`() {
        assertThat(WorkingIsf.finalize(1.0, 1.0, null)).isWithin(1e-9).of(WorkingIsf.MIN_MGDL_PER_U)
        assertThat(WorkingIsf.finalize(500.0, 1.0, null)).isWithin(1e-9).of(WorkingIsf.MAX_MGDL_PER_U)
    }
}
