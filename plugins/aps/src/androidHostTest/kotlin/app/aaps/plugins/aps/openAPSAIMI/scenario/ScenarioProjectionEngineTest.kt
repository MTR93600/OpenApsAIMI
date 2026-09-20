package app.aaps.plugins.aps.openAPSAIMI.scenario

import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhase
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetrics
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryModulation
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ScenarioProjectionEngineTest {

    @BeforeEach
    fun resetHysteresis() {
        InsulinSlopePreserveHysteresis.reset()
    }

    private fun flatCurves(bg: Double, terminal: Double): AdvancedPredictionCurves {
        val series = listOf(bg, terminal)
        return AdvancedPredictionCurves(
            iob = series,
            cob = series,
            uam = listOf(bg, bg + 20.0),
            zt = series,
            hybrid = listOf(bg, 39.0),
        )
    }

    private fun collapsedCurves(bg: Double, steps: Int = 12): AdvancedPredictionCurves {
        val hybrid = List(steps) { bg }
        val iob = List(steps) { i -> bg - i * 2.0 }
        return AdvancedPredictionCurves(
            iob = iob,
            cob = hybrid,
            uam = hybrid,
            zt = iob,
            hybrid = hybrid,
        )
    }

    @Test
    fun thomasLunchScenario_bestRisesAboveFloor() {
        val projection = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 119.0,
                deltaMgdlPer5 = 0.6f,
                curves = flatCurves(119.0, 39.0),
                context = ScenarioProjectionContext(
                    mealContext = MealSafetyContext(mealModeActive = true),
                    effectiveCobG = 0.0,
                ),
            ),
        )
        assertTrue(projection.scenarioBest.terminalMgdl > projection.clinicalFloor.terminalMgdl)
        assertTrue(projection.scenarioBest.terminalMgdl >= 119.0)
        assertTrue(projection.contributors.any { it.id == ScenarioContributorId.MEAL_CONTEXT })
    }

    @Test
    fun openDivergingTrajectory_addsRiseContributor() {
        val analysis = TrajectoryAnalysis(
            classification = TrajectoryType.OPEN_DIVERGING,
            metrics = TrajectoryMetrics(
                curvature = 0.1,
                convergenceVelocity = -0.5,
                coherence = 0.5,
                energyBalance = 1.0,
                openness = 0.8,
            ),
            modulation = TrajectoryModulation.NEUTRAL,
            warnings = emptyList(),
            stableOrbitDistance = 10.0,
            predictedConvergenceTime = null,
        )
        val projection = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 145.0,
                deltaMgdlPer5 = 3.5f,
                curves = flatCurves(145.0, 120.0),
                context = ScenarioProjectionContext(
                    trajectoryAnalysis = analysis,
                    trajectoryRelevanceScore = 0.6,
                ),
            ),
        )
        assertTrue(projection.contributors.any { it.id == ScenarioContributorId.TRAJECTORY_RISE })
        assertTrue(projection.scenarioBest.terminalMgdl >= projection.clinicalFloor.terminalMgdl)
    }

    @Test
    fun activityProtection_capsRiseProjection() {
        val before = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 130.0,
                deltaMgdlPer5 = 2.5f,
                curves = flatCurves(130.0, 180.0),
                context = ScenarioProjectionContext(),
            ),
        )
        val after = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 130.0,
                deltaMgdlPer5 = 2.5f,
                curves = flatCurves(130.0, 180.0),
                context = ScenarioProjectionContext(activityProtectionMode = true),
            ),
        )
        assertTrue(after.scenarioBest.terminalMgdl <= before.scenarioBest.terminalMgdl)
        assertTrue(after.contributors.any { it.id == ScenarioContributorId.ACTIVITY_PROTECTION })
    }

    @Test
    fun hormonalPhase_suppressesUamMomentumAndCapsTerminal() {
        val without = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 122.0,
                deltaMgdlPer5 = 2.0f,
                curves = flatCurves(122.0, 39.0),
                context = ScenarioProjectionContext(effectiveCobG = 0.0),
            ),
        )
        val withPhase = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 122.0,
                deltaMgdlPer5 = 2.0f,
                curves = flatCurves(122.0, 39.0),
                context = ScenarioProjectionContext(
                    effectiveCobG = 0.0,
                    physiologicalPhase = PhysiologicalPhase.DAWN_CORTISOL,
                    suppressMealLikeUam = true,
                    scenarioBestCapAboveBgMgdl = 50.0,
                ),
            ),
        )
        assertTrue(withPhase.scenarioBest.terminalMgdl <= 172.0)
        assertTrue(withPhase.scenarioBest.terminalMgdl <= without.scenarioBest.terminalMgdl)
        assertTrue(withPhase.contributors.any { it.id == ScenarioContributorId.PHYSIOLOGICAL_PHASE })
        assertTrue(without.contributors.any { it.id == ScenarioContributorId.PKPD_UAM_MOMENTUM })
    }

    @Test
    fun graphMapping_floorOnIobBestOnUam() {
        val projection = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = 100.0,
                deltaMgdlPer5 = 0f,
                curves = flatCurves(100.0, 80.0),
                context = ScenarioProjectionContext(),
            ),
        )
        assertEquals(projection.clinicalFloor.pointsMgdl, projection.clinicalFloor.pointsMgdl)
        assertEquals(projection.scenarioBest.pointsMgdl.size, projection.clinicalFloor.pointsMgdl.size)
    }

    @Test
    fun collapsedHybrid_restoresInsulinSlopeFromFloor() {
        val bg = 72.0
        val projection = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = bg,
                deltaMgdlPer5 = -1.0f,
                curves = collapsedCurves(bg),
                context = ScenarioProjectionContext(),
            ),
        )
        assertTrue(projection.contributors.any { it.id == ScenarioContributorId.INSULIN_SLOPE_RESTORE })
        assertTrue(projection.scenarioBest.terminalMgdl < bg - 3.0)
        assertTrue(projection.scenarioBest.terminalMgdl > projection.clinicalFloor.terminalMgdl)
    }

    @Test
    fun mealIntent_dampensRestoreInsteadOfBailing() {
        val bg = 72.0
        val withoutMeal = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = bg,
                deltaMgdlPer5 = -1.0f,
                curves = collapsedCurves(bg),
                context = ScenarioProjectionContext(),
            ),
        )
        InsulinSlopePreserveHysteresis.reset()
        val withMeal = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = bg,
                deltaMgdlPer5 = -1.0f,
                curves = collapsedCurves(bg),
                context = ScenarioProjectionContext(
                    mealContext = MealSafetyContext(inferredMealSignal = true),
                ),
            ),
        )
        assertTrue(withMeal.contributors.any { it.id == ScenarioContributorId.INSULIN_SLOPE_RESTORE })
        assertTrue(
            withMeal.contributors.any {
                it.id == ScenarioContributorId.INSULIN_SLOPE_RESTORE && it.summary.contains("mealDamp")
            },
        )
        // Dampened restore declines less than full restore (terminal closer to BG).
        assertTrue(withMeal.scenarioBest.terminalMgdl > withoutMeal.scenarioBest.terminalMgdl)
        assertTrue(withMeal.scenarioBest.terminalMgdl < bg)
    }

    @Test
    fun mealAbsorptionFloor_usesTerminalRamp_notWholePathPin() {
        val bg = 120.0
        val steps = 13
        val descending = List(steps) { i -> bg - i * 4.0 } // terminal 72
        val curves = AdvancedPredictionCurves(
            iob = descending,
            cob = descending,
            uam = descending,
            zt = descending,
            hybrid = descending,
        )
        val floorAbove = 15.0
        val projection = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = bg,
                deltaMgdlPer5 = -2.0f,
                curves = curves,
                context = ScenarioProjectionContext(
                    mealAbsorptionMemoryActive = true,
                    mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                    mealAbsorptionBestTFloorAboveBgMgdl = floorAbove,
                ),
            ),
        )
        val points = projection.scenarioBest.pointsMgdl
        assertEquals(bg.toInt(), points.first())
        // Must not be a dead-flat constant across the horizon.
        assertTrue(points.distinct().size > 1)
        assertTrue(projection.scenarioBest.terminalMgdl >= bg + floorAbove - 0.5)
        // Gate path-min is the pre-lift minimum (descending raw), not the lifted display min.
        assertTrue(projection.scenarioBest.gatePathMinMgdl < bg)
        assertTrue(projection.scenarioBest.gatePathMinMgdl < projection.scenarioBest.pathMinMgdl)
        assertTrue(
            projection.contributors.any {
                it.id == ScenarioContributorId.PHYSIOLOGICAL_PHASE && it.summary.contains("terminal ramp")
            },
        )
        // Flat-line signature bestMin == bestT must not appear on a multi-point lifted path.
        assertTrue(projection.scenarioBest.pathMinMgdl != projection.scenarioBest.terminalMgdl || points.size <= 2)
    }

    @Test
    fun mealAbsorptionFloor_gatePathMinBlocksFalseSafeClamp() {
        val bg = 130.0
        val steps = 13
        // Pre-lift path bottoms near 70 — below Clamp SCN_PATHMIN (80).
        val descending = List(steps) { i -> bg - i * 5.0 }
        val curves = AdvancedPredictionCurves(
            iob = descending,
            cob = descending,
            uam = descending,
            zt = descending,
            hybrid = descending,
        )
        val projection = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = bg,
                deltaMgdlPer5 = -3.0f,
                curves = curves,
                context = ScenarioProjectionContext(
                    mealAbsorptionMemoryActive = true,
                    mealAbsorptionPhase = MealAbsorptionPhase.FIRST_WAVE,
                    mealAbsorptionBestTFloorAboveBgMgdl = 20.0,
                ),
            ),
        )
        assertTrue(projection.scenarioBest.gatePathMinMgdl < ClampPkpdScenarioReconcile.SCN_PATHMIN_MGDL)
        // Display min may be lifted; gates must still see the unsafe pre-lift min.
        assertTrue(projection.scenarioBest.pathMinMgdl >= projection.scenarioBest.gatePathMinMgdl)
        val clamp = ClampPkpdScenarioReconcile.reconcile(
            ClampPkpdScenarioReconcile.Input(
                bgMgdl = bg,
                targetBgMgdl = 100.0,
                deltaMgdl5m = 1.0,
                pkpdEventualMgdl = 90.0,
                scenarioTerminalMgdl = projection.scenarioBest.terminalMgdl,
                scenarioPathMinMgdl = projection.scenarioBest.gatePathMinMgdl,
                scenarioPathMinHitFloor = projection.scenarioBest.gatePathMinHitFloor,
                digestionOrMealActive = true,
                sportTime = false,
                postHypoDeliveryActive = false,
            ),
        )
        assertFalse(clamp.reconciled)
    }

    @Test
    fun physioCap_doesNotRewritePointZero() {
        val bg = 122.0
        val rising = listOf(bg, 200.0, 210.0, 220.0)
        val curves = AdvancedPredictionCurves(
            iob = rising,
            cob = rising,
            uam = rising,
            zt = rising,
            hybrid = rising,
        )
        val projection = ScenarioProjectionEngine.build(
            ScenarioProjectionInput(
                bgNowMgdl = bg,
                deltaMgdlPer5 = 4.0f,
                curves = curves,
                context = ScenarioProjectionContext(
                    physiologicalPhase = PhysiologicalPhase.DAWN_CORTISOL,
                    suppressMealLikeUam = true,
                    scenarioBestCapAboveBgMgdl = 50.0,
                ),
            ),
        )
        assertEquals(bg.toInt(), projection.scenarioBest.pointsMgdl.first())
    }

    @Test
    fun preserveHysteresis_holdsAcrossBriefGeometryDrop() {
        InsulinSlopePreserveHysteresis.reset()
        assertTrue(InsulinSlopePreserveHysteresis.stabilize(true))
        repeat(InsulinSlopePreserveHysteresis.HOLD_TICKS_DEFAULT) {
            assertTrue(InsulinSlopePreserveHysteresis.stabilize(false))
        }
        assertFalse(InsulinSlopePreserveHysteresis.stabilize(false))
    }
}
