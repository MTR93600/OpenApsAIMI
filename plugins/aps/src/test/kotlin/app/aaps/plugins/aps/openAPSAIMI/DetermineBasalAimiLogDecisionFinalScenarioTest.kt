package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetrics
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryModulation
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Calendar

/**
 * Phase 2 P1 : chemins [logDecisionFinal] supplémentaires → `updateLearning` une fois (décision A).
 * Couverture : SAFETY (bruit), TRAJ_SAFETY, HARD_BRAKE, COMPRESSION, MEAL_ADVISOR, AUTODRIVE_V3,
 * DRIFT_TERMINATOR, MAX_IOB. (Chemin **AUTODRIVE** tag `AUTODRIVE` V2 seul sans tick V3 : instable dans ce harnais → pas de test dédié ici.)
 */
class DetermineBasalAimiLogDecisionFinalScenarioTest {

    /** `nightbis` = heure locale ≤ 7 → Drift / autodrive V2 bloqués ; sans `--add-opens` on ne mock pas [Calendar] (Java 17+). */
    private fun assumeOutsideNightBisWindow() {
        val hour = Calendar.getInstance()[Calendar.HOUR_OF_DAY]
        assumeTrue(hour >= 8, "Scenario needs local hour >= 8 (nightbis <=7 would skip drift/autodrive V2 path)")
    }

    private fun preferencesBase(): Preferences = mockk(relaxed = true) {
        every { get(BooleanKey.OApsAIMIT3cBrittleMode) } returns false
        every { get(BooleanKey.AimiPhysioAssistantEnable) } returns false
        every { get(BooleanKey.OApsAIMIUnifiedReactivityEnabled) } returns false
        every { get(BooleanKey.OApsAIMIautoDriveActive) } returns false
        every { get(BooleanKey.OApsAIMIMealAdvisorTrigger) } returns false
        every { get(DoubleKey.OApsAIMIMaxSMB) } returns 0.5
        every { get(DoubleKey.OApsAIMIHighBGMaxSMB) } returns 1.0
        every { get(DoubleKey.OApsAIMITDD7) } returns 48.0
    }

    private fun profileStandard() = mockk<OapsProfileAimi>(relaxed = true) {
        every { TDD } returns 48.0
        every { current_basal } returns 1.0
        every { max_daily_basal } returns 48.0
        every { max_basal } returns 5.0
        every { max_iob } returns 20.0
        every { sens } returns 50.0
        every { target_bg } returns 100.0
        every { min_bg } returns 80.0
        every { max_bg } returns 180.0
        every { carb_ratio } returns 10.0
        every { dia } returns 5.0
        every { peakTime } returns 60.0
        every { enableUAM } returns false
        every { currentActivity } returns 0.0
        every { futureActivity } returns 0.0
        every { sensorLagActivity } returns 0.0
        every { historicActivity } returns 0.0
        every { lgsThreshold } returns 70
        every { variable_sens } returns 0.0
    }

