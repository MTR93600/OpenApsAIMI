package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.BS
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.data.model.ICfg
import app.aaps.plugins.aimiengine.AimiCommonEngineSwitch
import app.aaps.plugins.aimiengine.HoldAimiEngine
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimitestkit.AimiTestSnapshots
import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import app.aaps.plugins.aps.openAPSAIMI.AimiDecisionContext
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.tpo.JsonBackedPreferences
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoDeltaBuilder
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPackId
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPersistence
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoProposal
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoSessionManager
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
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
            assertLowPredictionRuntime(neutral)
            neutral.pkpdFloor.telemetry = neutral.pkpdFloor.telemetry?.copy(softPathMinMgdl = 999.0)
            val again = holdAimiEngineWired(IosNeutralScene.LOW_PREDICTION).first.evaluate(
                AimiTestSnapshots.emptyInput(),
                AimiTestSnapshots.emptyState(),
                AimiTestSnapshots.emptyModels(),
            )
            val againTbr = again.command as AimiTherapyCommand.TempBasal
            assertEquals("0.25", aimiFmt2(againTbr.rateUPerHour))
            assertModeLines(neutral, patientSkipped = false)
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
        assertLowPredictionRuntime(neutral)
        val runtimeAt = log.indexOf(
            "TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE",
        )
        assertTrue(runtimeAt > log.indexOf(IosNeutralLog.PKPD), log.toString())

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
    fun emptyBolusCacheLeavesTheMealSmbAndTbr() {
        AimiCommonEngineSwitch.enabled = true
        val insulin = ICfg(insulinLabel = "test", peak = 75, dia = 5.0, concentration = 1.0)
        val store = MemoryAimiTherapyReads(
            boluses = listOf(
                BS(
                    id = 4L,
                    timestamp = 1L,
                    amount = 2.0,
                    type = BS.Type.SMB,
                    isValid = false,
                    iCfg = insulin,
                ),
                BS(
                    id = 5L,
                    timestamp = 1L,
                    amount = 1.0,
                    type = BS.Type.NORMAL,
                    isValid = true,
                    referenceId = 1L,
                    iCfg = insulin,
                ),
            ),
        )
        val (hold, neutral) = holdAimiEngineWired(IosNeutralScene.MEAL, store)
        val result = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val smb = result.command as AimiTherapyCommand.Smb
        val tbr = result.pairedCommand as AimiTherapyCommand.TempBasal
        assertEquals("3.30", aimiFmt2(smb.insulinU))
        assertEquals("2.00", aimiFmt2(tbr.rateUPerHour))
        assertEquals(IOS_NEUTRAL_TBR_DURATION_MS, tbr.durationMs)
        assertTrue(neutral.therapyCaches.boluses.isEmpty())
        assertTrue(neutral.therapyCaches.heartRates.isEmpty())
        assertFalse(neutral.portLog.any { it.contains("HR_TREND_ISF") }, neutral.portLog.toString())
        assertModeLines(neutral)
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

    private fun assertModeLines(neutral: IosNeutralAimiEngine, patientSkipped: Boolean = true) {
        val log = neutral.portLog
        assertTrue(log.contains(IosNeutralLog.HYSTERESIS), log.toString())
        assertTrue(log.contains(IosNeutralLog.earlyScratch(IOS_EARLY_SCRATCH_WRITE_COUNT)), log.toString())
        assertFalse(log.any { it.startsWith("🍽️ VIRTUAL_COB:") }, log.toString())
        assertEquals(0.0, neutral.virtualCobGrams)
        assertTrue(log.contains(IosNeutralLog.EFFORT), log.toString())
        assertTrue(log.contains(IosNeutralLog.VETO), log.toString())
        if (patientSkipped) {
            assertTrue(log.contains(IosNeutralLog.PATIENT), log.toString())
        } else {
            assertFalse(log.contains(IosNeutralLog.PATIENT), log.toString())
            assertLowPredictionRuntime(neutral)
        }
        assertFalse(log.contains(IosNeutralLog.TPO), log.toString())
        assertNull(neutral.tpoInsulinReqU)
        assertTrue(log.contains(IosNeutralLog.WEARABLE), log.toString())
        assertFalse(log.any { it.contains("Exception") })
    }

    private fun assertLowPredictionRuntime(neutral: IosNeutralAimiEngine) {
        val log = neutral.portLog
        assertTrue(
            log.contains("TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE"),
            log.toString(),
        )
        assertTrue(
            log.contains("Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain"),
            log.toString(),
        )
        assertTrue(
            log.contains("MEAL_CERTAINTY level=NONE tree=NONE rise=WEAK terminals=HYPO_CONFLICT effortVeto=false"),
            log.toString(),
        )
        val tbr = neutral.portLog
        assertFalse(tbr.contains(IosNeutralLog.PATIENT), tbr.toString())
    }

    @Test
    fun activeTpoSessionCapsActivityInsulinAtPointTwoAndNightStaysOne() {
        AimiCommonEngineSwitch.enabled = true
        val storage = InMemoryAimiStorage()
        val prefs = JsonBackedPreferences(storage)
        val nowMs = aimiWallClockMs()
        val plan = TpoDeltaBuilder.buildPlan(
            proposal = TpoProposal(
                packId = TpoPackId.POST_HYPO_RECOVERY,
                tier = TuningStepTier.MICRO,
                algoConfidence = 0.90,
                reasonCodes = listOf("post_hypo"),
            ),
            preferences = prefs,
            hypoLoad = 0.0,
            t3cBrittle = false,
        )
        TpoSessionManager(TpoPersistence(storage)).startSession(
            plan = plan,
            preferences = prefs,
            nowMs = nowMs,
            llmResult = null,
            historyRepo = null,
        )
        val (hold, neutral) = holdAimiEngineWired(
            IosNeutralScene.NIGHT,
            MemoryAimiTherapyReads(),
            storage,
        )
        val result = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val tbr = result.command as AimiTherapyCommand.TempBasal
        assertEquals("1.00", aimiFmt2(tbr.rateUPerHour))
        assertEquals(IOS_NEUTRAL_TBR_DURATION_MS, tbr.durationMs)
        assertEquals(0.80, neutral.tpoMaxSmb!!, 1e-9)
        assertEquals(0.20, neutral.tpoInsulinReqU!!, 1e-9)
        assertTrue(
            neutral.portLog.contains("SMB capped by Activity/Recovery (Limit: 0.40)"),
            neutral.portLog.toString(),
        )

        val reloaded = JsonBackedPreferences(storage)
        assertEquals(0.80, reloaded.get(DoubleKey.OApsAIMIMaxSMB), 1e-9)
        val (againHold, again) = holdAimiEngineWired(
            IosNeutralScene.NIGHT,
            MemoryAimiTherapyReads(),
            storage,
        )
        val againResult = againHold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val againTbr = againResult.command as AimiTherapyCommand.TempBasal
        assertEquals("1.00", aimiFmt2(againTbr.rateUPerHour))
        assertEquals(0.20, again.tpoInsulinReqU!!, 1e-9)
    }

    @Test
    fun expiredTpoSessionRestoresTheCeilingAndLeavesTheNightTbr() {
        AimiCommonEngineSwitch.enabled = true
        val storage = InMemoryAimiStorage()
        val prefs = JsonBackedPreferences(storage)
        val plan = TpoDeltaBuilder.buildPlan(
            proposal = TpoProposal(
                packId = TpoPackId.POST_HYPO_RECOVERY,
                tier = TuningStepTier.MICRO,
                algoConfidence = 0.90,
                reasonCodes = listOf("post_hypo"),
            ),
            preferences = prefs,
            hypoLoad = 0.0,
            t3cBrittle = false,
        )
        TpoSessionManager(TpoPersistence(storage)).startSession(
            plan = plan,
            preferences = prefs,
            nowMs = 0L,
            llmResult = null,
            historyRepo = null,
        )
        val (hold, neutral) = holdAimiEngineWired(
            IosNeutralScene.NIGHT,
            MemoryAimiTherapyReads(),
            storage,
        )
        val result = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val tbr = result.command as AimiTherapyCommand.TempBasal
        assertEquals("1.00", aimiFmt2(tbr.rateUPerHour))
        assertNull(neutral.tpoInsulinReqU)
        assertEquals(1.00, neutral.tpoMaxSmb!!, 1e-9)
        assertFalse(
            neutral.portLog.any { it.contains("SMB capped") },
            neutral.portLog.toString(),
        )
        val learner = neutral.portLog
            .dropWhile { it != "═══════════════════════════════" }
            .take(8)
            .joinToString("\n")
        assertEquals(COLD_LEARNER_NIGHT_TRACE, learner)
    }

    @Test
    fun nightTickLogsColdLearnersAtOneUnitAndSportStaysWithoutThem() {
        AimiCommonEngineSwitch.enabled = true
        val storage = InMemoryAimiStorage()
        val (hold, neutral) = holdAimiEngineWired(
            IosNeutralScene.NIGHT,
            MemoryAimiTherapyReads(),
            storage,
        )
        val result = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val tbr = result.command as AimiTherapyCommand.TempBasal
        assertEquals("1.00", aimiFmt2(tbr.rateUPerHour))
        assertEquals(IOS_NEUTRAL_TBR_DURATION_MS, tbr.durationMs)
        val learner = neutral.portLog
            .dropWhile { it != "═══════════════════════════════" }
            .take(8)
            .joinToString("\n")
        assertEquals(COLD_LEARNER_NIGHT_TRACE, learner)

        val (sportHold, sport) = holdAimiEngineWired(
            IosNeutralScene.SPORT,
            MemoryAimiTherapyReads(),
            InMemoryAimiStorage(),
        )
        val sportResult = sportHold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val sportTbr = sportResult.command as AimiTherapyCommand.TempBasal
        assertEquals("1.30", aimiFmt2(sportTbr.rateUPerHour))
        assertFalse(sport.portLog.any { it.contains("AIMI LEARNERS HEALTH") }, sport.portLog.toString())
    }

    @Test
    fun sportOnsetUsesTheCommonVetoAndTheUnlockedSceneStaysOnePointThree() {
        AimiCommonEngineSwitch.enabled = true
        val storage = InMemoryAimiStorage()
        val (hold, neutral) = holdAimiEngineWired(
            IosNeutralScene.SPORT,
            MemoryAimiTherapyReads(),
            storage,
        )
        neutral.onsetAcceleration = 2f
        val open = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        assertEquals(true, neutral.mealOnset)
        val openTbr = open.command as AimiTherapyCommand.TempBasal
        assertEquals(IOS_NEUTRAL_TBR_DURATION_MS, openTbr.durationMs)

        val (vetoHold, veto) = holdAimiEngineWired(
            IosNeutralScene.SPORT,
            MemoryAimiTherapyReads(),
            InMemoryAimiStorage(),
        )
        veto.onsetAcceleration = 2f
        veto.onsetAssessment = EffortActivityBelief.Assessment(
            state = EffortActivityBelief.State.ACTIVE,
            posture = EffortActivityBelief.Posture.EXERTION,
            confidence = 0.30,
            minutesSinceEffort = 0.0,
            smbFactor = 1.0,
            basalFactor = 1.0,
            reasons = emptyList(),
        )
        val closed = vetoHold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        assertEquals(false, veto.mealOnset)
        val closedTbr = closed.command as AimiTherapyCommand.TempBasal
        assertEquals("1.30", aimiFmt2(closedTbr.rateUPerHour))
        assertEquals(IOS_NEUTRAL_TBR_DURATION_MS, closedTbr.durationMs)
    }

    @Test
    fun virtualCobUsesTheSnapshotAndThePreferenceAndLeavesTheMealDose() {
        AimiCommonEngineSwitch.enabled = true
        val (nineResult, nine) = mealWithVirtualCob(
            snapshot = HealthContextSnapshot(),
            signals = nineGramSignals(),
        )
        val smb = nineResult.command as AimiTherapyCommand.Smb
        val tbr = nineResult.pairedCommand as AimiTherapyCommand.TempBasal
        assertEquals("3.30", aimiFmt2(smb.insulinU))
        assertEquals("2.00", aimiFmt2(tbr.rateUPerHour))
        assertEquals(9.0, nine.virtualCobGrams)
        assertTrue(
            nine.portLog.contains(
                "🍽️ VIRTUAL_COB: g=9.0 raw=11.3 cap=25.0 gated=false reason=ra_meal_estimate",
            ),
            nine.portLog.toString(),
        )

        val (inflamedResult, inflamed) = mealWithVirtualCob(
            snapshot = HealthContextSnapshot(hrNow = 110, rhrResting = 60),
            signals = nineGramSignals(),
        )
        assertEquals(0.0, inflamed.virtualCobGrams)
        assertTrue(
            inflamed.portLog.contains(
                "🍽️ VIRTUAL_COB: g=0.0 raw=0.0 cap=0.0 gated=true reason=hr_inflammation",
            ),
            inflamed.portLog.toString(),
        )
        val inflamedSmb = inflamedResult.command as AimiTherapyCommand.Smb
        assertEquals("3.30", aimiFmt2(inflamedSmb.insulinU))
    }

    private fun nineGramSignals() = VirtualCobTickSignals(
        estimatedRaMgdlPerMin = 2.0,
        isfMgdlPerU = 40.0,
        carbRatioGPerU = 10.0,
        bgMgdl = 150.0,
        deltaMgdl5m = 3.0,
        slopeFromMinDeviation = 2.0,
        tdd24hU = 40.0,
        mealProb = 0.8,
    )

    private fun mealWithVirtualCob(
        snapshot: HealthContextSnapshot,
        signals: VirtualCobTickSignals,
    ): Pair<app.aaps.plugins.aimicontracts.AimiTickResult, IosNeutralAimiEngine> {
        val storage = InMemoryAimiStorage()
        val prefs = JsonBackedPreferences(storage)
        prefs.put(BooleanKey.OApsAIMIUndeclaredCobEnabled, true)
        prefs.put(DoubleKey.OApsAIMIweight, 70.0)
        prefs.put(DoubleKey.OApsAIMIUndeclaredCobMaxG, 25.0)
        val neutral = IosNeutralAimiEngine(
            scene = IosNeutralScene.MEAL,
            therapy = MemoryAimiTherapyReads(),
            tpoStorage = storage,
            tpoPreferences = prefs,
        )
        neutral.wearableSnapshot = snapshot
        neutral.virtualCobSignals = signals
        val result = HoldAimiEngine(neutral).evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        return result to neutral
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
