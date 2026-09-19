package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater.GateKind
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiHealthContext
import app.aaps.plugins.aps.openAPSAIMI.recursive.MealChannelHint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The order of the branches in `AutoDriveGater.shouldEngageV3` decides only the name of the tick.
 *
 * Measured on 2026-09-08: the gate opened 472 times, 306 of them named HIGH_PLATEAU, and
 * [GateKind.MEAL_AWARE_RISE] was never written once. The plateau was read first so above BG 150
 * the meal was hidden by the glucose level and the meal label could only ever appear below 150.
 * Reading the meal first fixes the name. It must not move the gate itself: `shouldEngage` is a
 * plain OR of the three conditions so this grid pins that `engage` is the same for all eight
 * combinations and that the only name that moved is the one case where both a plateau and a meal
 * were true at once.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `0ca7744184`. Study: commonTest + kotlin.test + a
 * no-op logger / idle [AimiHealthContext] — not androidHostTest mockito. Backtick names omit
 * commas (Kotlin/Native).
 */
class AutoDriveGaterEngageKindOrderTest {

    /**
     * Steps and heart rate stay at zero so neither the activity block nor the heart-rate block
     * can fire and hide the glycemic branches under test.
     */
    private object IdleHealth : AimiHealthContext {
        override fun fetchSnapshot() = HealthContextSnapshot()
        override fun fetchSnapshotForAutodriveGater() = HealthContextSnapshot()
        override fun getLastSnapshot() = HealthContextSnapshot()
    }

    private val gater = AutoDriveGater(IdleHealth, NoOpAapsLogger)

    /** One row of the grid: the three conditions and the inputs that produce them. */
    private data class Row(
        val highPlateau: Boolean,
        val activelyRising: Boolean,
        val mealRising: Boolean,
        val bg: Double,
        val combinedDelta: Double,
        val explicitMealMode: Boolean,
    )

    /**
     * bg 180 is above the plateau line (150); bg 110 is below it and below the 120 rise branch
     * where a rise needs delta above 2.0. A delta of 0.3 is above the meal line (0.25) but under
     * every rise line so it separates "meal rising" from "actively rising" cleanly.
     */
    private val grid = listOf(
        Row(highPlateau = true, activelyRising = true, mealRising = true, bg = 180.0, combinedDelta = 1.5, explicitMealMode = true),
        Row(highPlateau = true, activelyRising = true, mealRising = false, bg = 180.0, combinedDelta = 1.5, explicitMealMode = false),
        Row(highPlateau = true, activelyRising = false, mealRising = true, bg = 180.0, combinedDelta = 0.3, explicitMealMode = true),
        Row(highPlateau = true, activelyRising = false, mealRising = false, bg = 180.0, combinedDelta = 0.3, explicitMealMode = false),
        Row(highPlateau = false, activelyRising = true, mealRising = true, bg = 110.0, combinedDelta = 2.5, explicitMealMode = true),
        Row(highPlateau = false, activelyRising = true, mealRising = false, bg = 110.0, combinedDelta = 2.5, explicitMealMode = false),
        Row(highPlateau = false, activelyRising = false, mealRising = true, bg = 110.0, combinedDelta = 0.3, explicitMealMode = true),
        Row(highPlateau = false, activelyRising = false, mealRising = false, bg = 110.0, combinedDelta = 0.3, explicitMealMode = false),
    )

    private fun run(row: Row) = gater.shouldEngageV3(
        bg = row.bg,
        combinedDelta = row.combinedDelta,
        explicitMealMode = row.explicitMealMode,
    )

    /** The label the gate wrote before the branches were reordered: plateau first, meal second. */
    private fun kindBeforeReorder(row: Row): GateKind = when {
        row.highPlateau -> GateKind.HIGH_PLATEAU
        row.mealRising -> GateKind.MEAL_AWARE_RISE
        else -> GateKind.STRONG_RISE
    }

