package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater.GateKind
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextRepository
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.MealChannelHint
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * The order of the branches in `AutoDriveGater.shouldEngageV3` decides only the name of the tick.
 *
 * Measured on 2026-09-08: the gate opened 472 times, 306 of them named HIGH_PLATEAU, and
 * [GateKind.MEAL_AWARE_RISE] was never written once. The plateau was read first, so above BG 150
 * the meal was hidden by the glucose level and the meal label could only ever appear below 150.
 * Reading the meal first fixes the name. It must not move the gate itself: `shouldEngage` is a
 * plain OR of the three conditions, so this grid pins that `engage` is the same for all eight
 * combinations, and that the only name that moved is the one case where both a plateau and a meal
 * were true at once.
 */
class AutoDriveGaterEngageKindOrderTest {

    private lateinit var healthRepo: HealthContextRepository
    private lateinit var gater: AutoDriveGater

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
     * bg 180 is above the plateau line (150); bg 110 is below it and below the 120 rise branch,
     * where a rise needs delta above 2.0. A delta of 0.3 is above the meal line (0.25) but under
     * every rise line, so it separates "meal rising" from "actively rising" cleanly.
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

    @BeforeEach
    fun setUp() {
        healthRepo = mock()
        // Steps and heart rate stay at zero, so neither the activity block nor the heart-rate block
        // can fire and hide the glycemic branches under test.
        whenever(healthRepo.fetchSnapshotForAutodriveGater()).thenReturn(HealthContextSnapshot())
        gater = AutoDriveGater(healthRepo, mock<AAPSLogger>())
    }

    private fun run(row: Row) = gater.shouldEngageV3(
        bg = row.bg,
        combinedDelta = row.combinedDelta,
        explicitMealMode = row.explicitMealMode,
    )

    /** The label the gate wrote before the branches were reordered: plateau first, meal second. */
    private fun kindBeforeReorder(row: Row): GateKind = when {
        row.highPlateau  -> GateKind.HIGH_PLATEAU
        row.mealRising   -> GateKind.MEAL_AWARE_RISE
        else             -> GateKind.STRONG_RISE
    }

    @Test
    fun `the grid really covers the eight combinations`() {
        assertThat(grid).hasSize(8)
        assertThat(grid.map { Triple(it.highPlateau, it.activelyRising, it.mealRising) }.toSet()).hasSize(8)
    }

    @Test
    fun `engage is the plain OR of the three conditions, whatever the branch order`() {
        grid.forEach { row ->
            val expected = row.highPlateau || row.activelyRising || row.mealRising
            assertThat(run(row).engage).isEqualTo(expected)
        }
    }

    @Test
    fun `only the plateau-and-meal tick changed its name`() {
        grid.forEach { row ->
            val kind = run(row).kind
            val before = kindBeforeReorder(row)
            if (row.highPlateau && row.mealRising) {
                assertThat(kind).isEqualTo(GateKind.MEAL_AWARE_RISE)
                assertThat(before).isEqualTo(GateKind.HIGH_PLATEAU)
            } else if (row.highPlateau || row.activelyRising || row.mealRising) {
                assertThat(kind).isEqualTo(before)
            } else {
                assertThat(kind).isEqualTo(GateKind.RISE_TOO_WEAK)
            }
        }
    }

    @Test
    fun `a meal is named even above the plateau line`() {
        val result = gater.shouldEngageV3(bg = 180.0, combinedDelta = 1.5, explicitMealMode = true)

        assertThat(result.engage).isTrue()
        assertThat(result.kind).isEqualTo(GateKind.MEAL_AWARE_RISE)
        assertThat(result.reason).contains("Meal-aware rise")
    }

    /**
     * The meal channel from the recursive belief tree is one of the ways a meal context is seen.
     * On the measured day the gate was always handed `null` here, because the tree is resolved
     * later in the same tick than the gate, so this path was never exercised in production.
     */
    @Test
    fun `the recursive belief meal channel can open the gate on its own`() {
        val withoutHint = gater.shouldEngageV3(bg = 110.0, combinedDelta = 0.3, mealChannelHint = null)
        val withHint = gater.shouldEngageV3(bg = 110.0, combinedDelta = 0.3, mealChannelHint = MealChannelHint.PRIORITY)

        assertThat(withoutHint.engage).isFalse()
        assertThat(withoutHint.kind).isEqualTo(GateKind.RISE_TOO_WEAK)
        assertThat(withHint.engage).isTrue()
        assertThat(withHint.kind).isEqualTo(GateKind.MEAL_AWARE_RISE)
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

        assertThat(result.engage).isTrue()
        assertThat(result.kind).isEqualTo(GateKind.HIGH_PLATEAU)
    }
}
