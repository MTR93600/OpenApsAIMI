package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IosNeutralPortsTest {

    @Test
    fun virtualCobEffortVetoWearableAndSkippedPortsLogTheirMode() {
        val log = mutableListOf<String>()
        assertEquals(0.0, iosNeutralVirtualCobG(log))
        assertEquals(1.0, iosNeutralEffortSmbFactor(log))
        assertFalse(iosNeutralEffortVeto(log))
        iosNeutralPatientRuntimeSkipped(log)
        iosNeutralTpoSkipped(log)
        val wearable = iosNeutralEmptyWearable(log)
        assertEquals(0, wearable.stepsLast5m)
        assertEquals(0, wearable.hrNow)
        assertEquals("IDLE", wearable.activityState)
        assertFalse(wearable.isValid)
        assertEquals(
            listOf(
                IosNeutralLog.VIRTUAL_COB,
                IosNeutralLog.EFFORT,
                IosNeutralLog.VETO,
                IosNeutralLog.PATIENT,
                IosNeutralLog.TPO,
                IosNeutralLog.WEARABLE,
            ),
            log,
        )
    }

    @Test
    fun earlyScratchPerformsTheAndroidWrites() {
        val scratch = IosEarlyTickScratch()
        val writes = scratch.reset(effectiveDiaHours = 5.0, effectivePeakMinutes = 75.0, noise = 0)
        assertEquals(IOS_EARLY_SCRATCH_WRITE_COUNT, writes)
        assertEquals("IOS_NEUTRAL earlyScratch writes=27", IosNeutralLog.earlyScratch(writes))
        assertFalse(scratch.exerciseInsulinLockoutActive)
        assertFalse(scratch.exerciseHyperBasalOverrideActive)
        assertFalse(scratch.aimiContextActivityActive)
        assertFalse(scratch.pkpdAbsorptionGuardAppliedThisTick)
        assertFalse(scratch.criticalSafetyZeroedThisTick)
        assertNull(scratch.cachedRiskEnvelopeEarly)
        assertNull(scratch.cachedRiskEnvelopeDecision)
        assertNull(scratch.lastSafetyRiskExport)
        assertNull(scratch.lastScenarioProjection)
        assertNull(scratch.lastPredDivergenceExport)
        assertNull(scratch.lastDecisionPredictionAuthority)
        assertNull(scratch.lastIntelligenceSnapshot)
        assertNull(scratch.lastPredictionAuthorityApplyResult)
        assertNull(scratch.lastDoseTerminalSnapshot)
        assertNull(scratch.lastPkpdSoftFloorTelemetry)
        assertNull(scratch.tubeDoseBaseline)
        assertFalse(scratch.tubeAppliedFromDoseSnapshotThisTick)
        assertFalse(scratch.isConfirmedHighRiseThisTick)
        assertNull(scratch.correctionAggressionDecision)
        assertFalse(scratch.mealAdvisorOneShotThisTick)
        assertNull(scratch.lastTubeAdvisorSmbCapScale)
        assertNull(scratch.lastTubeAdvisorTrace)
        assertNull(scratch.lastInflammationResult)
        assertNull(scratch.tickInsulinActionState)
        assertEquals(5.0, scratch.tickEffectiveDiaHours)
        assertEquals(75.0, scratch.tickEffectivePeakMinutes)
        assertEquals(0, scratch.lastLoopCgmNoise)
    }

    @Test
    fun memoScenesMatchTheLockedNumbersAndTheFloorIsNotReread() {
        val mealLog = mutableListOf<String>()
        val meal = iosNeutralMealAdvisor(iosNeutralEffortSmbFactor(mealLog), mealLog)
        assertEquals("3.30", aimiFmt2(meal.bolusU))
        assertEquals("2.00", aimiFmt2(meal.tbrUph))
        assertEquals(30, meal.tbrMin)
        assertEquals("1.30", aimiFmt2(iosNeutralSportTbrUph()))
        assertEquals("1.00", aimiFmt2(iosNeutralNightTbrUph()))
        assertEquals("0.25", aimiFmt2(iosNeutralLowPredictionTbrUph()))

        val memory = IosPkpdFloorMemory()
        val scratch = IosEarlyTickScratch()
        scratch.reset(effectiveDiaHours = 5.0, effectivePeakMinutes = 75.0, noise = 0)
        val floorLog = mutableListOf<String>()
        val stored = iosNeutralStorePkpdFloor(iosNeutralFloorCurves(), memory, scratch, floorLog)
        assertEquals(39.0, stored.rawPathMinMgdl)
        assertEquals(39.0, stored.softPathMinMgdl)
        assertEquals(39.0, stored.hybridTerminalMgdl)
        assertTrue(stored.hitNumericFloor)
        assertFalse(stored.applied)
        assertFalse(stored.endogenousReversionEnabled)
        assertEquals("endo_reversion_disabled", stored.reason)
        assertEquals(stored, scratch.lastPkpdSoftFloorTelemetry)
        assertTrue(floorLog.contains(IosNeutralLog.PKPD))
        memory.telemetry = stored.copy(softPathMinMgdl = 999.0, rawPathMinMgdl = 999.0)
        assertEquals("0.25", aimiFmt2(iosNeutralLowPredictionTbrUph()))
    }

    @Test
    fun sportOnsetStaysFalseWhenTheVetoIsFalse() {
        val log = mutableListOf<String>()
        val veto = iosNeutralEffortVeto(log)
        val onset = decideDetectMealOnset(
            delta = 5f,
            predictedDelta = 5f,
            acceleration = 0f,
            predictedBg = 180f,
            targetBg = 100f,
            effortSuppressesUndeclaredMeal = veto,
        )
        assertFalse(onset)
    }
}
