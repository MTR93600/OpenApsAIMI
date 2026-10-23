package app.aaps.plugins.aps.openAPSAIMI

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * After `finalizeAndCapSMB` writes the tick's dose, nothing may raise it.
 *
 * `finalizeAndCapSMB` is where every cap, guard and budget meets, so it is meant to be the last
 * word. Three writers ran after it: the AI auditor and two legacy meal-mode paths. The auditor's own
 * prompt says it may only CONFIRM or SOFTEN, but the code wrote its value with no comparison.
 *
 * These tests mirror `DetermineBasalAIMI2.applySmbUnits`, which is private.
 *
 * Port of `SmbTerminalSealTest` from `dev_OAPSAIMI`. Truth/JUnit replaced by `kotlin.test`, names
 * in camelCase. The KMP `applySmbUnits` (state on `work.`) was compared with dev and has the same
 * seal logic. The `SmbSwitchGate` check that comes first in the real function is not part of this
 * mirror, as on dev.
 */
class SmbTerminalSealTest {

    /** Mirrors the seal in `DetermineBasalAIMI2.applySmbUnits`. */
    private class Terminal {
        var units: Double? = null
        var sealed = false
            private set
        var refusedCount = 0
            private set
        var refusedTotalU = 0.0
            private set
        var allowedRaiseCount = 0
            private set

        private val allowedOwners = setOf("MealAdvisor")

        fun apply(requestedU: Double, owner: String) {
            val requested = if (requestedU.isFinite()) requestedU.coerceAtLeast(0.0) else 0.0
            val current = units ?: 0.0
            if (!sealed || requested <= current + 1e-9) {
                units = requested
                return
            }
            if (owner in allowedOwners) {
                allowedRaiseCount++
                units = requested
                return
            }
            refusedCount++
            refusedTotalU += requested - current
        }

        fun seal(finalU: Double) {
            units = finalU.coerceAtLeast(0.0)
            sealed = true
        }
    }

    private fun assertNear(expected: Double, actual: Double?) {
        assertTrue(actual != null && abs(expected - actual) < 1e-9, "expected $expected but was $actual")
    }

    @Test
    fun beforeTheSealAnyWriterMaySetTheDose() {
        val t = Terminal()
        t.apply(0.4, "LegacyMealModes")
        assertNear(0.4, t.units)
        t.apply(1.1, "LegacyPrebolus")
        assertNear(1.1, t.units)
        assertEquals(0, t.refusedCount)
    }

    @Test
    fun afterTheSealTheAuditorCannotRaiseTheDose() {
        val t = Terminal()
        t.seal(0.8)

        t.apply(2.5, "AiAuditor")

        assertNear(0.8, t.units)
        assertEquals(1, t.refusedCount)
        assertNear(1.7, t.refusedTotalU)
    }

    @Test
    fun afterTheSealTheAuditorMayStillSoften() {
        val t = Terminal()
        t.seal(0.8)

        t.apply(0.3, "AiAuditor")

        assertNear(0.3, t.units)
        assertEquals(0, t.refusedCount)
    }

    @Test
    fun aRejectionToZeroAlwaysGoesThrough() {
        val t = Terminal()
        t.seal(1.6)

        t.apply(0.0, "AiAuditor")

        assertNear(0.0, t.units)
        assertEquals(0, t.refusedCount)
    }

    @Test
    fun theLegacyMealPathsCannotRaisePastTheTerminalEither() {
        val t = Terminal()
        t.seal(0.5)

        t.apply(1.2, "LegacyMealModes")
        t.apply(3.0, "LegacyPrebolus")

        assertNear(0.5, t.units)
        assertEquals(2, t.refusedCount)
    }

    /** The meal advisor is a user action, not a loop decision, and it is counted rather than hidden. */
    @Test
    fun theMealAdvisorKeepsItsDocumentedExceptionAndIsCounted() {
        val t = Terminal()
        t.seal(0.5)

        t.apply(4.0, "MealAdvisor")

        assertNear(4.0, t.units)
        assertEquals(1, t.allowedRaiseCount)
        assertEquals(0, t.refusedCount)
    }

    @Test
    fun aNonFiniteRequestIsTreatedAsZeroNeverAsARaise() {
        val t = Terminal()
        t.seal(0.9)

        t.apply(Double.NaN, "AiAuditor")

        assertNear(0.0, t.units)
    }

    @Test
    fun repeatedRefusalsAccumulateSoTheExportShowsTheSizeOfTheDisagreement() {
        val t = Terminal()
        t.seal(1.0)

        t.apply(1.5, "AiAuditor")
        t.apply(2.0, "LegacyMealModes")

        assertNear(1.0, t.units)
        assertEquals(2, t.refusedCount)
        assertNear(1.5, t.refusedTotalU)
    }
}
