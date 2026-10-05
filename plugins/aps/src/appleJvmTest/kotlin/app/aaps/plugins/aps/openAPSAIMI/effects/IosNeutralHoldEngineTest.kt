package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aimiengine.AimiCommonEngineSwitch
import app.aaps.plugins.aimiengine.HoldAimiEngine
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimitestkit.AimiTestSnapshots
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseHysteresis
import app.aaps.plugins.aps.openAPSAIMI.scenario.InsulinSlopePreserveHysteresis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosNeutralHoldEngineTest {

    @AfterTest
    fun switchOff() {
        AimiCommonEngineSwitch.enabled = false
        iosNeutralResetHysteresisForTest(mutableListOf())
    }

    @Test
    fun switchOffKeepsEngineNotExtracted() {
        val (hold, neutral) = holdAimiEngineWired(IosNeutralScene.MEAL)
        AimiCommonEngineSwitch.enabled = false
        val result = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(generation = 7L),
            AimiTestSnapshots.emptyModels(),
        )
        val command = result.command
        assertTrue(command is AimiTherapyCommand.Hold)
        assertEquals(HoldAimiEngine.REASON_NOT_EXTRACTED, command.reasonCode)
        assertEquals(HoldAimiEngine.REASON_NOT_EXTRACTED, result.telemetry.reasonCode)
        assertEquals(HoldAimiEngine.REASON_NOT_EXTRACTED, result.safety.holdReasonCode)
        assertEquals(7L, result.nextState.generation)
        assertNull(result.pairedCommand)
        assertTrue(result.trainingEvents.isEmpty())
        assertTrue(result.persistenceEvents.isEmpty())
        assertTrue(neutral.portLog.isEmpty())
    }

    @Test
    fun switchOnMealSportNightAndLowPredictionMatchTheMemo() {
        AimiCommonEngineSwitch.enabled = true
        assertScene(IosNeutralScene.MEAL) { result, neutral ->
            val smb = result.command as AimiTherapyCommand.Smb
            val tbr = result.pairedCommand as AimiTherapyCommand.TempBasal
            assertEquals("3.30", aimiFmt2(smb.insulinU))
            assertEquals("2.00", aimiFmt2(tbr.rateUPerHour))
            assertEquals(IOS_NEUTRAL_TBR_DURATION_MS, tbr.durationMs)
            assertEquals("MEAL_ADVISOR", result.telemetry.reasonCode)
            assertNull(result.safety.holdReasonCode)
            assertModeLines(neutral)
        }
        assertScene(IosNeutralScene.SPORT) { result, neutral ->
            val tbr = result.command as AimiTherapyCommand.TempBasal
            assertEquals("1.30", aimiFmt2(tbr.rateUPerHour))
            assertEquals(IOS_NEUTRAL_TBR_DURATION_MS, tbr.durationMs)
            assertNull(result.pairedCommand)
            assertEquals(false, neutral.mealOnset)
            assertModeLines(neutral)
        }
        assertScene(IosNeutralScene.NIGHT) { result, neutral ->
            val tbr = result.command as AimiTherapyCommand.TempBasal
            assertEquals("1.00", aimiFmt2(tbr.rateUPerHour))
            assertEquals(IOS_NEUTRAL_TBR_DURATION_MS, tbr.durationMs)
            assertNull(result.pairedCommand)
            assertModeLines(neutral)
        }
        assertScene(IosNeutralScene.LOW_PREDICTION) { result, neutral ->
            val tbr = result.command as AimiTherapyCommand.TempBasal
            assertEquals("0.25", aimiFmt2(tbr.rateUPerHour))
            assertEquals(IOS_NEUTRAL_TBR_DURATION_MS, tbr.durationMs)
            assertNull(result.pairedCommand)
            assertEquals(39.0, neutral.pkpdFloor.telemetry?.rawPathMinMgdl)
            assertEquals(39.0, neutral.pkpdFloor.telemetry?.softPathMinMgdl)
            assertNull(neutral.scratch.lastPkpdSoftFloorTelemetry)
            neutral.pkpdFloor.telemetry = neutral.pkpdFloor.telemetry?.copy(softPathMinMgdl = 999.0)
            val again = holdAimiEngineWired(IosNeutralScene.LOW_PREDICTION).first.evaluate(
                AimiTestSnapshots.emptyInput(),
                AimiTestSnapshots.emptyState(),
                AimiTestSnapshots.emptyModels(),
            )
            val againTbr = again.command as AimiTherapyCommand.TempBasal
            assertEquals("0.25", aimiFmt2(againTbr.rateUPerHour))
            assertModeLines(neutral)
        }
    }

    private fun assertScene(
        scene: IosNeutralScene,
        check: (app.aaps.plugins.aimicontracts.AimiTickResult, IosNeutralAimiEngine) -> Unit,
    ) {
        val (hold, neutral) = holdAimiEngineWired(scene)
        val result = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        check(result, neutral)
    }

    private fun assertModeLines(neutral: IosNeutralAimiEngine) {
        val log = neutral.portLog
        assertTrue(log.contains(IosNeutralLog.HYSTERESIS), log.toString())
        assertTrue(log.contains(IosNeutralLog.earlyScratch(IOS_EARLY_SCRATCH_WRITE_COUNT)), log.toString())
        assertTrue(log.contains(IosNeutralLog.VIRTUAL_COB), log.toString())
        assertTrue(log.contains(IosNeutralLog.EFFORT), log.toString())
        assertTrue(log.contains(IosNeutralLog.VETO), log.toString())
        assertTrue(log.contains(IosNeutralLog.PATIENT), log.toString())
        assertTrue(log.contains(IosNeutralLog.TPO), log.toString())
        assertTrue(log.contains(IosNeutralLog.WEARABLE), log.toString())
        assertTrue(log.contains(IosNeutralLog.PKPD), log.toString())
        assertFalse(log.any { it.contains("Exception") })
    }

    @Test
    fun defaultTickKeepsTheAndroidHysteresisAndTheTestOptionClearsIt() {
        MealAbsorptionPhaseHysteresis.stabilize(wave(MealAbsorptionPhase.FIRST_WAVE))
        MealAbsorptionMemory.lastPhase = MealAbsorptionPhase.FIRST_WAVE
        InsulinSlopePreserveHysteresis.stabilize(true)
        assertTrue(InsulinSlopePreserveHysteresis.stabilize(false))
        val scratch = IosEarlyTickScratch()
        scratch.reset(effectiveDiaHours = 5.0, effectivePeakMinutes = 75.0, noise = 0)
        val startLog = mutableListOf<String>()
        iosNeutralTickStart(startLog)
        assertEquals(listOf(IosNeutralLog.HYSTERESIS), startLog)
        val stillHeld = MealAbsorptionPhaseHysteresis.stabilize(wave(MealAbsorptionPhase.NONE))
        assertEquals(MealAbsorptionPhase.FIRST_WAVE, stillHeld.phase)
        assertEquals("meal absorption hysteresis hold", stillHeld.reason)
        assertEquals(MealAbsorptionPhase.FIRST_WAVE, MealAbsorptionMemory.lastPhase)
        val resetLog = mutableListOf<String>()
        iosNeutralResetHysteresisForTest(resetLog)
        assertEquals(listOf(IosNeutralLog.HYSTERESIS_RESET_TEST), resetLog)
        val released = MealAbsorptionPhaseHysteresis.stabilize(wave(MealAbsorptionPhase.NONE))
        assertEquals(MealAbsorptionPhase.NONE, released.phase)
        assertEquals(MealAbsorptionPhase.NONE, MealAbsorptionMemory.lastPhase)
        assertFalse(InsulinSlopePreserveHysteresis.stabilize(false))
    }

    private fun wave(phase: MealAbsorptionPhase) = MealAbsorptionPhaseEngine.Output(
        phase = phase,
        belief = 1.0,
        reason = "raw",
        deltaMgdlPer5 = 5.0,
        gapMgdl = 10.0,
        bestTerminalMgdl = 180.0,
        memoryActive = false,
        waveCount = 1,
        mealDeliveryPriority = true,
        chronoPrior = 0.0,
        kineticScore = 0.0,
        trajectoryScore = 0.0,
        physioScore = 0.0,
    )
}
