package app.aaps.plugins.aps.openAPSAIMI

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.min

/**
 * The aggressive-rise SMB floor must stop at the ceiling the maxSMB ladder chose for the tick.
 *
 * The floor used to be capped by `max(maxSMB, maxSMBHB)`, so it took the high-BG ceiling even on
 * ticks where the ladder had refused to promote. Measured over three support packages: on 36 of 50
 * floor-active ticks the floor landed exactly on `maxSMBHB`, and on 31 of those 50 the ladder had
 * chosen a lower value.
 *
 * The fixtures below are real ticks, and each one matters for a different reason:
 *  - dinner 2026-08-14 20:41 — ladder on standard 1.60 U, floor took 2.70 U, five such ticks in a
 *    row drove IOB from 5.73 to 19.32 U and BG to 65.7 mg/dL. This is the case the change fixes.
 *  - the 10:00 meal on 2026-08-15 — ladder on standard 0.80 U, floor took 2.20 U, BG went to 64.1.
 *  - lunch 2026-08-15 13:57 — ladder had already promoted, so `maxSMB` equals `maxSMBHB` at 1.80 U
 *    and the floor is unchanged. This is the rescue the change must not break.
 *  - the 2026-08-12 crash ticks at BG 287-297 — the BG >= 250 branch promotes, so again unchanged.
 *
 * These tests mirror the clamp in `DetermineBasalAIMI2.determine_basal` around `smbCeilingForFloor`,
 * which is private to an 18 000-line class.
 */
class RiseFloorLadderCeilingTest {

    /**
     * The clamp under test: the floor is bounded by the ladder ceiling and by the IOB headroom.
     * `maxSmbHighBg` is deliberately absent — that is the whole point of the change.
     */
    private fun boundedFloorU(
        tierFloorU: Double,
        maxSmb: Double,
        maxIob: Double,
        iob: Double,
    ): Double {
        val iobHeadroom = (maxIob - iob).coerceAtLeast(0.0)
        val ceiling = maxSmb.coerceAtLeast(0.0)
        return tierFloorU.coerceAtMost(min(ceiling, iobHeadroom))
    }

    @Test
    fun `the floor never exceeds the ladder ceiling`() {
        // Dinner 2026-08-14 20:41: tier floor 3.0 U pref, ladder on standard 1.60 U.
        val floor = boundedFloorU(tierFloorU = 3.0, maxSmb = 1.60, maxIob = 20.0, iob = 8.33)

        assertThat(floor).isWithin(1e-9).of(1.60)
    }

    @Test
    fun `a high BG ceiling above the ladder ceiling no longer lifts the floor`() {
        // The old clamp used max(maxSMB, maxSMBHB) = 2.70 U here and delivered exactly that.
        val maxSmb = 1.60
        val maxSmbHighBg = 2.70
        val old = 3.0.coerceAtMost(min(maxOf(maxSmb, maxSmbHighBg), 20.0 - 8.33))
        val new = boundedFloorU(tierFloorU = 3.0, maxSmb = maxSmb, maxIob = 20.0, iob = 8.33)

        assertThat(old).isWithin(1e-9).of(2.70)
        assertThat(new).isWithin(1e-9).of(1.60)
        assertThat(new).isLessThan(old)
    }

    @Test
    fun `a promoted ladder leaves the floor untouched`() {
        // Lunch 2026-08-15 13:57: the ladder had promoted, so maxSMB equals maxSMBHB.
        val maxSmb = 1.80
        val maxSmbHighBg = 1.80
        val old = 3.0.coerceAtMost(min(maxOf(maxSmb, maxSmbHighBg), 20.0 - 10.72))
        val new = boundedFloorU(tierFloorU = 3.0, maxSmb = maxSmb, maxIob = 20.0, iob = 10.72)

        assertThat(new).isWithin(1e-9).of(old)
        assertThat(new).isWithin(1e-9).of(1.80)
    }

    @Test
    fun `the BG over 250 branch still promotes so the crash ticks are unchanged`() {
        // 2026-08-12 10:37, BG 287.4: the plateau branch had already set maxSMB to maxSMBHB.
        val new = boundedFloorU(tierFloorU = 2.20, maxSmb = 2.20, maxIob = 20.0, iob = 8.59)

        assertThat(new).isWithin(1e-9).of(2.20)
    }

    @Test
    fun `the floor stays finite and non negative when the ladder ceiling is zero`() {
        val floor = boundedFloorU(tierFloorU = 3.0, maxSmb = 0.0, maxIob = 20.0, iob = 5.0)

        assertThat(floor.isFinite()).isTrue()
        assertThat(floor).isWithin(1e-9).of(0.0)
        assertThat(floor).isAtLeast(0.0)
    }

    @Test
    fun `a negative ladder ceiling cannot invent a negative dose`() {
        // Defensive: the ladder should never hand down a negative value, but the clamp must not
        // turn one into a negative bolus if it ever does.
        val floor = boundedFloorU(tierFloorU = 3.0, maxSmb = -1.0, maxIob = 20.0, iob = 5.0)

        assertThat(floor.isFinite()).isTrue()
        assertThat(floor).isAtLeast(0.0)
    }

    @Test
    fun `the IOB headroom still binds when it is tighter than the ladder ceiling`() {
        // Dinner 2026-08-14 20:56: headroom was 2.79 U while the ladder ceiling was 1.60 U, so the
        // ceiling binds. Push IOB higher and the headroom must take over.
        val floor = boundedFloorU(tierFloorU = 3.0, maxSmb = 1.60, maxIob = 20.0, iob = 19.2)

        assertThat(floor).isWithin(1e-9).of(0.8)
        assertThat(floor).isAtMost(1.60)
    }

    @Test
    fun `no headroom means no floor`() {
        val floor = boundedFloorU(tierFloorU = 3.0, maxSmb = 1.60, maxIob = 20.0, iob = 25.0)

        assertThat(floor).isWithin(1e-9).of(0.0)
        assertThat(floor).isAtLeast(0.0)
    }
}
