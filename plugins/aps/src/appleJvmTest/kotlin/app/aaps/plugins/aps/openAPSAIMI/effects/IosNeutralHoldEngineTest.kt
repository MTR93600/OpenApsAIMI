package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aimiengine.AimiCommonEngineSwitch
import app.aaps.plugins.aimiengine.HoldAimiEngine
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimitestkit.AimiTestSnapshots
import app.aaps.plugins.aps.openAPSAIMI.AimiDecisionContext
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
            assertFalse(neutral.portLog.any { it.contains("EFFORT_BELIEF") }, neutral.portLog.toString())
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
            assertEquals(neutral.pkpdFloor.telemetry, neutral.scratch.lastPkpdSoftFloorTelemetry)
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

    /**
     * Android records the floor inside advanced predictions, after the wearable read and before
     * safety. Safety then returns the quarter basal and does not reach meal onset. The dose does
     * not re-read the stored telemetry. The export field is that same object.
     */
    @Test
    fun lowPredictionRecordsTheFloorAfterWearableAndBeforeTheDose() {
        AimiCommonEngineSwitch.enabled = true
        val (hold, neutral) = holdAimiEngineWired(IosNeutralScene.LOW_PREDICTION)
        val result = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val tbr = result.command as AimiTherapyCommand.TempBasal
        assertEquals("0.25", aimiFmt2(tbr.rateUPerHour))
        assertEquals(IOS_NEUTRAL_TBR_DURATION_MS, tbr.durationMs)
        assertNull(neutral.mealOnset)

        val log = neutral.portLog
        val floorLine =
            "PKPD_SOFT_FLOOR: raw=39 soft=39 hybT=39 hitFloor=true applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled"
        val wearableAt = log.indexOf(IosNeutralLog.WEARABLE)
        val floorAt = log.indexOf(floorLine)
        assertTrue(wearableAt >= 0, log.toString())
        assertTrue(floorAt > wearableAt, log.toString())
        assertEquals(floorAt + 1, log.indexOf(IosNeutralLog.PKPD))
        assertEquals(log.lastIndex, log.indexOf(IosNeutralLog.PKPD))

        val telemetry = neutral.scratch.lastPkpdSoftFloorTelemetry
        assertEquals(39.0, telemetry?.rawPathMinMgdl)
        assertEquals(39.0, telemetry?.softPathMinMgdl)
        assertEquals(39.0, telemetry?.hybridTerminalMgdl)
        assertEquals(true, telemetry?.hitNumericFloor)
        assertEquals(false, telemetry?.applied)
        assertEquals(false, telemetry?.endogenousReversionEnabled)
        assertEquals(false, telemetry?.suppressedByFallingTrend)
        assertEquals("endo_reversion_disabled", telemetry?.reason)

        val ctx = AimiDecisionContext(
            event_id = "quarter-basal",
            timestamp = 1_700_000_000_000L,
            trigger = "low-prediction",
            baseline_state = AimiDecisionContext.BaselineState(
                profile_isf_mgdl = 50.0,
                profile_basal_uph = 1.0,
                current_bg_mgdl = 100.0,
                cob_g = 0.0,
                iob_u = 2.0,
            ),
        )
        ctx.adjustments.pkpd_soft_floor = telemetry?.toJsonObject()
        val json = ctx.toMedicalJson()
        assertTrue(json.contains("\"pkpd_soft_floor\""), json)
        assertTrue(json.contains("\"raw_path_min_mgdl\":39"), json)
        assertTrue(json.contains("\"soft_path_min_mgdl\":39"), json)
        assertTrue(json.contains("\"hybrid_terminal_mgdl\":39"), json)
        assertTrue(json.contains("\"hit_numeric_floor\":true"), json)
        assertTrue(json.contains("\"applied\":false"), json)
        assertTrue(json.contains("\"endogenous_reversion_enabled\":false"), json)
        assertTrue(json.contains("\"reason\":\"endo_reversion_disabled\""), json)

        neutral.scratch.lastPkpdSoftFloorTelemetry =
            telemetry?.copy(softPathMinMgdl = 999.0, rawPathMinMgdl = 999.0)
        assertEquals("0.25", aimiFmt2(iosNeutralLowPredictionTbrUph()))
    }

    @Test
    fun mealSportAndNightDoNotRecordTheLowPredictionFloor() {
        AimiCommonEngineSwitch.enabled = true
        for (scene in listOf(IosNeutralScene.MEAL, IosNeutralScene.SPORT, IosNeutralScene.NIGHT)) {
            val (hold, neutral) = holdAimiEngineWired(scene)
            hold.evaluate(
                AimiTestSnapshots.emptyInput(),
                AimiTestSnapshots.emptyState(),
                AimiTestSnapshots.emptyModels(),
            )
            assertFalse(
                neutral.portLog.any { it.startsWith("PKPD_SOFT_FLOOR") },
                "$scene ${neutral.portLog}",
            )
            assertNull(neutral.scratch.lastPkpdSoftFloorTelemetry)
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