    @Test
    fun `SAFETY high noise triggers logDecisionFinal and learning once`() {
        val now = System.currentTimeMillis()
        val prefs = preferencesBase()
        val basalNeuralLearner = mockk<BasalNeuralLearner>(relaxed = true)
        val harness = DetermineBasalAimiScenarioTestHarness(
            now = now,
            preferences = prefs,
            trajectoryGuard = mockk(relaxed = true),
            autodriveGater = mockk(relaxed = true),
            autodriveEngine = mockk(relaxed = true),
            physioAdapter = mockk(relaxed = true),
            basalNeuralLearner = basalNeuralLearner
        )
        val rt = harness.engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 120.0
                every { delta } returns 0.0
                every { shortAvgDelta } returns 0.0
                every { longAvgDelta } returns 0.0
                every { combinedDelta } returns 0.0
                every { date } returns now
                every { noise } returns 3.0
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true) {
                every { duration } returns 0
                every { rate } returns 1.0
            },
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true) {
                every { iob } returns 0.5
                every { lastBolusTime } returns 0L
            }),
            profile = profileStandard(),
            autosens_data = mockk<AutosensResult>(relaxed = true) { every { ratio } returns 1.0 },
            mealData = mockk<MealData>(relaxed = true) {
                every { lastCarbTime } returns 1L
                every { mealCOB } returns 0.0
                every { slopeFromMinDeviation } returns 0.0
            },
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "SAFETY noise scenario"
        )
        assertThat(rt).isNotNull()
        verify(exactly = 1) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `TRAJ_SAFETY tight spiral bridge triggers learning once`() {
        val now = System.currentTimeMillis()
        val prefs = preferencesBase()
        val basalNeuralLearner = mockk<BasalNeuralLearner>(relaxed = true)
        val traj = mockk<TrajectoryGuard>(relaxed = true)
        val tightSpiral = TrajectoryAnalysis(
            classification = TrajectoryType.TIGHT_SPIRAL,
            metrics = TrajectoryMetrics(
                curvature = 0.5,
                convergenceVelocity = 0.0,
                coherence = 0.5,
                energyBalance = 2.0,
                openness = 0.5
            ),
            modulation = TrajectoryModulation.NEUTRAL,
            warnings = emptyList(),
            stableOrbitDistance = 0.0,
            predictedConvergenceTime = null,
            timestamp = now
        )
        every { traj.getLastAnalysis() } returns tightSpiral
        val harness = DetermineBasalAimiScenarioTestHarness(
            now = now,
            preferences = prefs,
            trajectoryGuard = traj,
            autodriveGater = mockk(relaxed = true),
            autodriveEngine = mockk(relaxed = true),
            physioAdapter = mockk(relaxed = true),
            basalNeuralLearner = basalNeuralLearner
        )
        harness.engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 100.0
                every { delta } returns 0.0
                every { shortAvgDelta } returns 0.0
                every { longAvgDelta } returns 0.0
                every { combinedDelta } returns 0.0
                every { date } returns now
                every { noise } returns 0.0
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true) {
                every { duration } returns 0
                every { rate } returns 1.0
            },
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true) {
                every { iob } returns 2.0
                every { lastBolusTime } returns 0L
            }),
            profile = profileStandard(),
            autosens_data = mockk<AutosensResult>(relaxed = true) { every { ratio } returns 1.0 },
            mealData = mockk<MealData>(relaxed = true) {
                every { lastCarbTime } returns 1L
                every { mealCOB } returns 0.0
                every { slopeFromMinDeviation } returns 0.0
            },
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "TRAJ_SAFETY scenario"
        )
        verify(exactly = 1) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `HARD_BRAKE falling decelerating triggers learning once`() {
        val now = System.currentTimeMillis()
        val prefs = preferencesBase()
        val basalNeuralLearner = mockk<BasalNeuralLearner>(relaxed = true)
        val harness = DetermineBasalAimiScenarioTestHarness(
            now = now,
            preferences = prefs,
            trajectoryGuard = mockk(relaxed = true),
            autodriveGater = mockk(relaxed = true),
            autodriveEngine = mockk(relaxed = true),
            physioAdapter = mockk(relaxed = true),
            basalNeuralLearner = basalNeuralLearner
        )
        harness.engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 100.0
                every { delta } returns -1.0
                every { shortAvgDelta } returns -1.0
                every { longAvgDelta } returns -1.5
                every { combinedDelta } returns -1.0
                every { date } returns now
                every { noise } returns 0.0
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true) {
                every { duration } returns 0
                every { rate } returns 1.0
            },
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true) {
                every { iob } returns 0.5
                every { lastBolusTime } returns 0L
            }),
            profile = profileStandard(),
            autosens_data = mockk<AutosensResult>(relaxed = true) { every { ratio } returns 1.0 },
            mealData = mockk<MealData>(relaxed = true) {
                every { lastCarbTime } returns 1L
                every { mealCOB } returns 0.0
                every { slopeFromMinDeviation } returns 0.0
            },
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "HARD_BRAKE scenario"
        )
        verify(exactly = 1) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `COMPRESSION impossible rise delta triggers learning once`() {
        val now = System.currentTimeMillis()
        val prefs = preferencesBase()
        val basalNeuralLearner = mockk<BasalNeuralLearner>(relaxed = true)
        val harness = DetermineBasalAimiScenarioTestHarness(
            now = now,
            preferences = prefs,
            trajectoryGuard = mockk(relaxed = true),
            autodriveGater = mockk(relaxed = true),
            autodriveEngine = mockk(relaxed = true),
            physioAdapter = mockk(relaxed = true),
            basalNeuralLearner = basalNeuralLearner
        )
        harness.engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 140.0
                every { delta } returns 40.0
                every { shortAvgDelta } returns 40.0
                every { longAvgDelta } returns 40.0
                every { combinedDelta } returns 40.0
                every { date } returns now
                every { noise } returns 0.0
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true) {
                every { duration } returns 0
                every { rate } returns 1.0
            },
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true) {
                every { iob } returns 0.5
                every { lastBolusTime } returns 0L
            }),
            profile = profileStandard(),
            autosens_data = mockk<AutosensResult>(relaxed = true) { every { ratio } returns 1.0 },
            mealData = mockk<MealData>(relaxed = true) {
                every { lastCarbTime } returns 1L
                every { mealCOB } returns 0.0
                every { slopeFromMinDeviation } returns 0.0
            },
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "COMPRESSION scenario"
        )
        verify(exactly = 1) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `MEAL_ADVISOR passive estimate triggers learning once`() {
        val now = System.currentTimeMillis()
        val prefs = preferencesBase()
        every { prefs.get(DoubleKey.OApsAIMILastEstimatedCarbs) } returns 60.0
        every { prefs.get(DoubleKey.OApsAIMILastEstimatedCarbTime) } returns now.toDouble()
        every { prefs.get(DoubleKey.meal_modes_MaxBasal) } returns 5.0
        val basalNeuralLearner = mockk<BasalNeuralLearner>(relaxed = true)
        val harness = DetermineBasalAimiScenarioTestHarness(
            now = now,
            preferences = prefs,
            trajectoryGuard = mockk(relaxed = true),
            autodriveGater = mockk(relaxed = true),
            autodriveEngine = mockk(relaxed = true),
            physioAdapter = mockk(relaxed = true),
            basalNeuralLearner = basalNeuralLearner
        )
        harness.engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 120.0
                every { delta } returns 0.0
                every { shortAvgDelta } returns 0.0
                every { longAvgDelta } returns 0.0
                every { combinedDelta } returns 0.0
                every { date } returns now
                every { noise } returns 0.0
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true) {
                every { duration } returns 0
                every { rate } returns 1.0
            },
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true) {
                every { iob } returns 0.5
                every { lastBolusTime } returns 0L
            }),
            profile = profileStandard(),
            autosens_data = mockk<AutosensResult>(relaxed = true) { every { ratio } returns 1.0 },
            mealData = mockk<MealData>(relaxed = true) {
                every { lastCarbTime } returns 1L
                every { mealCOB } returns 0.0
                every { slopeFromMinDeviation } returns 0.0
            },
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "MEAL_ADVISOR scenario"
        )
        verify(exactly = 1) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `AUTODRIVE_V3 safe tick triggers learning once`() {
        val now = System.currentTimeMillis()
        val prefs = preferencesBase()
        every { prefs.get(BooleanKey.OApsAIMIautoDriveActive) } returns true
        every { prefs.get(DoubleKey.OApsAIMIweight) } returns 70.0
        val basalNeuralLearner = mockk<BasalNeuralLearner>(relaxed = true)
        val gater = mockk<AutoDriveGater>(relaxed = true)
        every {
            gater.shouldEngageV3(any(), any(), any(), any(), any(), any())
        } returns AutoDriveGater.GatingResult(engage = true, reason = "test harness")
        val adEngine = mockk<AutodriveEngine>(relaxed = true)
        every {
            adEngine.tick(any(), any(), any(), any(), any(), any(), any(), any())
        } returns AutoDriveCommand(
            scheduledMicroBolus = 0.0,
            temporaryBasalRate = 2.0,
            isSafe = true,
            reason = "harness v3"
        )
        val harness = DetermineBasalAimiScenarioTestHarness(
            now = now,
            preferences = prefs,
            trajectoryGuard = mockk(relaxed = true),
            autodriveGater = gater,
            autodriveEngine = adEngine,
            physioAdapter = mockk(relaxed = true),
            basalNeuralLearner = basalNeuralLearner
        )
        harness.engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 160.0
                every { delta } returns 0.0
                every { shortAvgDelta } returns 0.0
                every { longAvgDelta } returns 0.0
                every { combinedDelta } returns 1.0
                every { date } returns now
                every { noise } returns 0.0
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true) {
                every { duration } returns 0
                every { rate } returns 1.0
            },
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true) {
                every { iob } returns 0.5
                every { lastBolusTime } returns 0L
            }),
            profile = profileStandard(),
            autosens_data = mockk<AutosensResult>(relaxed = true) { every { ratio } returns 1.0 },
            mealData = mockk<MealData>(relaxed = true) {
                every { lastCarbTime } returns 1L
                every { mealCOB } returns 0.0
                every { slopeFromMinDeviation } returns 0.0
            },
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "AUTODRIVE_V3 scenario"
        )
        verify(exactly = 1) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `DRIFT_TERMINATOR plateau triggers learning once`() {
        assumeOutsideNightBisWindow()
        val now = System.currentTimeMillis()
        val prefs = preferencesBase()
        every { prefs.get(BooleanKey.OApsAIMIautoDriveActive) } returns false
        val basalNeuralLearner = mockk<BasalNeuralLearner>(relaxed = true)
        val harness = DetermineBasalAimiScenarioTestHarness(
            now = now,
            preferences = prefs,
            trajectoryGuard = mockk(relaxed = true),
            autodriveGater = mockk(relaxed = true),
            autodriveEngine = mockk(relaxed = true),
            physioAdapter = mockk(relaxed = true),
            basalNeuralLearner = basalNeuralLearner,
        )
        harness.engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 135.0
                every { delta } returns 1.0
                every { shortAvgDelta } returns 1.0
                every { longAvgDelta } returns 0.5
                every { combinedDelta } returns 1.5
                every { date } returns now
                every { noise } returns 0.0
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true) {
                every { duration } returns 0
                every { rate } returns 1.0
            },
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true) {
                every { iob } returns 0.5
                every { lastBolusTime } returns 0L
            }),
            profile = profileStandard(),
            autosens_data = mockk<AutosensResult>(relaxed = true) { every { ratio } returns 1.0 },
            mealData = mockk<MealData>(relaxed = true) {
                every { lastCarbTime } returns 1L
                every { mealCOB } returns 0.0
                every { slopeFromMinDeviation } returns 2.0
            },
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = true,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "DRIFT_TERMINATOR scenario",
        )
        verify(exactly = 1) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `MAX_IOB cap path triggers learning once`() {
        assumeOutsideNightBisWindow()
        val now = System.currentTimeMillis()
        val prefs = preferencesBase()
        every { prefs.get(DoubleKey.ApsSmbMaxIob) } returns 10.0
        val basalNeuralLearner = mockk<BasalNeuralLearner>(relaxed = true)
        val harness = DetermineBasalAimiScenarioTestHarness(
            now = now,
            preferences = prefs,
            trajectoryGuard = mockk(relaxed = true),
            autodriveGater = mockk(relaxed = true),
            autodriveEngine = mockk(relaxed = true),
            physioAdapter = mockk(relaxed = true),
            basalNeuralLearner = basalNeuralLearner,
        )
        val profile = profileStandard()
        every { profile.enableSMB_always } returns true
        harness.engine.determine_basal(
            glucose_status = mockk<GlucoseStatusAIMI>(relaxed = true) {
                every { glucose } returns 220.0
                every { delta } returns 2.0
                every { shortAvgDelta } returns 2.0
                every { longAvgDelta } returns 1.0
                every { combinedDelta } returns 3.0
                every { date } returns now
                every { noise } returns 0.0
            },
            currenttemp = mockk<CurrentTemp>(relaxed = true) {
                every { duration } returns 0
                every { rate } returns 1.0
            },
            iob_data_array = arrayOf(mockk<IobTotal>(relaxed = true) {
                every { iob } returns 18.0
                every { lastBolusTime } returns 0L
            }),
            profile = profile,
            autosens_data = mockk<AutosensResult>(relaxed = true) { every { ratio } returns 1.0 },
            mealData = mockk<MealData>(relaxed = true) {
                every { lastCarbTime } returns 1L
                every { mealCOB } returns 0.0
                every { slopeFromMinDeviation } returns 2.0
            },
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mockk<UiInteraction>(relaxed = true),
            extraDebug = "MAX_IOB scenario",
        )
        verify(exactly = 1) {
            basalNeuralLearner.updateLearning(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }
}