    @Test
    fun `the grid really covers the eight combinations`() {
        assertEquals(8, grid.size)
        assertEquals(8, grid.map { Triple(it.highPlateau, it.activelyRising, it.mealRising) }.toSet().size)
    }

    @Test
    fun `engage is the plain OR of the three conditions whatever the branch order`() {
        grid.forEach { row ->
            val expected = row.highPlateau || row.activelyRising || row.mealRising
            assertEquals(expected, run(row).engage, row.toString())
        }
    }

    @Test
    fun `only the plateau-and-meal tick changed its name`() {
        grid.forEach { row ->
            val kind = run(row).kind
            val before = kindBeforeReorder(row)
            if (row.highPlateau && row.mealRising) {
                assertEquals(GateKind.MEAL_AWARE_RISE, kind, row.toString())
                assertEquals(GateKind.HIGH_PLATEAU, before, row.toString())
            } else if (row.highPlateau || row.activelyRising || row.mealRising) {
                assertEquals(before, kind, row.toString())
            } else {
                assertEquals(GateKind.RISE_TOO_WEAK, kind, row.toString())
            }
        }
    }

    @Test
    fun `a meal is named even above the plateau line`() {
        val result = gater.shouldEngageV3(bg = 180.0, combinedDelta = 1.5, explicitMealMode = true)

        assertTrue(result.engage)
        assertEquals(GateKind.MEAL_AWARE_RISE, result.kind)
        assertTrue(result.reason.contains("Meal-aware rise"))
    }

    /**
     * The meal channel from the recursive belief tree is one of the ways a meal context is seen.
     * On the measured day the gate was always handed `null` here because the tree is resolved
     * later in the same tick than the gate so this path was never exercised in production.
     */
    @Test
    fun `the recursive belief meal channel can open the gate on its own`() {
        val withoutHint = gater.shouldEngageV3(bg = 110.0, combinedDelta = 0.3, mealChannelHint = null)
        val withHint = gater.shouldEngageV3(bg = 110.0, combinedDelta = 0.3, mealChannelHint = MealChannelHint.PRIORITY)

        assertFalse(withoutHint.engage)
        assertEquals(GateKind.RISE_TOO_WEAK, withoutHint.kind)
        assertTrue(withHint.engage)
        assertEquals(GateKind.MEAL_AWARE_RISE, withHint.kind)
    }

    /** A suppressed meal channel must still win over any other meal evidence. */
    @Test
    fun `a suppressed meal channel keeps the meal name away`() {
        val result = gater.shouldEngageV3(
            bg = 180.0,
            combinedDelta = 1.5,
            explicitMealMode = true,
            mealChannelHint = MealChannelHint.SUPPRESS,
        )

        assertTrue(result.engage)
        assertEquals(GateKind.HIGH_PLATEAU, result.kind)
    }

    /**
     * commonMain no-op [AAPSLogger]: [AutoDriveGater] only logs; nothing in these locks
     * reads the log. Kept local so `commonTest` does not grow a mockito / shared-tests dependency.
     */
    private object NoOpAapsLogger : AAPSLogger {
        override fun debug(message: String) = Unit
        override fun debug(enable: Boolean, tag: LTag, message: String) = Unit
        override fun debug(tag: LTag, message: String) = Unit
        override fun debug(tag: LTag, accessor: () -> String) = Unit
        override fun debug(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun warn(tag: LTag, message: String) = Unit
        override fun warn(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun info(tag: LTag, message: String) = Unit
        override fun info(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun error(tag: LTag, message: String) = Unit
        override fun error(tag: LTag, message: String, throwable: Throwable) = Unit
        override fun error(tag: LTag, format: String, vararg arguments: Any?) = Unit
        override fun error(message: String) = Unit
        override fun error(message: String, throwable: Throwable) = Unit
        override fun error(format: String, vararg arguments: Any?) = Unit
        override fun debug(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun info(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun warn(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
        override fun error(className: String, methodName: String, lineNumber: Int, tag: LTag, message: String) = Unit
    }
}
