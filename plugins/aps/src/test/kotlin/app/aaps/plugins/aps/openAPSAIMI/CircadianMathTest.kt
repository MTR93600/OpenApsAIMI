package app.aaps.plugins.aps.openAPSAIMI

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.pow
import kotlin.math.round

class CircadianMathTest {

    /** Legacy expanded formula (pre-refactor) for regression check. */
    private fun legacyCircadianSmb(nowMinutes: Double, delta: Float): Double {
        val d = delta.toDouble()
        return round(
            (
                (0.00000379 * d * nowMinutes.pow(5)) -
                    (0.00016422 * d * nowMinutes.pow(4)) +
                    (0.00128081 * d * nowMinutes.pow(3)) +
                    (0.02533782 * d * nowMinutes.pow(2)) -
                    (0.33275556 * d * nowMinutes) +
                    CIRCADIAN_INTERCEPT
                ) * 100.0
        ) / 100.0
    }

    @Test
    fun smbScaledMatchesLegacyExpansionForSamples() {
        val samples = listOf(
            8.25 to 1.2f,
            12.0 to -0.5f,
            18.5 to 2.0f,
            6.0 to 0.0f,
        )
        for ((nowMin, delta) in samples) {
            assertEquals(
                legacyCircadianSmb(nowMin, delta),
                circadianSmbScaled(nowMin, delta),
                1e-9,
            )
        }
    }

    @Test
    fun sensitivityUsesIntercept() {
        val n = 10.0
        val s = circadianSensitivityHourly(n)
        assertEquals(
            0.00000379 * n.pow(5) - 0.00016422 * n.pow(4) + 0.00128081 * n.pow(3) +
                0.02533782 * n.pow(2) - 0.33275556 * n + CIRCADIAN_INTERCEPT,
            s,
            1e-9,
        )
    }
}
