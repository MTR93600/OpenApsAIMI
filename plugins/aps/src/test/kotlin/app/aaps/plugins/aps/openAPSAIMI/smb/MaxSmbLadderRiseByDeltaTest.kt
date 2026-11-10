package app.aaps.plugins.aps.openAPSAIMI.smb

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.math.max

/**
 * The confirmed-rise branch must also open on `shortAvgDelta`, not on `slopeFromMinDeviation` alone.
 *
 * Measured on the undeclared meal of 2026-09-05: BG crossed 140 at 13:54 but the SMB ceiling stayed
 * at 0.60 U until 14:07, because `slopeFromMinDeviation` sat between 0.67 and 0.85 the whole time
 * while `shortAvgDelta` ran from 7.5 to 12.5 mg/dL per 5 min. Thirteen lost minutes, then 13.78 U in
 * 76 minutes with 67 % of it above 180 mg/dL.
 *
 * These tests pin the new opening AND every guard the fix must not weaken.
 */
class MaxSmbLadderRiseByDeltaTest {

    private val maxSmb = 0.60
    private val maxSmbHighBg = 2.70

    private fun decide(
        bg: Double,
        combinedDelta: Double,
        slope: Double,
        shortAvgDelta: Double,
        honeymoon: Boolean = false,
    ) = MaxSmbLadder.decide(
        bgMgdl = bg,
        combinedDelta = combinedDelta,
        slopeFromMinDeviation = slope,
        shortAvgDeltaMgdl5m = shortAvgDelta,
        honeymoon = honeymoon,
        maxSmb = maxSmb,
        maxSmbHighBg = maxSmbHighBg,
    )

    @Test
    fun `le tick 13h54 du 5 septembre promeut sur shortAvgDelta quand la pente de deviation est aveugle`() {
        val decision = decide(bg = 141.0, combinedDelta = 2.0, slope = 0.85, shortAvgDelta = 9.0)

        assertThat(decision.ceilingU).isWithin(1e-9).of(maxSmbHighBg)
        assertThat(decision.branch).isEqualTo(MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH_BY_DELTA)
    }

    @Test
    fun `une montee sous le seuil reste standard`() {
        val decision = decide(bg = 141.0, combinedDelta = 2.0, slope = 0.85, shortAvgDelta = 7.9)

        assertThat(decision.branch).isEqualTo(MaxSmbLadder.LADDER_STANDARD)
        assertThat(decision.ceilingU).isWithin(1e-9).of(maxSmb)
    }

    @Test
    fun `le plancher BG 140 n est pas affaibli`() {
        val decision = decide(bg = 139.9, combinedDelta = 2.0, slope = 0.2, shortAvgDelta = 12.0)

        assertThat(decision.branch).isEqualTo(MaxSmbLadder.LADDER_STANDARD)
        assertThat(decision.ceilingU).isWithin(1e-9).of(maxSmb)
    }

    @Test
    fun `la garde combinedDelta n est pas affaiblie`() {
        val decision = decide(bg = 160.0, combinedDelta = 0.4, slope = 0.2, shortAvgDelta = 12.0)

        assertThat(decision.branch).isEqualTo(MaxSmbLadder.LADDER_STANDARD)
        assertThat(decision.ceilingU).isWithin(1e-9).of(maxSmb)
    }

    @Test
    fun `une descente avec un shortAvgDelta residuel eleve ne prend pas la branche montee`() {
        // shortAvgDelta averages 15 minutes, so it stays high for a while after a rise turns over.
        // The combinedDelta guard is what keeps the ladder off the rise branch on that turn.
        val decision = decide(bg = 210.0, combinedDelta = -2.0, slope = 0.2, shortAvgDelta = 9.0)

        assertThat(decision.branch).isEqualTo(MaxSmbLadder.LADDER_PLATEAU_MODERATE_75)
        assertThat(decision.ceilingU).isWithin(1e-9).of(max(maxSmb, maxSmbHighBg * 0.75))
    }

    @Test
    fun `le honeymoon est inchange`() {
        val decision = decide(
            bg = 190.0, combinedDelta = 2.0, slope = 0.9, shortAvgDelta = 12.0, honeymoon = true,
        )

        assertThat(decision.branch).isEqualTo(MaxSmbLadder.LADDER_STANDARD)
        assertThat(decision.ceilingU).isWithin(1e-9).of(maxSmb)
    }

    @Test
    fun `la bande 120 140 est inchangee`() {
        val decision = decide(bg = 130.0, combinedDelta = 2.0, slope = 0.5, shortAvgDelta = 12.0)

        assertThat(decision.branch).isEqualTo(MaxSmbLadder.LADDER_STANDARD)
        assertThat(decision.ceilingU).isWithin(1e-9).of(maxSmb)
    }

    @Test
    fun `le chemin pente historique garde son propre tag`() {
        // Attribution matters: only ticks the new criterion opened may carry the new tag, or the
        // next support package cannot measure the fix.
        val decision = decide(bg = 160.0, combinedDelta = 2.0, slope = 1.03, shortAvgDelta = 2.0)

        assertThat(decision.branch).isEqualTo(MaxSmbLadder.LADDER_CONFIRMED_RISE_HIGH)
        assertThat(decision.ceilingU).isWithin(1e-9).of(maxSmbHighBg)
    }

    @Test
    fun `le plafond promu ne depasse jamais la preference utilisateur`() {
        val bgs = listOf(90.0, 119.0, 125.0, 139.9, 141.0, 185.0, 210.0, 260.0)
        val deltas = listOf(-9.0, -4.0, -1.0, 0.4, 2.0, 6.0)
        val slopes = listOf(0.0, 0.85, 1.03, 1.5)
        val shortAvgDeltas = listOf(-5.0, 0.0, 7.9, 8.0, 12.0)
        val prefs = listOf(0.6 to 2.7, 3.0 to 2.0, 1.6 to 1.6)

        for (bg in bgs) for (d in deltas) for (s in slopes) for (sad in shortAvgDeltas) {
            for ((std, high) in prefs) {
                val decision = MaxSmbLadder.decide(
                    bgMgdl = bg,
                    combinedDelta = d,
                    slopeFromMinDeviation = s,
                    shortAvgDeltaMgdl5m = sad,
                    honeymoon = false,
                    maxSmb = std,
                    maxSmbHighBg = high,
                )
                assertThat(decision.ceilingU).isAtMost(max(std, high))
            }
        }
    }
}
