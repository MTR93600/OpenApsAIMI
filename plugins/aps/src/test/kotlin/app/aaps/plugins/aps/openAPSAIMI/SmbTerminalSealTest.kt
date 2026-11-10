package app.aaps.plugins.aps.openAPSAIMI

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * After `finalizeAndCapSMB` writes the tick's dose, nothing may raise it.
 *
 * `finalizeAndCapSMB` is where every cap, guard and budget converges, so it is meant to be the last
 * word. It was not. Five places wrote the final SMB, and three ran after it: the AI auditor and two
 * legacy meal-mode paths.
 *
 * The auditor is the clearest case. Its own prompt says its role is *"CONFIRM or SOFTEN only — never
 * invent a lift above Harmonia's decided SMB"*, and the code did
 * `finalResult.units = result.bolusU ?: 0.0` with no comparison against what the terminal had
 * decided. A constraint stated in documentation and absent from the code — the same shape as the
 * aggressive-rise floor, whose KDoc claims it is "always re-bounded by V3 safety" while it is not.
 *
 * These tests mirror `DetermineBasalAIMI2.applySmbUnits`, which is private to a 17 000-line class.
 *
 * ## What this does not cover
 *
 * The aggressive-rise floor feeds `finalizeAndCapSMB` as an **input**, so it sits upstream of the
 * seal and is untouched by it. Its defect is that it bypasses `ControlBarrierShield`, which is a
 * different boundary and needs the barrier's insulin model fixed first.
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

    @Test
    fun `before the seal any writer may set the dose`() {
        val t = Terminal()
        t.apply(0.4, "LegacyMealModes")
        assertThat(t.units).isWithin(1e-9).of(0.4)
        t.apply(1.1, "LegacyPrebolus")
        assertThat(t.units).isWithin(1e-9).of(1.1)
        assertThat(t.refusedCount).isEqualTo(0)
    }

    @Test
    fun `after the seal the auditor cannot raise the dose`() {
        val t = Terminal()
        t.seal(0.8)

        t.apply(2.5, "AiAuditor")

        assertThat(t.units).isWithin(1e-9).of(0.8)
        assertThat(t.refusedCount).isEqualTo(1)
        assertThat(t.refusedTotalU).isWithin(1e-9).of(1.7)
    }

    @Test
    fun `after the seal the auditor may still soften, which is its stated role`() {
        val t = Terminal()
        t.seal(0.8)

        t.apply(0.3, "AiAuditor")

        assertThat(t.units).isWithin(1e-9).of(0.3)
        assertThat(t.refusedCount).isEqualTo(0)
    }

    @Test
    fun `a rejection to zero always goes through`() {
        val t = Terminal()
        t.seal(1.6)

        t.apply(0.0, "AiAuditor")

        assertThat(t.units).isWithin(1e-9).of(0.0)
        assertThat(t.refusedCount).isEqualTo(0)
    }

    @Test
    fun `the legacy meal paths cannot raise past the terminal either`() {
        val t = Terminal()
        t.seal(0.5)

        t.apply(1.2, "LegacyMealModes")
        t.apply(3.0, "LegacyPrebolus")

        assertThat(t.units).isWithin(1e-9).of(0.5)
        assertThat(t.refusedCount).isEqualTo(2)
    }

    /** The meal advisor is a user action, not a loop decision, and it is counted rather than hidden. */
    @Test
    fun `the meal advisor keeps its documented exception, and is counted`() {
        val t = Terminal()
        t.seal(0.5)

        t.apply(4.0, "MealAdvisor")

        assertThat(t.units).isWithin(1e-9).of(4.0)
        assertThat(t.allowedRaiseCount).isEqualTo(1)
        assertThat(t.refusedCount).isEqualTo(0)
    }

    @Test
    fun `a non-finite request is treated as zero, never as a raise`() {
        val t = Terminal()
        t.seal(0.9)

        t.apply(Double.NaN, "AiAuditor")

        assertThat(t.units).isWithin(1e-9).of(0.0)
    }

    @Test
    fun `repeated refusals accumulate so the export shows the size of the disagreement`() {
        val t = Terminal()
        t.seal(1.0)

        t.apply(1.5, "AiAuditor")
        t.apply(2.0, "LegacyMealModes")

        assertThat(t.units).isWithin(1e-9).of(1.0)
        assertThat(t.refusedCount).isEqualTo(2)
        assertThat(t.refusedTotalU).isWithin(1e-9).of(1.5)
    }
}
