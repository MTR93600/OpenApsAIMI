package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.interfaces.aps.AutosensDataStore
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.pump.PumpWithConcentration
import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.AIMIAdaptiveBasal
import app.aaps.plugins.aps.openAPSAIMI.context.ContextInfluenceEngine
import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent
import app.aaps.plugins.aps.openAPSAIMI.context.ContextManager
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.AimiDecisionContext
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.AimiUamHandler
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import app.aaps.plugins.aps.openAPSAIMI.NGRConfig
import app.aaps.plugins.aps.openAPSAIMI.GlucoseStatusCalculatorAimi
import app.aaps.plugins.aps.openAPSAIMI.advisor.gestation.GestationalAutopilot
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalDecisionEngine
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalPlanner
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.plugins.aps.openAPSAIMI.effects.RbtLiveCommitResult
import app.aaps.plugins.aps.openAPSAIMI.patient.GlobalPhysiologicalState
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaAction
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionBasis
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecisionEnvironment
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalRiskLevel
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.orchestration.DoseTerminalSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporterProvider
import app.aaps.plugins.aps.openAPSAIMI.physio.CircadianMealProfileStore
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseHysteresis
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityStage
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActivityWindow
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLearnedState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdParams
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.pkpd.SmbDamping
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.smb.SmbInstructionExecutor
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiAutonomyMode
import app.aaps.plugins.aps.openAPSAIMI.control.StraightLineTubeAdvisor
import app.aaps.plugins.aps.openAPSAIMI.compose.AimiBehaviorRuntimeProfile
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAuditor
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiTpo
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiSmbComparison
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiEmergencySos
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiHealthContext
import app.aaps.plugins.aps.openAPSAIMI.recursive.AutodriveModeHint
import app.aaps.plugins.aps.openAPSAIMI.recursive.BasalFirstChannel
import app.aaps.plugins.aps.openAPSAIMI.recursive.T3cBasalFirstResolution
import app.aaps.plugins.aps.openAPSAIMI.recursive.DoseChannelResolution
import app.aaps.plugins.aps.openAPSAIMI.recursive.HypoGuardMode
import app.aaps.plugins.aps.openAPSAIMI.recursive.MealChannelHint
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtExtendedSignals
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefAuthorityGate
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefSnapshot
import app.aaps.plugins.aps.openAPSAIMI.recursive.ReleaseAuthority
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import app.aaps.plugins.aps.openAPSAIMI.safety.SafetyDecision
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryMetrics
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryModulation
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryWarning
import app.aaps.plugins.aps.openAPSAIMI.trajectory.WarningSeverity
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorageHelper
import app.aaps.plugins.aps.openAPSAIMI.validation.PumpCapabilityValidator
import app.aaps.plugins.aps.openAPSAIMI.wcycle.ThyroidStatus
import app.aaps.plugins.aps.openAPSAIMI.wcycle.VerneuilStatus
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalTime
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.whenever
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.mockito.stubbing.Answer
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Meal-rise and UAM traces of the current Android shell. Robolectric is required because
 * [AimiUamHandler] reads external storage when the class loads. The engine command is an input;
 * the trace is what this shell does with it.
 *
 * The TICK log stamps [app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs], which is not injectable.
 * That one token is written `ts=<clock>` so the rest of the line stays byte-stable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ShellDecisionTraceTest {

    private val now = 1_700_000_000_000L
    private lateinit var tick: DetermineBasalaimiSMB2
    private lateinit var dateUtil: DateUtil

    @Before
    fun setUp() {
        DetermineBasalaimiSMB2.resetLegacyPrebolusMemoryForTrace()
        MealAbsorptionMemory.reset()
        dateUtil = mock(DateUtil::class.java)
        whenever(dateUtil.now()).thenReturn(now)
        tick = newTick(recordingPreferences(emptyMap()))
        armShell()
    }

    @After
    fun tearDown() {
        AimiEffectProbe.lines.remove()
        DetermineBasalaimiSMB2.resetLegacyPrebolusMemoryForTrace()
        CircadianMealProfileStore.resetForTests()
        MealAbsorptionMemory.reset()
        AimiUamHandler.updateRuntimeConfidence(null)
    }

    @Test
    fun engagedMealRiseRecordsTheRequestedTbr() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMIweight to 70.0,
                DoubleKey.OApsAIMIautodrivesmallPrebolus to 0.50,
                DoubleKey.OApsAIMIautodrivePrebolus to 1.50,
            ),
            bools = mapOf(BooleanKey.OApsAIMIautoDriveActive to true),
        )
        val engine = mock(AutodriveEngine::class.java, Answer { inv: InvocationOnMock ->
            if (inv.method.name == "tick") {
                AutoDriveCommand(
                    scheduledMicroBolus = 0.80,
                    temporaryBasalRate = 2.40,
                    isSafe = true,
                    reason = "meal-rise",
                )
            } else if (inv.method.returnType == Void.TYPE) {
                null
            } else {
                zeroFor(inv)
            }
        })
        tick = newTick(prefs, engine)
        armShell()
        setField(tick, "mealTime", true)
        setField(tick, "bg", 160.0)
        setField(tick, "delta", 3.0f)
        setField(tick, "shortAvgDelta", 2.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "hourOfDay", 12)
        setField(tick, "variableSensitivity", 50.0f)
        setField(tick, "targetBg", 100.0f)
        val profile = profileStub()
        val trace = capture {
            tick.runAutodriveV3MultiVariableBranch(
                ctx = tickContext(profile),
                profile = profile,
                rT = RT(runningDynamicIsf = false),
                bg = 160.0,
                combinedDelta = 3.0f,
                shortAvgDeltaAdj = 2.0f,
                hypoThresholdMgdl = 70.0,
                pkpdRuntime = null,
            )
        }.replace(Regex("ts=\\d+"), "ts=<clock>")
        assertEquals(MEAL_RISE_TRACE, trace)
    }

    @Test
    fun autodriveOnWithTheGateClosedEmitsNoDose() {
        val (trace, applied) = autodriveTrace(glucose = 110.0, delta = 0.2f, shortAvg = 0.1f, meal = false)
        assertFalse(applied)
        assertEquals(GATE_CLOSED_TRACE, trace)
    }

    @Test
    fun autodriveOnDuringHypoWithAWeakRiseEmitsNoDose() {
        // BG 54 and a flat delta miss every open threshold, so the real gater stays shut.
        // The disengaged path does not log the glucose, so the bytes match the closed gate.
        val (trace, applied) = autodriveTrace(glucose = 54.0, delta = 0.0f, shortAvg = -0.2f, meal = false)
        assertFalse(applied)
        assertEquals(GATE_CLOSED_TRACE, trace)
    }

    @Test
    fun autodriveOnHitsTheActivityBasalCeiling() {
        val (trace, _) = autodriveTrace(
            glucose = 160.0,
            delta = 3.0f,
            shortAvg = 2.0f,
            meal = true,
            activityLockout = true,
            activityFactor = 1.3,
        )
        assertEquals(ACTIVITY_CEILING_TRACE, trace)
    }

    @Test
    fun uamRecoveryRecordsPreferenceReadsAndSignals() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMIT3cAnticipationStrength to 0.40,
                DoubleKey.OApsAIMILastEstimatedCarbs to 5.0,
                DoubleKey.OApsAIMISmbTailDamping to 0.25,
            ),
            bools = mapOf(BooleanKey.OApsAIMIT3cBrittleMode to false),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "bg", 110.0)
        setField(tick, "targetBg", 90.0f)
        setField(tick, "hourOfDay", 12)
        setField(
            tick,
            "lastUamHypothesisState",
            UamHypothesisState(
                mealProb = 0.72,
                dominant = UamHypothesisId.MEAL,
                dominantConfidence = 0.72,
            ),
        )
        val glucose = mock(GlucoseStatusCalculatorAimi::class.java)
        whenever(glucose.getRecentGlucose()).thenReturn(listOf(110f, 100f, 80f, 65f))
        whenever(glucose.getAimiFeatures(true)).thenReturn(null)
        setField(tick, "glucoseStatusCalculatorAimi", glucose)
        AimiUamHandler.updateRuntimeConfidence(0.70)
        val profile = profileStub()
        val autosens = mock(AutosensResult::class.java)
        whenever(autosens.ratio).thenReturn(1.0)
        val trace = captureSignals {
            tick.buildRbtExtendedSignals(
                rT = RT(runningDynamicIsf = false),
                profile = profile,
                htr = HyperTrajectoryReleaseResult(
                    active = false,
                    tier = HyperSeverityTier.OFF,
                    severityWeight = 0.0,
                    smbFloorU = 0.0,
                    v3SmbBeforeU = 0.0,
                    v3SmbAfterU = 0.0,
                    absorptionOffsetMgdl = 0.0,
                    suppressTrajBasalShift = false,
                    hypoMinPredIgnored = false,
                    reason = "trace",
                ),
                v3SmbU = 0.0,
                autosens = autosens,
                glucoseStatus = GlucoseStatusAIMI(glucose = 110.0),
                mpcFeedForwardRa = null,
                cbfShieldDeltaU = null,
            )
        }
        assertEquals(UAM_TRACE, trace)
    }

    @Test
    fun brittleHypoRecordsZeroDemandAndTheActivationThreshold() {
        val trace = rbtTrace(brittle = true, bg = 50.0, delta = -2f, shortAvg = -1f, adaptive = 1.0, maxBasal = 3.0)
        assertEquals(BRITTLE_HYPO_TRACE, trace)
    }

    @Test
    fun brittleHyperClampsT3cDemandAtMaxBasal() {
        val recent = listOf(180f, 190f, 200f, 220f)
        val trace = rbtTrace(
            brittle = true,
            bg = 220.0,
            delta = 8f,
            shortAvg = 4f,
            adaptive = 1.0,
            currentBasal = 1.0,
            maxBasal = 1.2,
            eventual = 220.0,
            recentGlucose = recent,
        )
        val open = rbtTrace(
            brittle = true,
            bg = 220.0,
            delta = 8f,
            shortAvg = 4f,
            adaptive = 1.0,
            currentBasal = 1.0,
            maxBasal = 30.0,
            eventual = 220.0,
            recentGlucose = recent,
        )
        val openDemand = open.lineSequence().first { it.startsWith("SIGNAL t3cDemand=") }.substringAfter('=').toDouble()
        assertTrue("uncapped demand should clear the 1.20 ceiling, was $openDemand", openDemand > 1.20)
        assertEquals(BRITTLE_CEILING_TRACE, trace)
    }

    @Test
    fun brittleActiveRecordsThePiBasal() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMIT3cActivationThreshold to 140.0,
                DoubleKey.OApsAIMIT3cAggressiveness to 1.0,
                DoubleKey.autodriveMaxBasal to 3.0,
                DoubleKey.meal_modes_MaxBasal to 3.0,
            ),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "adaptiveMult", 1.0)
        setField(tick, "lastNgrBasalMultiplier", 1.0)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            tick.executeT3cBrittleMode(
                bg = 180.0,
                delta = 4.0f,
                shortAvgDelta = 2.0,
                longAvgDelta = 1.0,
                accel = 0.2,
                duraISFminutes = 0.0,
                duraISFaverage = 180.0,
                profile = profile,
                currenttemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
                iob = IobTotal(time = now, iob = 1.0),
                targetBg = 100.0,
                variableSensitivity = 50.0,
                maxIob = 10.0,
                eventualBg = 180.0,
                rT = rT,
            )
        }
        assertEquals(2.0, rT.rate ?: 0.0, 0.0)
        assertEquals(null, rT.units)
        assertEquals(30, rT.duration)
        assertEquals(BRITTLE_ACTIVE_TRACE, trace)
    }

    @Test
    fun mealAdvisorWithFreshCarbsRecordsTheTbrAndTheSmb() {
        val carbTime = System.currentTimeMillis().toDouble()
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMILastEstimatedCarbs to 40.0,
                DoubleKey.OApsAIMILastEstimatedCarbTime to carbTime,
                DoubleKey.meal_modes_MaxBasal to 2.0,
            ),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "adaptiveMult", 1.0)
        val profile = profileStub()
        whenever(profile.carb_ratio).thenReturn(10.0)
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            val returned = tick.runMealAdvisorDecisionOrReturn(
                ctx = tickContext(profile, glucose = 160.0),
                profile = profile,
                rT = rT,
                bg = 160.0,
                delta = 2.0f,
                iobData = IobTotal(time = now, iob = 1.0),
                modesCondition = true,
                isExplicitAdvisorRun = false,
                lastBolusTimeMs = 0L,
                autodriveDisplay = "off",
                hasRecentBolus45m = false,
            )
            assertEquals(rT, returned)
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
            .replace(Regex("DoubleKey\\.OApsAIMILastEstimatedCarbTime value=[0-9.]+"), "DoubleKey.OApsAIMILastEstimatedCarbTime value=<clock>")
        assertEquals(3.3, rT.units ?: 0.0, 0.0)
        assertEquals(MEAL_ADVISOR_TRACE, trace)
    }

    @Test
    fun finalizeCapCutsAProposedSmbDownToMaxSmb() {
        val prefs = recordingPreferences(emptyMap())
        setField(tick, "preferences", prefs)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 2.0f)
        setField(tick, "shortAvgDelta", 1.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "maxSMB", 0.5)
        setField(tick, "maxSMBHB", 0.5)
        setField(tick, "eventualBG", 180.0)
        setField(tick, "lastBolusAgeMinutes", 999.0)
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            invokeFinalize(rT, proposedUnits = 3.0)
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
        assertEquals(0.15, rT.units ?: -1.0, 1e-6)
        assertEquals(FINALIZE_CAP_TRACE, trace)
    }

    @Test
    fun recursiveBeliefResolveWithAFlatScenarioRecordsTheReads() {
        val prefs = recordingPreferences(
            doubles = emptyMap(),
            bools = mapOf(BooleanKey.OApsAIMIRecursiveBeliefShadow to true),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 2.0f)
        setField(tick, "shortAvgDelta", 1.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "maxSMB", 0.5)
        setField(tick, "maxSMBHB", 0.5)
        setField(tick, "eventualBG", 180.0)
        setField(tick, "hourOfDay", 12)
        val curves = AdvancedPredictionCurves(
            iob = listOf(180.0, 170.0),
            cob = listOf(180.0),
            uam = listOf(180.0),
            zt = listOf(180.0),
            hybrid = listOf(180.0, 170.0),
        )
        val floor = ScenarioProjectionCurve(
            kind = ScenarioProjectionKind.CLINICAL_FLOOR,
            pointsMgdl = listOf(180, 170),
            terminalMgdl = 170.0,
            pathMinMgdl = 170.0,
            pathMinHitFloor = false,
        )
        setField(
            tick,
            "lastScenarioProjection",
            ScenarioProjectionPair(
                clinicalFloor = floor,
                scenarioBest = floor.copy(kind = ScenarioProjectionKind.SCENARIO_BEST),
                contributors = emptyList(),
                cobPointsMgdl = listOf(180),
                ztPointsMgdl = listOf(180),
            ),
        )
        setField(tick, "lastAdvancedPredictionCurves", curves)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        var returned: Any? = null
        val trace = capture {
            returned = invokeRbtResolve(rT, profile)
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
        val snap = returned as RecursiveBeliefSnapshot
        val resolution = snap.resolutions
        assertEquals(0.0, resolution.smbDemandU, 0.0)
        assertEquals(1.0, resolution.tbrDemandFraction, 0.0)
        assertEquals(0.15, resolution.waitBias, 1e-9)
        assertEquals("NONE", resolution.releaseAuthority.toString())
        assertEquals("FULL", resolution.hypoGuardMode.toString())
        assertEquals(listOf("P2_SOFT", "HARMONIA_SMB_ACCEPT", "OFF_ASLEEP_LIVE"), resolution.reasonCodes)
        assertEquals(RBT_RESOLVE_TRACE, trace)
    }

    @Test
    fun recursiveBeliefResolveWithShadowAndActiveHtrDemandsSmb() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMIMaxSMB to 2.0,
                DoubleKey.OApsAIMIHighBGMaxSMB to 2.0,
            ),
            bools = mapOf(BooleanKey.OApsAIMIRecursiveBeliefShadow to true),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 2.0f)
        setField(tick, "shortAvgDelta", 1.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "maxSMBHB", 2.0)
        setField(tick, "eventualBG", 180.0)
        setField(tick, "hourOfDay", 12)
        setField(tick, "sleepTime", false)
        setField(
            tick,
            "physioAdapter",
            mock(AIMIInsulinDecisionAdapterMTR::class.java, Answer { inv: InvocationOnMock ->
                when (inv.method.name) {
                    "getLatestSnapshot" -> HealthContextSnapshot(
                        hrNow = 90,
                        rhrResting = 60,
                        stepsLast15m = 80,
                        stepsLast5m = 20,
                    )
                    "getEffectiveContext" -> PhysioContextMTR.NEUTRAL
                    else -> null
                }
            }),
        )
        val curves = AdvancedPredictionCurves(
            iob = listOf(180.0, 170.0),
            cob = listOf(180.0),
            uam = listOf(180.0),
            zt = listOf(180.0),
            hybrid = listOf(180.0, 170.0),
        )
        val floor = ScenarioProjectionCurve(
            kind = ScenarioProjectionKind.CLINICAL_FLOOR,
            pointsMgdl = listOf(180, 170),
            terminalMgdl = 170.0,
            pathMinMgdl = 170.0,
            pathMinHitFloor = false,
        )
        setField(
            tick,
            "lastScenarioProjection",
            ScenarioProjectionPair(
                clinicalFloor = floor,
                scenarioBest = floor.copy(kind = ScenarioProjectionKind.SCENARIO_BEST),
                contributors = emptyList(),
                cobPointsMgdl = listOf(180),
                ztPointsMgdl = listOf(180),
            ),
        )
        setField(tick, "lastAdvancedPredictionCurves", curves)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        val htr = HyperTrajectoryReleaseResult(
            active = true,
            tier = HyperSeverityTier.ESTABLISHED,
            severityWeight = 0.80,
            smbFloorU = 1.20,
            v3SmbBeforeU = 0.40,
            v3SmbAfterU = 1.20,
            absorptionOffsetMgdl = 12.0,
            suppressTrajBasalShift = false,
            hypoMinPredIgnored = false,
            reason = "established-rise",
        )
        var returned: Any? = null
        val trace = capture {
            returned = invokeRbtResolve(rT, profile, htr)
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
        val snap = returned as RecursiveBeliefSnapshot
        assertEquals(1.20, snap.resolutions.smbDemandU, 1e-6)
        assertFalse(snap.resolutions.reasonCodes.contains("OFF_ASLEEP_LIVE"))
        assertEquals(RBT_ACTIVE_HTR_TRACE, trace)
    }

    @Test
    fun t9WithPhysioAssistantOffKeepsNeutralMultipliers() {
        val prefs = recordingPreferences(emptyMap())
        setField(tick, "preferences", prefs)
        val plugin = mock(app.aaps.core.interfaces.plugin.ActivePlugin::class.java)
        whenever(plugin.activeBgSource).thenReturn(mock(app.aaps.core.interfaces.source.BgSource::class.java))
        setField(tick, "activePlugin", plugin)
        val cycle = getField(tick, "wCyclePreferences") as WCyclePreferences
        whenever(cycle.verneuil()).thenReturn(VerneuilStatus.NONE)
        whenever(cycle.thyroid()).thenReturn(ThyroidStatus.EUTHYROID)
        val profile = profileStub()
        val glucose = GlucoseStatusAIMI(glucose = 110.0, delta = 0.0, date = now)
        val ctx = tickContext(profile, 110.0)
        val rT = RT(runningDynamicIsf = false)
        var pumpAge = -1f
        val trace = capture {
            val returned = invokeT9(ctx, glucose, rT, iobTotal = 1.0)
            pumpAge = returned.javaClass.getDeclaredField("pumpAgeDays").apply { isAccessible = true }.get(returned) as Float
        }
        assertTrue(pumpAge >= 0f)
        assertEquals(T9_NEUTRAL_TRACE, trace)
    }

    @Test
    fun pkpdGuardAtAFlat110KeepsZeroSmb() {
        val prefs = recordingPreferences(emptyMap())
        setField(tick, "preferences", prefs)
        setField(tick, "bg", 110.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "shortAvgDelta", 0.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "predictedBg", 110.0f)
        setField(tick, "eventualBG", 110.0)
        setField(tick, "maxSMB", 0.50)
        setField(tick, "maxSMBHB", 1.20)
        setField(tick, "maxIob", 10.0)
        setField(tick, "iob", 1.0f)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        var returned: Any? = null
        val trace = capture {
            returned = invokePkpdGuard(tickContext(profile, 110.0), rT)
        }
        val stage = returned
        val smb = stage!!.javaClass.getDeclaredField("smbToGive").apply { isAccessible = true }.get(stage) as Float
        val interval = stage.javaClass.getDeclaredField("intervalsmb").apply { isAccessible = true }.get(stage) as Int
        assertEquals(0.0f, smb, 0.0f)
        assertEquals(4, interval)
        assertEquals("", rT.reason.toString())
        val draft = getField(tick, "lastSmbBindingTraceDraft")!!
        val stages = draft.javaClass.getDeclaredField("stages").apply { isAccessible = true }.get(draft) as List<*>
        val names = stages.map { stage ->
            stage!!.javaClass.getDeclaredField("name").apply { isAccessible = true }.get(stage) as String
        }
        assertEquals(listOf("PKPD_GUARD", "LEGACY_RED_CARPET_MAX_SMB_IOB"), names)
        assertEquals(PKPD_GUARD_FLAT_TRACE, trace)
    }

    @Test
    fun tickClockRiseUsesHighCeilingAndCachedTirAndCarbs() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.ApsSmbMaxIob to 10.0,
                DoubleKey.OApsAIMIMaxSMB to 0.50,
                DoubleKey.OApsAIMIHighBGMaxSMB to 1.20,
            ),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "cob", 0.0f)
        setField(tick, "now", now)
        holdRefresh("tirWarmupRefreshInFlight")
        holdRefresh("carbContextRefreshInFlight")
        setAtomic(
            "tirWarmupSnapshotRef",
            privateData(
                "TirWarmupSnapshot",
                listOf(18.0, 72.0, 4.0, 70.0, 26.0, 3.0, 22.0, 5.0, 11.0, 30.0, 68.0, 6.0, 26.0, 14.0),
            ),
        )
        setAtomic(
            "carbContextSnapshotRef",
            privateData(
                "CarbContextSnapshot",
                listOf(now - 8L * 60_000L, 8, 12.0f, 25.0f, emptyList<Any>()),
            ),
        )
        val profile = profileStub()
        val ctx = tickContext(profile, 180.0)
        ctx.mealData.slopeFromMinDeviation = 2.0
        ctx.mealData.lastCarbTime = now - 8L * 60_000L
        val glucose = GlucoseStatusAIMI(
            glucose = 180.0,
            delta = 4.0,
            shortAvgDelta = 3.0,
            longAvgDelta = 1.0,
            date = now,
        )
        val rT = RT(runningDynamicIsf = false)
        var returned: Any? = null
        val trace = capture {
            returned = invokeTickClock(ctx, glucose, rT, combinedDelta = 3.0f)
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
        val stage = returned!!
        fun field(name: String): Any? = stage.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(stage)
        assertEquals(1.20, getField(tick, "maxSMB") as Double, 1e-6)
        assertEquals(72.0, field("tir1DAYIR") as Double, 1e-6)
        assertEquals(22.0, field("lastHourTIRAbove") as Double, 1e-6)
        assertEquals(8, getField(tick, "lastCarbAgeMin") as Int)
        assertEquals(12.0f, getField(tick, "futureCarbs") as Float, 0.0f)
        assertEquals(25.0f, getField(tick, "cob") as Float, 0.0f)
        assertEquals(TICK_CLOCK_RISE_TRACE, trace)
    }

    @Test
    fun t9WithPhysioAndPkpdOnScalesTheCeiling() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMIweight to 70.0,
                DoubleKey.OApsAIMIPkpdStateDiaH to 5.0,
                DoubleKey.OApsAIMIPkpdStatePeakMin to 75.0,
                DoubleKey.OApsAIMIPkpdBoundsDiaMinH to 3.0,
                DoubleKey.OApsAIMIPkpdBoundsDiaMaxH to 8.0,
                DoubleKey.OApsAIMIPkpdBoundsPeakMinMin to 30.0,
                DoubleKey.OApsAIMIPkpdBoundsPeakMinMax to 180.0,
                DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH to 0.50,
                DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin to 10.0,
                DoubleKey.OApsAIMIIsfFusionMinFactor to 0.70,
                DoubleKey.OApsAIMIIsfFusionMaxFactor to 1.30,
                DoubleKey.OApsAIMIIsfFusionMaxChangePerTick to 0.05,
                DoubleKey.OApsAIMISmbTailDamping to 0.50,
                DoubleKey.OApsAIMISmbTailThreshold to 0.25,
                DoubleKey.OApsAIMISmbExerciseDamping to 0.60,
                DoubleKey.OApsAIMISmbLateFatDamping to 0.70,
                DoubleKey.OApsAIMIPkpdAnchorDiaH to 5.0,
                DoubleKey.OApsAIMIPkpdAnchorPeakMin to 75.0,
            ),
            bools = mapOf(
                BooleanKey.AimiPhysioAssistantEnable to true,
                BooleanKey.OApsAIMIPkpdEnabled to true,
            ),
        )
        tick = newTick(prefs, learnedState = PkPdLearnedState())
        armShell()
        val behavior = getField(tick, "behaviorProfileSource") as AimiBehaviorProfileSource
        whenever(behavior.read(any())).thenReturn(
            AimiBehaviorRuntimeProfile(
                protectionLevel = 1,
                mealCaptureLevel = 1,
                stabilityLevel = 1,
                physioLevel = 1,
                autonomyMode = AimiAutonomyMode.Observation,
            ),
        )
        setField(tick, "maxSMB", 1.0)
        setField(tick, "maxSMBHB", 1.20)
        setField(tick, "cachedPumpAgeDays", 3.5f)
        holdRefresh("pumpAgeRefreshInFlight")
        val plugin = mock(app.aaps.core.interfaces.plugin.ActivePlugin::class.java)
        whenever(plugin.activeBgSource).thenReturn(mock(app.aaps.core.interfaces.source.BgSource::class.java))
        setField(tick, "activePlugin", plugin)
        val cycle = getField(tick, "wCyclePreferences") as WCyclePreferences
        whenever(cycle.verneuil()).thenReturn(VerneuilStatus.NONE)
        whenever(cycle.thyroid()).thenReturn(ThyroidStatus.EUTHYROID)
        val physio = mock(AIMIInsulinDecisionAdapterMTR::class.java)
        whenever(physio.getMultipliers(any(), any(), anyOrNull(), any(), any())).thenReturn(
            PhysioMultipliersMTR(isfFactor = 1.10, basalFactor = 1.05, smbFactor = 1.08, confidence = 0.80),
        )
        whenever(physio.getDetailedLogString()).thenReturn("physio-detail")
        setField(tick, "physioAdapter", physio)
        val profile = profileStub()
        val glucose = GlucoseStatusAIMI(glucose = 160.0, delta = 4.0, date = now)
        val ctx = tickContext(profile, 160.0)
        val rT = RT(runningDynamicIsf = false)
        var pumpAge = -1f
        var multipliersNeutral = true
        val trace = capture {
            val returned = invokeT9(ctx, glucose, rT, iobTotal = 1.5)
            pumpAge = returned.javaClass.getDeclaredField("pumpAgeDays").apply { isAccessible = true }.get(returned) as Float
            val multipliers = returned.javaClass.getDeclaredField("physioMultipliers").apply { isAccessible = true }.get(returned) as PhysioMultipliersMTR
            multipliersNeutral = multipliers.isNeutral()
        }
        assertEquals(3.5f, pumpAge, 0.0f)
        assertFalse(multipliersNeutral)
        assertEquals(1.08, getField(tick, "maxSMB") as Double, 1e-6)
        assertTrue(getField(tick, "cachedPkpdRuntime") != null)
        assertEquals(T9_ACTIVE_TRACE, trace)
    }

    @Test
    fun pkpdGuardCutsThenEndoDampensAndTheRedCarpetRestores() {
        val prefs = recordingPreferences(emptyMap())
        setField(tick, "preferences", prefs)
        setField(tick, "bg", 160.0)
        setField(tick, "delta", 2.0f)
        setField(tick, "shortAvgDelta", 2.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "predictedBg", 170.0f)
        setField(tick, "eventualBG", 170.0)
        setField(tick, "maxSMB", 5.0)
        setField(tick, "maxSMBHB", 5.0)
        setField(tick, "maxIob", 10.0)
        setField(tick, "iob", 1.0f)
        setField(tick, "endoSmbMult", 0.50)
        setField(tick, "pkpdAbsorptionGuardAppliedThisTick", false)
        val profile = profileStub()
        val ctx = tickContext(profile, 160.0)
        ctx.mealData.slopeFromMinDeviation = 2.0
        val rT = RT(runningDynamicIsf = false)
        var returned: Any? = null
        val trace = capture {
            returned = invokePkpdGuardActive(ctx, rT, preOnsetRuntime())
        }
        val stage = returned!!
        val smb = stage.javaClass.getDeclaredField("smbToGive").apply { isAccessible = true }.get(stage) as Float
        assertEquals(2.0f, smb, 0.0f)
        assertTrue(trace.contains("SMB_GUARDED"))
        assertTrue(trace.contains("SMB_ENDO_DAMPEN"))
        assertTrue(trace.contains("RED CARPET"))
        assertTrue(trace.contains("MEAL_FORCE_EXECUTED"))
        assertEquals(PKPD_GUARD_ACTIVE_TRACE, trace)
    }

    @Test
    fun decisionContextRiseOverridesAFlatSensorAndArmsTheAuditor() {
        val prefs = recordingPreferences(emptyMap())
        setField(tick, "preferences", prefs)
        val storage = mock(AimiStorageHelper::class.java)
        whenever(storage.getHealthReport()).thenReturn("ok")
        setField(tick, "storageHelper", storage)
        val learner = getField(tick, "basalLearner") as app.aaps.plugins.aps.openAPSAIMI.learning.BasalLearner
        whenever(learner.getMultiplier()).thenReturn(1.0)
        setField(tick, "emergencySos", mock(AimiEmergencySos::class.java))
        val provider = mock(HormonitorStudyExporterProvider::class.java)
        whenever(provider.exporter()).thenReturn(null)
        setField(tick, "hormonitorStudyExporterProvider", provider)
        val profile = profileStub()
        whenever(profile.variable_sens).thenReturn(40.0)
        val ctx = tickContext(profile, 160.0).copy(
            glucoseStatus = GlucoseStatusAIMI(glucose = 160.0, delta = 6.0, date = now),
            flatBGsDetected = true,
            extraDebug = "extra-line",
            currentTime = now,
        )
        var returned: Any? = null
        val trace = capture {
            returned = invokeDecisionContext(ctx)
        }
        val stage = returned!!
        fun field(name: String): Any? = stage.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(stage)
        val decision = field("decisionCtx") as AimiDecisionContext
        val rT = field("rT") as RT
        assertEquals("BG_Rise_Fast", decision.trigger)
        assertEquals("evt_$now", decision.event_id)
        assertEquals(false, field("flatBGsDetected") as Boolean)
        assertTrue(rT.reason.toString().contains("extra-line"))
        assertTrue(rT.learnersInfo.toString().contains("100%"))
        assertEquals(DECISION_CONTEXT_RISE_TRACE, trace)
    }

    @Test
    fun rbtRefineAfterDoseSnapshotCutsTheLiftWithSurveillance() {
        val prefs = recordingPreferences(
            emptyMap(),
            bools = mapOf(BooleanKey.OApsAIMIIobSurveillanceGuard to true),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 1.0f)
        setField(tick, "shortAvgDelta", 1.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "iob", 4.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "rbtResolvedThisTick", true)
        val baseline = htr(before = 0.40, after = 0.40, reason = "baseline")
        val previous = htr(before = 0.40, after = 1.20, reason = "already-lifted")
        setField(
            tick,
            "lastRbtLiveCommitResult",
            RbtLiveCommitResult(baselineHtr = baseline, effectiveHtr = previous, rbtAuthority = true),
        )
        setField(
            tick,
            "lastRecursiveAuthorityGateDecision",
            RecursiveBeliefAuthorityGate.Decision(
                requestedAuthority = ReleaseAuthority.HARD,
                maxAllowedAuthority = ReleaseAuthority.HARD,
                effectiveAuthority = ReleaseAuthority.HARD,
                readinessScore = 0.80,
                liftBlend = 1.0,
                reasonCodes = listOf("READY"),
            ),
        )
        setField(
            tick,
            "lastDoseTerminalSnapshot",
            DoseTerminalSnapshot(
                eventualMgdl = 160.0,
                minPredMgdl = 150.0,
                source = "test",
                authorityApplied = false,
                clampReconciled = false,
                clampReason = null,
                predBGsRemapped = false,
            ),
        )
        setField(
            tick,
            "lastRecursiveBeliefSnapshot",
            RecursiveBeliefSnapshot(
                scales = emptyList(),
                tensions = emptyList(),
                paradoxes = emptyList(),
                resolutions = DoseChannelResolution(
                    smbDemandU = 1.50,
                    tbrDemandFraction = 1.0,
                    waitBias = 0.0,
                    dominantScaleMinutes = 30,
                    releaseAuthority = ReleaseAuthority.HARD,
                    hypoGuardMode = HypoGuardMode.FULL,
                    autodriveModeHint = AutodriveModeHint.V3,
                    mealChannel = MealChannelHint.NORMAL,
                    suppressTrajBasalShift = false,
                    hypoMinPredIgnored = false,
                    reasonCodes = listOf("DEMAND"),
                ),
                mr7Trace = emptyList(),
            ),
        )
        val rT = RT(runningDynamicIsf = false)
        val trace = capture { invokeRbtRefine(rT) }
        val stacking = getField(tick, "lastInsulinStackingEvaluation") as InsulinStackingStance.Evaluation
        val commit = getField(tick, "lastRbtLiveCommitResult") as RbtLiveCommitResult
        assertEquals(InsulinStackingStance.Kind.SURVEILLANCE_IOB, stacking.kind)
        assertEquals(0.38, stacking.smbAbsoluteCapU, 1e-9)
        assertEquals(0.38, commit.effectiveHtr.v3SmbAfterU, 1e-9)
        assertEquals(RBT_REFINE_ACTIVE_TRACE, trace)
    }

    @Test
    fun basalFirstKeepsAReductionWhenTheGuardIsOn() {
        val prefs = recordingPreferences(
            emptyMap(),
            bools = mapOf(BooleanKey.OApsAIMIBasalChannelSafetyGuards to true),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "adaptiveMult", 0.70)
        var multiplier = 0.0
        val trace = capture { multiplier = invokeBasalFirst() }
        assertEquals(0.70, multiplier, 1e-9)
        assertEquals(BASAL_FIRST_REDUCTION_TRACE, trace)
    }

    @Test
    fun basalTddDoublesOnHighTirThenBoostsAndCutsIsf() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMIweight to 70.0,
                DoubleKey.OApsAIMICHO to 15.0,
            ),
            bools = mapOf(BooleanKey.OApsAIMIpregnancy to true),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "adaptiveMult", 0.80)
        val engine = mock(BasalDecisionEngine::class.java, Answer { inv: InvocationOnMock ->
            if (inv.method.name == "smoothBasalRate") inv.arguments[2] else null
        })
        setField(tick, "basalDecisionEngine", engine)
        val profile = profileStub()
        val glucose = GlucoseStatusAIMI(glucose = 160.0, delta = 4.0, shortAvgDelta = 1.0, date = now)
        val trace = capture {
            invokeBasalPai(
                glucose = glucose,
                profile = profile,
            )
        }
        val ci = (450.0 / 35.0).toFloat()
        val expectedLimit = (15.0 / ci).toFloat() * 0.80f
        assertEquals(1.2f, getField(tick, "basalaimi") as Float, 0f)
        assertEquals(30f, getField(tick, "variableSensitivity") as Float, 0.001f)
        assertEquals(expectedLimit, getField(tick, "aimilimit") as Float, 0f)
        assertEquals(BASAL_TDD_PAI_TRACE, trace)
    }

    @Test
    fun mealFirstThirtyMinutesForcesATempBasal() {
        setField(tick, "mealTime", true)
        setField(tick, "mealruntime", 10L)
        setField(tick, "adaptiveMult", 1.0)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        var kind = ""
        val trace = capture { kind = invokeMealFirst(profile, rT).javaClass.simpleName }
        assertEquals("EarlyTempBasal", kind)
        assertEquals(MEAL_FIRST_30_TRACE, trace)
    }

    @Test
    fun activityCapThenMealIobDampingCutsInsulinReq() {
        setField(tick, "activityProtectionMode", true)
        setField(tick, "activityStateIntense", false)
        setField(tick, "maxSMB", 1.0)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        val ctx = tickContext(profile).copy(microBolusAllowed = false)
        val trace = capture {
            invokeInsulinReq(
                ctx = ctx,
                rT = rT,
                smbToGive = 2f,
                allowMealHighIob = true,
                mealHighIobDamping = 0.50,
            )
        }
        assertEquals(0.25, rT.insulinReq!!, 1e-9)
        assertEquals(INSULIN_REQ_ACTIVITY_TRACE, trace)
    }

    @Test
    fun maxIobWithoutMealRelaxSetsATempBasal() {
        setField(tick, "adaptiveMult", 1.0)
        setField(tick, "comparator", mock(AimiSmbComparison::class.java))
        val provider = mock(HormonitorStudyExporterProvider::class.java)
        whenever(provider.exporter()).thenReturn(null)
        setField(tick, "hormonitorStudyExporterProvider", provider)
        val profile = profileStub()
        val ctx = tickContext(profile, 160.0).copy(
            glucoseStatus = GlucoseStatusAIMI(glucose = 160.0, delta = 2.0, shortAvgDelta = 1.0, date = now),
            currentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
        )
        val rT = RT(runningDynamicIsf = false)
        var kind = ""
        val trace = capture {
            kind = invokeMaxIobGate(profile, ctx, rT).javaClass.simpleName
        }.replace(Regex("ts=\\d+"), "ts=<clock>")
        assertEquals("ReturnTempBasal", kind)
        assertEquals(MAX_IOB_TBR_TRACE, trace)
    }

    @Test
    fun sportMealGuardScalesTheSmb() {
        setField(tick, "sportTime", true)
        setField(tick, "mealTime", true)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 4.0f)
        setField(tick, "shortAvgDelta", 2.0f)
        setField(tick, "longAvgDelta", 1.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "predictedBg", 180.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "maxIob", 10.0)
        setField(tick, "eventualBG", 180.0)
        var smb = -1f
        val trace = capture { smb = invokeSafetySmb() }
        assertEquals(1.54f, smb, 0.001f)
        assertEquals(false, getField(tick, "criticalSafetyZeroedThisTick"))
        assertEquals(SPORT_MEAL_SMB_TRACE, trace)
    }

    @Test
    fun lowPredictionRequestsAQuarterBasal() {
        // A prior test in this class can leave the meal-absorption hold set. The locked
        // quarter-basal trace is the clean hold: TBR 0.25 U/h. Reset only here.
        MealAbsorptionPhaseHysteresis.reset()
        armPump()
        setField(tick, "targetBg", 100.0f)
        val profile = profileStub()
        whenever(profile.carb_ratio).thenReturn(10.0)
        val iob = IobTotal(time = aimiWallClockMs(), iob = 2.0, activity = 0.20)
        val ctx = tickContext(profile, 100.0).copy(
            iobDataArray = arrayOf(iob),
            glucoseStatus = GlucoseStatusAIMI(glucose = 100.0, delta = 0.0, date = now),
        )
        val rT = RT(runningDynamicIsf = false)
        val glucose = GlucoseStatusAIMI(glucose = 100.0, delta = 0.0, date = now)
        var best = Double.NaN
        var floorT = Double.NaN
        var threshold = Double.NaN
        val trace = capture {
            val prep = invokeNamed(
                "runAdvancedPredictionsAndPredPipePrep",
                listOf(
                    ctx, profile, rT, 100.0, 0.0f, 50.0, 100.0f, glucose, 1.0, false,
                    PhysioMultipliersMTR.NEUTRAL, iob, 0, 72, 60, 0.0f,
                ),
            )
            val scenario = prep!!.javaClass.getDeclaredField("scenario").apply { isAccessible = true }.get(prep) as ScenarioProjectionPair
            best = scenario.scenarioBest.terminalMgdl
            floorT = scenario.clinicalFloor.terminalMgdl
            threshold = prep.javaClass.getDeclaredField("threshold").apply { isAccessible = true }.get(prep) as Double
            invokeSafetyHalt(profile, ctx, rT, glucose, scenario)
        }.replace(Regex("ts=\\d+"), "ts=<clock>")
        assertEquals(43.78040816326531, best, 1e-6)
        assertEquals(39.0, floorT, 1e-9)
        assertEquals(70.0, threshold, 1e-9)
        assertEquals(LOW_PREDICTION_TBR_TRACE, trace)
    }

    @Test
    fun harmoniaRampsATwoUnitRequest() {
        val profile = profileStub()
        whenever(profile.min_bg).thenReturn(90.0)
        whenever(profile.max_bg).thenReturn(140.0)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 2.0f)
        setField(tick, "predictedBg", 180.0f)
        setField(tick, "eventualBG", 180.0)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "maxSMB", 1.0)
        val decision = HarmoniaDecision(
            timestampMs = now,
            branch = "STABLE",
            action = HarmoniaAction.BASAL_FIRST,
            eligible = true,
            targetBasalUph = 2.0,
            targetSmbU = 0.0,
            basalFactor = 1.0,
            smbFactor = 1.0,
            environment = HarmoniaDecisionEnvironment(
                currentBgMgdl = 180.0,
                deltaMgdl5m = 2.0,
                iobU = 1.0,
                cobG = 0.0,
                currentBasalUph = 1.0,
                maxBasalUph = 3.0,
                maxSmbU = 1.0,
                maxIobU = 10.0,
            ),
            capsApplied = emptyList(),
            blockers = emptyList(),
            rationale = emptyList(),
            compactSummary = "ready",
            decisionBasis = HarmoniaDecisionBasis(
                trunkState = GlobalPhysiologicalState.STABLE,
                trunkConfidence = 0.8,
                trunkRisk = PhysiologicalRiskLevel.LOW,
                primaryReason = "test",
                contributingBranches = emptyList(),
                actionCoherentWithTrunk = true,
            ),
        )
        setField(tick, "lastHarmoniaDecision", decision)
        val ctx = tickContext(profile, 180.0)
        val rT = RT(runningDynamicIsf = false)
        val bundle = privateData(
            "AimiPostBasalEngineFinalizeBundle",
            listOf(
                ctx,
                profile,
                profile,
                rT,
                BasalDecisionEngine.Decision(rate = 1.0, duration = 30, overrideSafety = false),
                false,
                null,
                35.0,
                4,
            ),
        )
        var rate = Double.NaN
        val trace = capture {
            val plan = invokeNamed("planHarmoniaProductionBranch", listOf(bundle))
            rate = if (plan == null) Double.NaN else plan.javaClass.getDeclaredField("rateUph").apply { isAccessible = true }.get(plan) as Double
        }
        assertEquals(1.3, rate, 0.001)
        assertEquals(HARMONIA_RAMP_TRACE, trace)
    }

    @Test
    fun autosensHalfDoublesScheduledBasalAndRestingHeartRateStrengthensIsf() {
        val prefs = recordingPreferences(
            doubles = emptyMap(),
            bools = mapOf(BooleanKey.OApsAIMIEnableStepsFromWatch to true),
        )
        setField(tick, "preferences", prefs)
        val profile = profileStub()
        whenever(profile.min_bg).thenReturn(80.0)
        whenever(profile.max_bg).thenReturn(120.0)
        whenever(profile.autosens_max).thenReturn(1.5)
        whenever(profile.half_basal_exercise_target).thenReturn(160)
        whenever(profile.carb_ratio).thenReturn(10.0)
        whenever(profile.adv_target_adjustments).thenReturn(false)
        armPump()
        val autosens = mock(AutosensResult::class.java)
        whenever(autosens.ratio).thenReturn(0.5)
        val ctx = tickContext(profile, 140.0).copy(autosensData = autosens)
        val rT = RT(runningDynamicIsf = false)
        val glucose = GlucoseStatusAIMI(glucose = 140.0, delta = 1.0, shortAvgDelta = 1.0, longAvgDelta = 1.0, date = now, combinedDelta = 1.0)
        setField(tick, "hourOfDay", 15)
        setField(tick, "iob", 1.0f)
        setField(tick, "delta", 2.0f)
        setField(tick, "variableSensitivity", 50.0f)
        setField(tick, "maxIob", 10.0)
        holdRefresh("stepsRefreshInFlight")
        holdRefresh("heartRatesRefreshInFlight")
        setAtomic("stepsSnapshotRef", emptyList<Any>())
        val wall = aimiWallClockMs()
        setAtomic(
            "heartRatesSnapshotRef",
            listOf(
                HR(duration = 60_000L, timestamp = wall - 40 * 60_000L, beatsPerMinute = 80.0, device = "watch"),
                HR(duration = 60_000L, timestamp = wall - 30 * 60_000L, beatsPerMinute = 80.0, device = "watch"),
                HR(duration = 60_000L, timestamp = wall - 20 * 60_000L, beatsPerMinute = 80.0, device = "watch"),
                HR(duration = 60_000L, timestamp = wall - 2 * 60_000L, beatsPerMinute = 110.0, device = "watch"),
            ),
        )
        var basal = Double.NaN
        val trace = capture {
            val schedule = invokeNamed(
                "buildGlobalAimiBasalScheduleBootstrap",
                listOf(ctx, profile, rT, glucose, null, 140.0, 140.0f, 1.0f, 1.0, now, now, false, false, 0, 0),
            )
            basal = schedule!!.javaClass.getDeclaredField("basal").apply { isAccessible = true }.get(schedule) as Double
            invokeNamed(
                "runPostBasalBootstrapIobTickStepsAndHeartRate",
                listOf(glucose, profile, IobTotal(time = now, iob = 1.0), 160.0),
            )
        }
        val isf = getField(tick, "variableSensitivity") as Float
        assertEquals(2.0, basal, 0.001)
        assertEquals(45.0f, isf, 0.001f)
        assertEquals(AUTOSENS_HR_TRACE, trace)
    }

    @Test
    fun highGlucoseLowersTheWorkingTarget() {
        val profile = profileStub()
        whenever(profile.adv_target_adjustments).thenReturn(true)
        whenever(profile.min_bg).thenReturn(90.0)
        whenever(profile.max_bg).thenReturn(120.0)
        whenever(profile.carb_ratio).thenReturn(10.0)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 2.0f)
        setField(tick, "cob", 0.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "iob", 1.0f)
        val ctx = tickContext(profile, 180.0)
        val rT = RT(runningDynamicIsf = false)
        val glucose = GlucoseStatusAIMI(
            glucose = 180.0,
            delta = 2.0,
            shortAvgDelta = 1.0,
            longAvgDelta = 1.0,
            date = now,
            combinedDelta = 2.0,
        )
        var target = Double.NaN
        val trace = capture {
            val stage = invokeNamed(
                "runPkpdPredictionsBgiDeviationAndNoisyTargetsStage",
                listOf(
                    ctx, profile, rT, glucose, null,
                    IobTotal(time = now, iob = 1.0),
                    180.0, 2.0f, 50.0, 1.0, 1.0, 90.0, 100.0, 120.0,
                ),
            )
            target = stage!!.javaClass.getDeclaredField("targetBg").apply { isAccessible = true }.get(stage) as Double
        }
        val correctionU = (180.0 - target) / 50.0
        assertEquals(80.0, target, 0.001)
        assertEquals(2.0, correctionU, 0.001)
        assertEquals(PKPD_TARGET_TRACE, trace)
    }

    @Test
    fun fragileGlucoseDisablesSmbAfterPkpdRuntime() {
        val integration = mock(app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration::class.java, Answer { inv ->
            if (inv.method.name == "computeRuntime") preOnsetRuntime() else null
        })
        setField(tick, "pkpdIntegration", integration)
        val provider = mock(HormonitorStudyExporterProvider::class.java)
        whenever(provider.exporter()).thenReturn(null)
        setField(tick, "hormonitorStudyExporterProvider", provider)
        setField(tick, "bg", 100.0)
        setField(tick, "delta", -1.0f)
        setField(tick, "shortAvgDelta", -1.0f)
        setField(tick, "longAvgDelta", -1.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "cob", 0.0f)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "predictedBg", 100.0f)
        holdRefresh("bolusRefreshInFlight")
        val profile = profileStub()
        val autosens = mock(AutosensResult::class.java)
        whenever(autosens.ratio).thenReturn(1.0)
        val ctx = tickContext(profile, 100.0).copy(autosensData = autosens)
        val glucose = GlucoseStatusAIMI(glucose = 100.0, delta = -1.0, shortAvgDelta = -1.0, longAvgDelta = -1.0, date = now, combinedDelta = -1.0)
        val trace = capture {
            invokeNamed(
                "runSignalPreparationPkpdRuntimePhase",
                listOf(ctx, profile, RT(runningDynamicIsf = false), glucose, -1.0f, 30.0, false, false, null),
            )
        }
        val maxSmb = getField(tick, "maxSMB") as Double
        assertEquals(0.0, maxSmb, 0.001)
        assertEquals(SIGNAL_PREP_TRACE, trace)
    }

    @Test
    fun pkpdRuntimeFailureKeepsTheSmbCeiling() {
        val integration = mock(app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdIntegration::class.java, Answer { inv ->
            if (inv.method.name == "computeRuntime") throw RuntimeException("boom") else null
        })
        setField(tick, "pkpdIntegration", integration)
        val provider = mock(HormonitorStudyExporterProvider::class.java)
        whenever(provider.exporter()).thenReturn(null)
        setField(tick, "hormonitorStudyExporterProvider", provider)
        setField(tick, "bg", 100.0)
        setField(tick, "delta", -1.0f)
        setField(tick, "shortAvgDelta", -1.0f)
        setField(tick, "longAvgDelta", -1.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "cob", 0.0f)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "predictedBg", 100.0f)
        holdRefresh("bolusRefreshInFlight")
        val profile = profileStub()
        val autosens = mock(AutosensResult::class.java)
        whenever(autosens.ratio).thenReturn(1.0)
        val ctx = tickContext(profile, 100.0).copy(autosensData = autosens)
        val glucose = GlucoseStatusAIMI(glucose = 100.0, delta = -1.0, shortAvgDelta = -1.0, longAvgDelta = -1.0, date = now, combinedDelta = -1.0)
        val trace = capture {
            invokeNamed(
                "runSignalPreparationPkpdRuntimePhase",
                listOf(ctx, profile, RT(runningDynamicIsf = false), glucose, -1.0f, 30.0, false, false, null),
            )
        }
        val maxSmb = getField(tick, "maxSMB") as Double
        assertEquals(2.0, maxSmb, 0.001)
        assertTrue(trace.contains("PKPD runtime failed (RuntimeException): boom — value null"))
        assertFalse(trace.contains("BASAL-FIRST"))
    }

    @Test
    fun trajectoryDampingHalvesTheSmbCeiling() {
        val prefs = recordingPreferences(doubles = emptyMap(), bools = mapOf(BooleanKey.OApsAIMITrajectoryGuardEnabled to true))
        setField(tick, "preferences", prefs)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "maxSMBHB", 2.0)
        holdRefresh("effectiveProfileRefreshInFlight")
        holdRefresh("trajectoryHistoryRefreshInFlight")
        val analysis = TrajectoryAnalysis(
            classification = TrajectoryType.STABLE_ORBIT,
            metrics = TrajectoryMetrics(
                curvature = 0.0,
                convergenceVelocity = 0.0,
                coherence = 0.0,
                energyBalance = 0.0,
                openness = 0.0,
            ),
            modulation = TrajectoryModulation(
                smbDamping = 0.50,
                intervalStretch = 1.0,
                basalPreference = 0.5,
                safetyMarginExpand = 1.0,
                relevanceScore = 1.0,
                reason = "damped",
            ),
            warnings = emptyList(),
            stableOrbitDistance = 0.0,
            predictedConvergenceTime = null,
        )
        val guard = mock(TrajectoryGuard::class.java)
        whenever(guard.analyzeTrajectory(any(), any())).thenReturn(analysis)
        setField(tick, "trajectoryGuard", guard)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            invokeNamed(
                "applyTrajectoryAnalysis",
                listOf(
                    now, 120.0, 0.0, 0.0, 0.0, 1.0f, InsulinActionState.default(),
                    30.0, 0.0f, 100.0, profile, rT, mock(UiInteraction::class.java), 1.0,
                ),
            )
        }
        val maxSmb = getField(tick, "maxSMB") as Double
        assertEquals(trace, 1.0, maxSmb, 0.001)
        assertEquals(TRAJECTORY_SMB_TRACE, trace)
    }

    @Test
    fun trajectoryNotificationFailureStaysVisible() {
        val prefs = recordingPreferences(doubles = emptyMap(), bools = mapOf(BooleanKey.OApsAIMITrajectoryGuardEnabled to true))
        setField(tick, "preferences", prefs)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "maxSMBHB", 2.0)
        holdRefresh("effectiveProfileRefreshInFlight")
        holdRefresh("trajectoryHistoryRefreshInFlight")
        val notifications = mock(NotificationManager::class.java, Answer { inv ->
            if (inv.method.name == "post") throw RuntimeException("boom") else null
        })
        setField(tick, "notificationManager", notifications)
        val analysis = TrajectoryAnalysis(
            classification = TrajectoryType.STABLE_ORBIT,
            metrics = TrajectoryMetrics(0.0, 0.0, 0.0, 0.0, 0.0),
            modulation = TrajectoryModulation(0.50, 1.0, 0.5, 1.0, 1.0, "damped"),
            warnings = listOf(
                TrajectoryWarning(
                    severity = WarningSeverity.CRITICAL,
                    type = "critical",
                    message = "spiral",
                    suggestedAction = "look",
                ),
            ),
            stableOrbitDistance = 0.0,
            predictedConvergenceTime = null,
        )
        val guard = mock(TrajectoryGuard::class.java)
        whenever(guard.analyzeTrajectory(any(), any())).thenReturn(analysis)
        setField(tick, "trajectoryGuard", guard)
        val profile = profileStub()
        val trace = capture {
            invokeNamed(
                "applyTrajectoryAnalysis",
                listOf(
                    now, 120.0, 0.0, 0.0, 0.0, 1.0f, InsulinActionState.default(),
                    30.0, 0.0f, 100.0, profile, RT(runningDynamicIsf = false), mock(UiInteraction::class.java), 1.0,
                ),
            )
        }
        assertEquals(1.0, getField(tick, "maxSMB") as Double, 0.001)
        assertTrue(trace.contains("Trajectory notification failed (RuntimeException): boom — post skipped"))
    }

    @Test
    fun mealAdvisorOneShotRaisesTheSmbCeiling() {
        val prefs = recordingPreferences(
            doubles = emptyMap(),
            bools = mapOf(BooleanKey.OApsAIMIMealAdvisorTrigger to true),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "maxSMB", 0.5)
        setField(tick, "maxSMBHB", 0.5)
        setField(tick, "predictedSMB", 0.0f)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false, insulinReq = 1.5)
        val glucose = GlucoseStatusAIMI(glucose = 180.0, delta = 2.0, shortAvgDelta = 1.0, longAvgDelta = 1.0, date = now, combinedDelta = 2.0)
        val trace = capture {
            invokeNamed(
                "runSmbDecisionLogAdvisorOneShotAndExecuteInstruction",
                listOf(
                    tickContext(profile, 180.0),
                    profile,
                    rT,
                    glucose,
                    180.0,
                    2.0f,
                    1.0f,
                    1.0f,
                    180.0f,
                    180.0,
                    50.0,
                    75.0,
                    50.0f,
                    100.0,
                    1.0f,
                    1.0,
                    false,
                    12,
                    false, false, false, false, false, false, false,
                    false,
                    0L,
                    70.0,
                    30,
                    5,
                    PumpCaps(0.05, 0.05, 30, 3.0, 3.0),
                    false,
                    0.0f,
                    null,
                    1.0f,
                    0.0f,
                    1.0,
                    false,
                    false,
                    2.0f,
                    true,
                    180.0,
                ),
            )
        }
        assertEquals(30.0, getField(tick, "maxSMB") as Double, 0.001)
        assertEquals(SMB_ONESHOT_TRACE, trace)
    }

    @Test
    fun legacyBrittleBypassSetsThePiBasal() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMIT3cActivationThreshold to 140.0,
                DoubleKey.OApsAIMIT3cAggressiveness to 1.0,
                DoubleKey.autodriveMaxBasal to 3.0,
                DoubleKey.meal_modes_MaxBasal to 3.0,
            ),
            bools = mapOf(BooleanKey.OApsAIMIT3cBrittleMode to true),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "adaptiveMult", 1.0)
        setField(tick, "lastNgrBasalMultiplier", 1.0)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 4.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "maxSMB", 0.5)
        setField(tick, "variableSensitivity", 50.0f)
        setField(tick, "hourOfDay", 12)
        setField(tick, "eventualBG", 180.0)
        holdRefresh("bolusRefreshInFlight")
        val profile = profileStub()
        whenever(profile.carb_ratio).thenReturn(10.0)
        whenever(profile.variable_sens).thenReturn(50.0)
        val ctx = tickContext(profile, 180.0)
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            invokeNamed(
                "runT3cBrittleBypassOrReturn",
                listOf(
                    ctx,
                    profile,
                    rT,
                    profile,
                    null,
                    2.0f,
                    PhysioMultipliersMTR.NEUTRAL,
                    InsulinActionState.default(),
                ),
            )
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
        assertEquals(1.3, rT.rate!!, 0.001)
        assertEquals(null, rT.units)
        assertEquals(30, rT.duration)
        assertEquals(T3C_BYPASS_TRACE, trace)
    }

    @Test
    fun t3cTreeDeployFailureStaysVisible() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMIT3cActivationThreshold to 140.0,
                DoubleKey.OApsAIMIT3cAggressiveness to 1.0,
                DoubleKey.autodriveMaxBasal to 3.0,
                DoubleKey.meal_modes_MaxBasal to 3.0,
            ),
            bools = mapOf(BooleanKey.OApsAIMIT3cBrittleMode to true),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "adaptiveMult", 1.0)
        setField(tick, "lastNgrBasalMultiplier", 1.0)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 4.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "maxSMB", 0.5)
        setField(tick, "variableSensitivity", 50.0f)
        setField(tick, "hourOfDay", 12)
        setField(tick, "eventualBG", 180.0)
        holdRefresh("bolusRefreshInFlight")
        val physio = mock(AIMIInsulinDecisionAdapterMTR::class.java, Answer { inv: InvocationOnMock ->
            if (inv.method.name == "getLatestSnapshot") throw RuntimeException("boom")
            else if (inv.method.name == "getEffectiveContext") PhysioContextMTR.NEUTRAL
            else null
        })
        setField(tick, "physioAdapter", physio)
        val profile = profileStub()
        whenever(profile.carb_ratio).thenReturn(10.0)
        whenever(profile.variable_sens).thenReturn(50.0)
        val ctx = tickContext(profile, 180.0)
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            invokeNamed(
                "runT3cBrittleBypassOrReturn",
                listOf(
                    ctx,
                    profile,
                    rT,
                    profile,
                    null,
                    2.0f,
                    PhysioMultipliersMTR.NEUTRAL,
                    InsulinActionState.default(),
                ),
            )
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
        assertTrue("rate=${rT.rate} dur=${rT.duration}\n$trace", rT.rate != null && rT.duration != null)
        assertTrue("rate=${rT.rate}\n$trace", trace.contains("T3C physioTree failed (RuntimeException): boom — deploy skipped"))
    }

    @Test
    fun rbtLiveTickLiftsTheV3Smb() {
        val prefs = recordingPreferences(
            doubles = mapOf(DoubleKey.OApsAIMIHighBg to 140.0),
            bools = mapOf(
                BooleanKey.OApsAIMIRecursiveBeliefShadow to true,
                BooleanKey.OApsAIMIautoDriveActive to true,
                BooleanKey.OApsAIMIHyperTrajectoryRelease to true,
            ),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "bg", 226.0)
        setField(tick, "delta", 20.0f)
        setField(tick, "shortAvgDelta", 18.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "maxSMBHB", 2.0)
        setField(tick, "eventualBG", 401.0)
        setField(tick, "hourOfDay", 12)
        setField(tick, "sleepTime", false)
        val curves = AdvancedPredictionCurves(
            iob = listOf(226.0, 200.0),
            cob = listOf(226.0),
            uam = listOf(226.0),
            zt = listOf(226.0),
            hybrid = listOf(226.0, 200.0),
        )
        val floor = ScenarioProjectionCurve(
            kind = ScenarioProjectionKind.CLINICAL_FLOOR,
            pointsMgdl = listOf(226, 147),
            terminalMgdl = 147.0,
            pathMinMgdl = 147.0,
            pathMinHitFloor = false,
        )
        setField(
            tick,
            "lastScenarioProjection",
            ScenarioProjectionPair(
                clinicalFloor = floor,
                scenarioBest = floor.copy(
                    kind = ScenarioProjectionKind.SCENARIO_BEST,
                    terminalMgdl = 401.0,
                    pointsMgdl = listOf(226, 401),
                ),
                contributors = emptyList(),
                cobPointsMgdl = listOf(226),
                ztPointsMgdl = listOf(226),
            ),
        )
        setField(tick, "lastAdvancedPredictionCurves", curves)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        var returned: Any? = null
        val trace = capture {
            returned = invokeNamed(
                "resolveAndWireRbtLiveTick",
                listOf(
                    tickContext(profile, 226.0),
                    profile,
                    rT,
                    20.0f,
                    55.0,
                    0.40,
                    0,
                    0,
                    false,
                    null,
                    null,
                ),
            )
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
        val commit = returned as RbtLiveCommitResult
        assertEquals(0.40, commit.effectiveHtr.v3SmbBeforeU, 1e-9)
        assertEquals(2.0, commit.effectiveHtr.v3SmbAfterU, 1e-9)
        assertEquals(2.0, commit.effectiveHtr.smbFloorU, 1e-9)
        assertEquals(true, commit.effectiveHtr.active)
        assertEquals(false, commit.rbtAuthority)
        assertEquals(RBT_LIVE_TICK_TRACE, trace)
    }

    @Test
    fun pkpdAbsorptionGuardHalvesThePreOnsetSmb() {
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 2.0f)
        setField(tick, "shortAvgDelta", 2.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "predictedBg", 200.0f)
        setField(tick, "eventualBG", 200.0)
        setField(tick, "intervalsmb", 1)
        setField(tick, "pkpdAbsorptionGuardAppliedThisTick", false)
        val channel = tick.javaClass.declaredClasses
            .first { it.simpleName == "PkpdGuardLogChannel" }
            .enumConstants
            .first { it.toString() == "PIPELINE" }
        lateinit var applied: Any
        val trace = capture {
            applied = invokeNamed(
                "applyPkpdAbsorptionGuardOncePerTick",
                listOf(2.0f, preOnsetRuntime(), 10.0, false, false, false, null, channel),
            )!!
        }
        assertEquals(1.0f, resultField(applied, "smbOut"))
        assertEquals(4, resultField(applied, "intervalAddMin"))
        assertEquals(true, resultField(applied, "multiplicationApplied"))
        assertEquals(5, getField(tick, "intervalsmb"))
        assertEquals(PKPD_ABSORPTION_GUARD_TRACE, trace)
    }

    @Test
    fun rbtMergeLiftsTheV3SmbUnderHardAuthority() {
        setField(tick, "delta", 20.0f)
        val htr = HyperTrajectoryReleaseResult(
            active = false,
            tier = HyperSeverityTier.ESTABLISHED,
            severityWeight = 1.0,
            smbFloorU = 0.40,
            v3SmbBeforeU = 0.40,
            v3SmbAfterU = 0.40,
            absorptionOffsetMgdl = 0.0,
            suppressTrajBasalShift = false,
            hypoMinPredIgnored = false,
            reason = "htr",
        )
        val snapshot = RecursiveBeliefSnapshot(
            scales = emptyList(),
            tensions = emptyList(),
            paradoxes = emptyList(),
            resolutions = DoseChannelResolution(
                smbDemandU = 2.0,
                tbrDemandFraction = 0.0,
                waitBias = 0.0,
                dominantScaleMinutes = 30,
                releaseAuthority = ReleaseAuthority.HARD,
                hypoGuardMode = HypoGuardMode.FULL,
                autodriveModeHint = AutodriveModeHint.V3,
                mealChannel = MealChannelHint.NORMAL,
                suppressTrajBasalShift = false,
                hypoMinPredIgnored = false,
                reasonCodes = listOf("DEMAND"),
            ),
            mr7Trace = emptyList(),
        )
        val gate = RecursiveBeliefAuthorityGate.Decision(
            requestedAuthority = ReleaseAuthority.HARD,
            maxAllowedAuthority = ReleaseAuthority.HARD,
            effectiveAuthority = ReleaseAuthority.HARD,
            readinessScore = 1.0,
            liftBlend = 1.0,
            reasonCodes = listOf("LIFT"),
        )
        lateinit var applied: Any
        val trace = capture {
            applied = invokeNamed(
                "mergeRbtHyperTrajectoryRelease",
                listOf(htr, snapshot, gate, RT(runningDynamicIsf = false)),
            )!!
        }
        val commit = applied as RbtLiveCommitResult
        assertEquals(0.40, commit.effectiveHtr.v3SmbBeforeU, 1e-9)
        assertEquals(2.0, commit.effectiveHtr.v3SmbAfterU, 1e-9)
        assertEquals(2.0, commit.effectiveHtr.smbFloorU, 1e-9)
        assertEquals(true, commit.effectiveHtr.active)
        assertEquals(true, commit.rbtAuthority)
        assertEquals(RBT_MERGE_LIFT_TRACE, trace)
    }

    @Test
    fun t3cBasalFirstRampsTheNativeRate() {
        tick = newTick(
            recordingPreferences(
                doubles = emptyMap(),
                bools = mapOf(
                    BooleanKey.OApsAIMIT3cBrittleMode to true,
                    BooleanKey.OApsAIMIRecursiveBeliefAuthority to true,
                ),
            ),
        )
        armShell()
        val profile = profileStub()
        whenever(profile.min_bg).thenReturn(80.0)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "shortAvgDelta", 0.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "predictedBg", 180.0f)
        setField(tick, "eventualBG", 180.0)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "exerciseInsulinLockoutActive", false)
        setField(tick, "lastT3cHistoricalBypassNeutralizedThisTick", true)
        setField(
            tick,
            "lastRecursiveAuthorityGateDecision",
            RecursiveBeliefAuthorityGate.Decision(
                requestedAuthority = ReleaseAuthority.NONE,
                maxAllowedAuthority = ReleaseAuthority.NONE,
                effectiveAuthority = ReleaseAuthority.NONE,
                readinessScore = 0.0,
                liftBlend = 0.0,
                reasonCodes = emptyList(),
            ),
        )
        val t3c = T3cBasalFirstResolution(
            active = true,
            eligible = true,
            basalDemandRateUph = 2.0,
            boundedRateUph = 2.0,
            maxBasalCapUph = 3.0,
            anticipationStrength = 1.0,
            mealConflict = false,
            postHypoBlock = false,
            exerciseBlock = false,
            hardSafetyBlock = false,
            dominantBlocker = null,
        )
        setField(
            tick,
            "lastRecursiveBeliefSnapshot",
            RecursiveBeliefSnapshot(
                scales = emptyList(),
                tensions = emptyList(),
                paradoxes = emptyList(),
                resolutions = DoseChannelResolution(
                    smbDemandU = 0.0,
                    tbrDemandFraction = 0.0,
                    waitBias = 0.0,
                    dominantScaleMinutes = 30,
                    releaseAuthority = ReleaseAuthority.NONE,
                    hypoGuardMode = HypoGuardMode.FULL,
                    autodriveModeHint = AutodriveModeHint.V3,
                    mealChannel = MealChannelHint.NORMAL,
                    suppressTrajBasalShift = false,
                    hypoMinPredIgnored = false,
                    reasonCodes = emptyList(),
                    basalFirstChannel = BasalFirstChannel.T3C_BASAL_FIRST,
                    t3cBasalFirst = t3c,
                ),
                mr7Trace = emptyList(),
            ),
        )
        val ctx = tickContext(profile, glucose = 180.0)
        val rT = RT(runningDynamicIsf = false)
        val bundle = privateData(
            "AimiPostBasalEngineFinalizeBundle",
            listOf(
                ctx,
                profile,
                profile,
                rT,
                BasalDecisionEngine.Decision(rate = 1.0, duration = 30, overrideSafety = false),
                false,
                null,
                0.0,
                1,
            ),
        )
        lateinit var applied: Any
        val trace = capture {
            applied = invokeNamed("planT3cBasalFirstProduction", listOf(bundle))!!
        }
        assertEquals(1.30, resultField(applied, "rateUph") as Double, 1e-9)
        assertEquals(30, resultField(applied, "durationMin"))
        assertEquals(T3C_BASAL_FIRST_TRACE, trace)
    }

    @Test
    fun carbsAdvisorEnableSmbCutsTheBolusFactorNearTarget() {
        val profile = profileStub()
        whenever(profile.enableSMB_always).thenReturn(true)
        whenever(profile.max_iob).thenReturn(10.0)
        whenever(profile.carb_ratio).thenReturn(10.0)
        whenever(profile.min_bg).thenReturn(80.0)
        val profileUtil = getField(tick, "profileUtil") as ProfileUtil
        whenever(profileUtil.fromMgdlToStringInUnits(anyOrNull(), anyOrNull())).thenReturn("100")
        setField(tick, "tirCalculator", mock(TirCalculator::class.java))
        setField(tick, "delta", 0.0f)
        setField(tick, "targetBg", 100.0f)
        val ctx = tickContext(profile, glucose = 180.0)
        val rT = RT(runningDynamicIsf = false)
        val glucose = GlucoseStatusAIMI(glucose = 180.0, date = now)
        val iobData = IobTotal(time = now, iob = 1.0)
        lateinit var stage: Any
        val trace = capture {
            stage = invokeNamed(
                "runCarbsAdvisorEnableSmbBasalHistoryAndSafetyStage",
                listOf(
                    profile,
                    ctx,
                    rT,
                    glucose,
                    iobData,
                    5.0,
                    0.0,
                    50.0,
                    180.0,
                    1.0f,
                    0.0f,
                    0.0f,
                    100.0,
                    0.0f,
                    0,
                    0.0,
                    100.0,
                    180.0,
                    0,
                ),
            )!!
        }
        val safety = resultField(stage, "safetyDecision") as SafetyDecision
        assertEquals(0.5, safety.bolusFactor, 1e-9)
        assertEquals(false, safety.stopBasal)
        assertEquals(false, safety.isHypoRisk)
        assertEquals(true, resultField(stage, "enableSMB"))
        assertEquals(CARBS_SMB_SAFETY_TRACE, trace)
    }

    @Test
    fun uamPostHypoReboundBridgesAShortTempBasal() {
        val profile = profileStub()
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "predictedBg", 180.0f)
        setField(tick, "eventualBG", 180.0)
        setField(tick, "iob", 1.0f)
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            invokeNamed(
                "runUamModelCalHypoGuardPostHypoAndSetPredictedSmb",
                listOf(
                    rT,
                    180.0,
                    0.0f,
                    1.0f,
                    180.0f,
                    180.0,
                    70.0,
                    180.0,
                    100.0,
                    profile,
                    PostHypoState.ReboundSuspected(sinceMs = 0L),
                    0.0f,
                ),
            )
        }
        assertEquals(1.05, rT.rate as Double, 1e-9)
        assertEquals(5, rT.duration)
        assertEquals(0.0f, getField(tick, "predictedSMB"))
        assertEquals(UAM_POST_HYPO_REBOUND_TRACE, trace)
    }

    @Test
    fun therapyExerciseLockoutZerosTheTempBasal() {
        val persistence = mock(PersistenceLayer::class.java)
        val clock = aimiWallClockMs()
        val sport = listOf(
            TE(
                timestamp = clock - 60_000L,
                duration = 3_600_000L,
                type = TE.Type.NOTE,
                note = "sport",
                glucoseUnit = GlucoseUnit.MGDL,
            ),
        )
        runBlocking {
            whenever(persistence.getTherapyEventDataFromTime(any(), any())).thenReturn(sport)
            whenever(persistence.getBolusesFromTime(any(), any())).thenReturn(emptyList())
        }
        setField(tick, "persistenceLayer", persistence)
        val storage = getField(tick, "storage") as AimiStorage
        whenever(storage.file(any<String>())).thenReturn(AimiPath("circadian"))
        whenever(storage.exists(any())).thenReturn(false)
        setField(tick, "bg", 100.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "targetBg", 100.0f)
        val profile = profileStub()
        val ctx = tickContext(profile, glucose = 100.0)
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            invokeNamed(
                "runTherapyHydrateClocksAndExerciseLockoutGate",
                listOf(ctx, profile, rT),
            )
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
        assertEquals(THERAPY_EXERCISE_LOCKOUT_TRACE, trace)
        assertEquals(0.0, rT.units as Double, 1e-9)
    }

    @Test
    fun publishDoseTerminalLiftsEventualOnMealEvidence() {
        tick = newTick(
            recordingPreferences(
                doubles = emptyMap(),
                bools = mapOf(
                    BooleanKey.OApsAIMIPredictionAuthorityEnabled to true,
                    BooleanKey.OApsAIMIAnticipMealEvidence to true,
                ),
            ),
        )
        armShell()
        AimiUamHandler.updateRuntimeConfidence(null)
        val floor = ScenarioProjectionCurve.fromRawPoints(
            ScenarioProjectionKind.CLINICAL_FLOOR,
            listOf(130.0, 120.0),
        )
        val best = ScenarioProjectionCurve.fromRawPoints(
            ScenarioProjectionKind.SCENARIO_BEST,
            listOf(160.0, 180.0),
        )
        setField(
            tick,
            "lastScenarioProjection",
            ScenarioProjectionPair(
                clinicalFloor = floor,
                scenarioBest = best,
                contributors = emptyList(),
                cobPointsMgdl = listOf(140),
                ztPointsMgdl = listOf(140),
            ),
        )
        setField(tick, "bg", 140.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "anticipTime", true)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        val profile = profileStub()
        val meal = MealData(mealCOB = 0.0)
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            invokeNamed(
                "publishDoseTerminalAuthorityAndSnapshot",
                listOf(rT, profile, meal, 140.0, 120.0, 100.0, "pre_rbt"),
            )
        }
        assertEquals(180.0, rT.eventualBG as Double, 1e-9)
        assertEquals(180.0, getField(tick, "eventualBG") as Double, 1e-9)
        assertEquals(DOSE_TERMINAL_MEAL_UPLIFT_TRACE, trace)
    }

    @Test
    fun basalDecisionEngineRaisesSportTemp() {
        val adaptive = mock(AIMIAdaptiveBasal::class.java)
        whenever(adaptive.suggest(anyOrNull())).thenReturn(
            AIMIAdaptiveBasal.Decision(rateUph = null, durationMin = 0, reason = ""),
        )
        val planner = mock(BasalPlanner::class.java)
        whenever(planner.plan(anyOrNull())).thenReturn(null)
        val rh = mock(TextResolver::class.java, Answer { inv: InvocationOnMock ->
            if (inv.method.name == "gs") "phrase" else null
        })
        setField(tick, "basalDecisionEngine", BasalDecisionEngine(rh, adaptive, planner))
        setField(tick, "sportTime", true)
        val profile = profileStub()
        whenever(profile.min_bg).thenReturn(80.0)
        whenever(profile.pre_floor_isf_mgdl).thenReturn(50.0)
        val ctx = tickContext(profile, glucose = 180.0)
        val rT = RT(runningDynamicIsf = false)
        val glucose = GlucoseStatusAIMI(glucose = 180.0, delta = 5.0, date = now)
        val safety = SafetyDecision(stopBasal = false, bolusFactor = 1.0, reason = "", basalLS = false)
        val caps = PumpCaps(basalStep = 0.05, bolusStep = 0.05, minDurationMin = 30, maxBasal = 3.0, maxSmb = 1.0)
        val bundle = privateData(
            "AimiBasalDecisionEngineStageBundle",
            listOf(
                ctx, profile, rT, glucose, 5.0,
                1.0, 1.0, 35.0, 35.0, 50.0,
                180.0, 100.0, 1.0, 10.0, 180.0,
                180.0, 5.0, 5.0, 5.0, 5.0,
                0.0, false, safety, 2.0, 0.0,
                false, 0, 0.0, 0, 0,
                caps, 12, 6, false, false,
                false, false, false,
            ),
        )
        var decision: BasalDecisionEngine.Decision? = null
        val trace = capture {
            decision = invokeNamed("runBasalDecisionEngineDecideStage", listOf(bundle)) as BasalDecisionEngine.Decision
        }
        assertEquals(1.30, decision!!.rate, 1e-9)
        assertEquals(30, decision!!.duration)
        assertFalse(decision!!.overrideSafety)
        assertEquals(BASAL_ENGINE_SPORT_TRACE, trace)
    }

    @Test
    fun trajectoryTightSpiralCutsThePendingBasal() {
        val guard = getField(tick, "trajectoryGuard") as TrajectoryGuard
        whenever(guard.getLastAnalysis()).thenReturn(
            TrajectoryAnalysis(
                classification = TrajectoryType.TIGHT_SPIRAL,
                metrics = TrajectoryMetrics(
                    curvature = 0.40,
                    convergenceVelocity = 0.0,
                    coherence = 0.8,
                    energyBalance = 4.0,
                    openness = 0.2,
                ),
                modulation = TrajectoryModulation.NEUTRAL,
                warnings = emptyList(),
                stableOrbitDistance = 0.0,
                predictedConvergenceTime = null,
            ),
        )
        AimiUamHandler.updateRuntimeConfidence(null)
        setField(tick, "bg", 120.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "shortAvgDelta", 0.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "maxIob", 10.0)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            invokeNamed(
                "runTrajectoryTightSpiralSafetyBridge",
                listOf(
                    profile,
                    rT,
                    IobTotal(time = now, iob = 1.0),
                    120.0,
                    0.0f,
                    0.0f,
                    PhysioMultipliersMTR(),
                    35.0f,
                    MealData(mealCOB = 0.0),
                    false,
                    false,
                ),
            )
        }
        val pending = getField(tick, "pendingTrajSpiralBasal")
            ?: error("pending spiral basal was not set\n$trace")
        assertEquals(0.25, resultField(pending, "proactiveBasalUph") as Double, 1e-9)
        assertEquals(30, resultField(pending, "durationMin"))
        assertEquals(TRAJECTORY_TIGHT_SPIRAL_TRACE, trace)
    }

    @Test
    fun enableSmbAlwaysTurnsTheBolusOn() {
        val profile = profileStub()
        whenever(profile.enableSMB_always).thenReturn(true)
        var enabled = false
        val trace = capture {
            enabled = invokeNamed(
                "enablesmb",
                listOf(
                    profile,
                    true,
                    MealData(mealCOB = 0.0),
                    100.0,
                    false,
                    180.0,
                    0.0,
                    100.0,
                    0.0,
                ),
            ) as Boolean
        }
        assertTrue(enabled)
        assertEquals(ENABLE_SMB_ALWAYS_TRACE, trace)
    }

    @Test
    fun contextActivityZerosSmbAndRaisesTheTarget() {
        val prefs = recordingPreferences(
            doubles = emptyMap(),
            bools = mapOf(BooleanKey.OApsAIMIContextEnabled to true),
        )
        tick = newTick(prefs)
        armShell()
        val activity = ContextIntent.Activity(
            startTimeMs = 1L,
            durationMs = 3_600_000L,
            intensity = ContextIntent.Intensity.HIGH,
        )
        val snapshot = ContextSnapshot(
            timestampMs = 1L,
            activeIntents = listOf(activity),
            hasActivity = true,
            hasIllness = false,
            hasMealRisk = false,
            hasStress = false,
            hasAlcohol = false,
            activityIntensity = ContextIntent.Intensity.HIGH,
            illnessIntensity = null,
            stressIntensity = null,
            alcoholIntensity = null,
        )
        val manager = mock(ContextManager::class.java)
        whenever(manager.getSnapshot(any())).thenReturn(snapshot)
        setField(tick, "contextManager", manager)
        setField(tick, "contextInfluenceEngine", ContextInfluenceEngine(mock(AAPSLogger::class.java)))
        setField(tick, "maxSMB", 0.5)
        setField(tick, "maxSMBHB", 0.5)
        setField(tick, "intervalsmb", 1)
        setField(tick, "sportTime", false)
        setField(tick, "exerciseHyperBasalOverrideActive", false)
        var target: Double? = null
        val trace = capture {
            target = invokeNamed(
                "applyContextModule",
                listOf(180.0, 1.0, 0.0, RT(runningDynamicIsf = false)),
            ) as Double?
        }
        assertEquals(150.0, target!!, 1e-9)
        assertEquals(0.0, getField(tick, "maxSMB") as Double, 1e-9)
        assertEquals(CONTEXT_ACTIVITY_TARGET_TRACE, trace)
    }

    @Test
    fun physioLatentPrefsChangeRaisesTheSmbCeiling() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMIMaxSMB to 1.25,
                DoubleKey.OApsAIMIHighBGMaxSMB to 0.40,
            ),
        )
        tick = newTick(prefs)
        armShell()
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "cob", 0.0f)
        setField(tick, "maxSMB", 0.5)
        setField(tick, "maxSMBHB", 0.5)
        val tpo = mock(AimiTpo::class.java)
        whenever(tpo.consumePrefsChangedThisTick()).thenReturn(true)
        whenever(
            tpo.onPatientStateReady(
                any(), any(), any(), anyOrNull(), any(), any(), any(), any(), any(),
            ),
        ).thenReturn(true)
        setField(tick, "tpoOrchestrator", tpo)
        val trace = capture {
            invokeNamed(
                "updatePhysioLatentState",
                listOf(HealthContextSnapshot(hrNow = 72, rhrResting = 60), null, null),
            )
        }
        assertEquals(1.25, getField(tick, "maxSMB") as Double, 1e-9)
        assertEquals(1.25, getField(tick, "maxSMBHB") as Double, 1e-9)
        assertEquals(PHYSIO_LATENT_SMB_CEILING_TRACE, trace)
    }

    @Test
    fun tubeAdvisorHalvesTheSmbCeiling() {
        val prefs = recordingPreferences(
            doubles = emptyMap(),
            bools = mapOf(BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled to true),
        )
        tick = newTick(prefs)
        armShell()
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "maxSMBHB", 2.0)
        setField(tick, "variableSensitivity", 50.0f)
        setField(tick, "tickEffectiveDiaHours", 5.0)
        setField(
            tick,
            "lastDoseTerminalSnapshot",
            DoseTerminalSnapshot(
                eventualMgdl = 180.0,
                minPredMgdl = 160.0,
                source = "trace",
                authorityApplied = true,
                clampReconciled = false,
                clampReason = null,
                predBGsRemapped = true,
            ),
        )
        val advisor = mock(StraightLineTubeAdvisor::class.java)
        whenever(advisor.advise(any())).thenReturn(
            StraightLineTubeAdvisor.Outcome(
                smbCapScale = 0.5,
                basalCapScale = 1.0,
                feasible = true,
                chosenCost = 0.0,
                reason = "graded",
            ),
        )
        setField(tick, "straightLineTubeAdvisor", advisor)
        val profile = profileStub()
        val trace = capture {
            invokeNamed(
                "applyTubeAdvisorFromDoseSnapshot",
                listOf(profile, MealData(mealCOB = 0.0), 100.0, "pre_rbt"),
            )
        }
        assertEquals(1.0, getField(tick, "maxSMB") as Double, 1e-9)
        assertEquals(1.0, getField(tick, "maxSMBHB") as Double, 1e-9)
        assertEquals(TUBE_ADVISOR_HALF_CAP_TRACE, trace)
    }

    @Test
    fun advancedPredictionPublishesEventualFromDeclaredCob() {
        tick = newTick(recordingPreferences(doubles = emptyMap()))
        armShell()
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            invokeNamed(
                "applyAdvancedPredictions",
                listOf(
                    180.0,
                    0.0f,
                    50.0,
                    arrayOf(IobTotal(time = now, iob = 0.0, activity = 0.0)),
                    MealData(mealCOB = 36.0),
                    profile,
                    rT,
                ),
            )
        }
        assertEquals(322.0, rT.eventualBG!!, 1e-9)
        assertEquals(getField(tick, "predictedBg") as Float, rT.eventualBG!!.toFloat(), 1e-3f)
        assertEquals(ADVANCED_PREDICTION_COB_TRACE, trace)
    }

    @Test
    fun mealAbsorptionFirstWavePrioritizesDelivery() {
        MealAbsorptionMemory.reset()
        MealAbsorptionPhaseHysteresis.reset()
        tick = newTick(recordingPreferences(doubles = emptyMap()))
        armShell()
        setField(tick, "bg", 180.0)
        setField(tick, "predictedBg", 180.0f)
        setField(tick, "delta", 6.0f)
        setField(tick, "shortAvgDelta", 6.0f)
        setField(tick, "longAvgDelta", 6.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "cob", 20.0f)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "maxIob", 10.0)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "hourOfDay", 12)
        var output: MealAbsorptionPhaseEngine.Output? = null
        val trace = capture {
            output = invokeNamed(
                "refreshMealAbsorptionPhase",
                listOf(
                    6.0f,
                    0,
                    72,
                    60,
                    MealSafetyContext(explicitMealTrigger = true),
                    null,
                    now,
                ),
            ) as MealAbsorptionPhaseEngine.Output
        }
        assertEquals(MealAbsorptionPhase.FIRST_WAVE, output!!.phase)
        assertEquals(true, output!!.mealDeliveryPriority)
        assertEquals(MEAL_ABSORPTION_FIRST_WAVE_TRACE, trace)
    }

    @Test
    fun driftTerminatorTapsAMicroSmb() {
        val prefs = recordingPreferences(
            doubles = mapOf(DoubleKey.OApsAIMIMaxSMB to 0.40),
        )
        tick = newTick(prefs)
        armShell()
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "shortAvgDelta", 0.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "maxSMB", 0.0)
        setField(tick, "maxSMBHB", 0.40)
        setField(tick, "sportTime", false)
        val profile = profileStub()
        val rT = RT(runningDynamicIsf = false)
        val ctx = tickContext(profile, 180.0)
        var returned: RT? = null
        val trace = capture {
            returned = invokeNamed(
                "runPostHypoCompressionAndDriftTerminatorOrReturn",
                listOf(
                    ctx,
                    rT,
                    180.0,
                    0.0f,
                    70.0,
                    0.0f,
                    0.0f,
                    100.0f,
                    PostHypoState.None,
                    1.0,
                    false,
                    true,
                    true,
                    false,
                    0.0,
                    0.30,
                    false,
                    StringBuilder(),
                ),
            ) as RT?
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
        assertEquals(0.12, returned?.units ?: -2.0, 1e-4)
        assertEquals(0.40, getField(tick, "maxSMB") as Double, 1e-9)
        assertEquals(DRIFT_TERMINATOR_TAP_TRACE, trace)
    }

    @Test
    fun earlyTickAdoptsThePreferenceTddWhenTheProfileIsEmpty() {
        val prefs = recordingPreferences(
            doubles = mapOf(DoubleKey.OApsAIMITDD7 to 35.0),
            bools = mapOf(BooleanKey.OApsAIMIMealAdvisorTrigger to false),
        )
        tick = newTick(prefs)
        armShell()
        val provider = mock(HormonitorStudyExporterProvider::class.java)
        whenever(provider.exporter()).thenReturn(null)
        setField(tick, "hormonitorStudyExporterProvider", provider)
        val profile = mock(OapsProfileAimi::class.java, Answer { inv ->
            when {
                inv.method.name == "copy" || inv.method.name.startsWith("copy") -> inv.mock
                inv.method.name == "getTDD" -> 0.0
                inv.method.returnType == java.lang.Double.TYPE -> 0.0
                inv.method.returnType == java.lang.Boolean.TYPE -> false
                inv.method.returnType == Integer.TYPE -> 0
                else -> null
            }
        })
        var state: Any? = null
        val trace = capture {
            state = invokeNamed(
                "runEarlyDetermineBasalStages",
                listOf(tickContext(profile, 180.0)),
            )
        }
        val early = state
        assertEquals(35.0, resultField(early!!, "tdd7Days") as Double, 1e-9)
        assertEquals(false, resultField(early, "isExplicitAdvisorRun") as Boolean)
        assertEquals(EARLY_TICK_TDD_TRACE, trace)
    }

    @Test
    fun trajectoryPrepCutsBasalAndSizesTheMicroBolus() {
        tick = newTick(recordingPreferences(doubles = emptyMap()))
        armShell()
        val guard = getField(tick, "trajectoryGuard") as TrajectoryGuard
        whenever(guard.getLastAnalysis()).thenReturn(
            TrajectoryAnalysis(
                classification = TrajectoryType.TIGHT_SPIRAL,
                metrics = TrajectoryMetrics(
                    curvature = 0.40,
                    convergenceVelocity = 0.0,
                    coherence = 0.8,
                    energyBalance = 4.0,
                    openness = 0.2,
                ),
                modulation = TrajectoryModulation.NEUTRAL,
                warnings = emptyList(),
                stableOrbitDistance = 0.0,
                predictedConvergenceTime = null,
            ),
        )
        AimiUamHandler.updateRuntimeConfidence(null)
        setField(tick, "bg", 120.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "shortAvgDelta", 0.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "iob", 1.0f)
        setField(tick, "contextInfluenceEngine", ContextInfluenceEngine(mock(AAPSLogger::class.java)))
        val profile = profileStub()
        val ctx = tickContext(profile, 120.0)
        whenever(ctx.autosensData.ratio).thenReturn(1.0)
        var prep: Any? = null
        val trace = capture {
            prep = invokeNamed(
                "runTrajectoryContextModuleTddIsfAndDynamicPbolusPrep",
                listOf(
                    ctx,
                    profile,
                    RT(runningDynamicIsf = false),
                    IobTotal(time = now, iob = 1.0),
                    PhysioMultipliersMTR(),
                    InsulinActionState.default(),
                    null,
                    35.0,
                    35.0,
                    35.0f,
                    0.0,
                    0.0,
                    StringBuilder(),
                    false,
                ),
            )
        }
        val out = prep
        assertEquals(0.50, resultField(out!!, "dynamicPbolusLarge") as Double, 1e-9)
        assertEquals(0.30, resultField(out, "dynamicPbolusSmall") as Double, 1e-9)
        assertEquals(50.0, resultField(out, "sens") as Double, 1e-9)
        val pending = getField(tick, "pendingTrajSpiralBasal")
            ?: error("pending spiral basal was not set\n$trace")
        assertEquals(0.25, resultField(pending, "proactiveBasalUph") as Double, 1e-9)
        assertEquals(30, resultField(pending, "durationMin"))
        assertEquals(TRAJECTORY_PREP_MICROBOLUS_TRACE, trace)
    }

    @Test
    fun raObservationCarriesTheProfileSensitivity() {
        val prefs = recordingPreferences(
            doubles = mapOf(DoubleKey.OApsAIMIweight to 70.0),
        )
        tick = newTick(prefs)
        armShell()
        AimiUamHandler.updateRuntimeConfidence(null)
        setField(tick, "variableSensitivity", 50.0f)
        setField(tick, "bg", 180.0)
        setField(tick, "delta", 0.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "hourOfDay", 12)
        val profile = profileStub()
        var state: Any? = null
        val trace = capture {
            state = invokeNamed(
                "buildRaObservationState",
                listOf(
                    tickContext(profile, 180.0),
                    0.0f,
                    0.0f,
                    null,
                    false,
                ),
            )
        }
        val observed = state ?: error("RA observation was null\n$trace")
        assertEquals(0.005, resultField(observed, "estimatedSI") as Double, 1e-12)
        assertEquals(70.0, resultField(observed, "patientWeightKg") as Double, 1e-9)
        assertEquals(false, resultField(observed, "applyHypoRecoveryRaDampening") as Boolean)
        assertEquals(RA_OBSERVATION_ISF_TRACE, trace)
    }

    private fun resultField(target: Any, name: String): Any? {
        val field = target.javaClass.getDeclaredField(name)
        field.isAccessible = true
        return field.get(target)
    }

    private fun invokeNamed(name: String, args: List<Any?>): Any? {
        val method = tick.javaClass.declaredMethods.first {
            it.name == name && it.parameterCount == args.size
        }
        method.isAccessible = true
        return try {
            method.invoke(tick, *args.toTypedArray())
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw e.cause ?: e
        }
    }

    private fun invokeSafetyHalt(
        profile: OapsProfileAimi,
        ctx: AimiTickContext,
        rT: RT,
        glucose: GlucoseStatusAIMI,
        scenario: ScenarioProjectionPair,
    ): Any? = invokeNamed(
        "runPredPipelineSafetyHaltOrReturn",
        listOf(
            ctx,
            profile,
            rT,
            glucose.glucose,
            glucose.delta.toFloat(),
            glucose.delta.toFloat(),
            IobTotal(time = now, iob = 1.0),
            glucose,
            scenario,
            false,
        ),
    )

    private fun armPump() {
        val pump = mock(PumpWithConcentration::class.java)
        val desc = PumpDescription()
        desc.basalStep = 0.05
        desc.bolusStep = 0.05
        whenever(pump.pumpDescription).thenReturn(desc)
        whenever(pump.isInitialized()).thenReturn(true)
        whenever(pump.isConnected()).thenReturn(true)
        val plugin = mock(ActivePlugin::class.java)
        whenever(plugin.activePump).thenReturn(pump)
        setField(tick, "activePlugin", plugin)
        val validator = mock(PumpCapabilityValidator::class.java)
        whenever(validator.validateBasal(any(), any())).thenAnswer { it.arguments[0] as Double }
        setField(tick, "pumpCapabilityValidator", validator)
    }

    private fun invokeSafetySmb(): Float {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "applySafetyPrecautions" && it.parameterCount == 9
        }
        method.isAccessible = true
        return method.invoke(
            tick,
            MealData(mealCOB = 0.0),
            2.0f,
            70.0,
            StringBuilder(),
            null,
            false,
            false,
            false,
            false,
        ) as Float
    }

    private fun invokeMaxIobGate(profile: OapsProfileAimi, ctx: AimiTickContext, rT: RT): Any {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runCoreDecisionMaxIobExceededTempBasalGate" && it.parameterCount == 14
        }
        method.isAccessible = true
        return method.invoke(
            tick,
            profile,
            ctx,
            rT,
            profile,
            false,
            false,
            2.0,
            SafetyDecision(stopBasal = false, bolusFactor = 1.0, reason = "", basalLS = false),
            2.0,
            160.0,
            2.0f,
            180.0,
            100.0,
            5.0,
        )!!
    }

    private fun invokeInsulinReq(
        ctx: AimiTickContext,
        rT: RT,
        smbToGive: Float,
        allowMealHighIob: Boolean,
        mealHighIobDamping: Double,
    ) {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runInsulinReqActivityRelaxAndMicrobolusStage" && it.parameterCount == 16
        }
        method.isAccessible = true
        method.invoke(
            tick,
            ctx,
            rT,
            IobTotal(time = now, iob = 4.0),
            smbToGive,
            allowMealHighIob,
            mealHighIobDamping,
            2.0,
            SafetyDecision(stopBasal = false, bolusFactor = 1.0, reason = "", basalLS = false),
            true,
            false,
            180.0,
            4.0f,
            70.0,
            now,
            false,
            null,
        )
    }

    private fun invokeMealFirst(profile: OapsProfileAimi, rT: RT): Any {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runPostSafetyMealFirst30NgrHeadroomBasalSmbStage" && it.parameterCount == 15
        }
        method.isAccessible = true
        return method.invoke(
            tick,
            profile,
            tickContext(profile),
            rT,
            NGRConfig(
                enabled = false,
                pediatricAgeYears = 0,
                nightStart = LocalTime(0, 0),
                nightEnd = LocalTime(6, 0),
                minRiseSlope = 0.0,
                minDurationMin = 0,
                minEventualOverTarget = 0,
                allowSMBBoostFactor = 1.0,
                allowBasalBoostFactor = 1.0,
                maxSMBClampU = 1.0,
                extraIobPer30Min = 0.0,
                decayMinutes = 0,
            ),
            SafetyDecision(stopBasal = false, bolusFactor = 1.0, reason = "", basalLS = false),
            2.0,
            10.0,
            0.5,
            0.2f,
            160.0,
            4.0f,
            1.0f,
            1.0f,
            180.0,
            100.0,
        )!!
    }

    private fun invokeBasalPai(glucose: GlucoseStatusAIMI, profile: OapsProfileAimi) {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runBasalAimiTddCarbLimitsTirEarlyBasalAndPaiIsf" && it.parameterCount == 17
        }
        method.isAccessible = true
        method.invoke(
            tick,
            glucose,
            profile,
            1.0,
            160.0,
            4.0f,
            35.0,
            35.0,
            50.0,
            false,
            10.0,
            50.0,
            null,
            6.0,
            null,
            60.0,
            0.2,
            0.5,
        )
    }

    private fun invokeBasalFirst(): Double {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "basalFirstAdaptiveMultiplier" && it.parameterCount == 0
        }
        method.isAccessible = true
        return method.invoke(tick) as Double
    }

    private fun htr(before: Double, after: Double, reason: String) = HyperTrajectoryReleaseResult(
        active = after > before + 0.02,
        tier = HyperSeverityTier.OFF,
        severityWeight = 0.0,
        smbFloorU = after,
        v3SmbBeforeU = before,
        v3SmbAfterU = after,
        absorptionOffsetMgdl = 0.0,
        suppressTrajBasalShift = false,
        hypoMinPredIgnored = false,
        reason = reason,
    )

    private fun invokeRbtRefine(rT: RT) {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "refineRbtMergeAfterDoseSnapshot" && it.parameterCount == 1
        }
        method.isAccessible = true
        method.invoke(tick, rT)
    }

    private fun invokeDecisionContext(ctx: AimiTickContext): Any? {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "buildDecisionContextInitRtSosAndFlatShadow" && it.parameterCount == 1
        }
        method.isAccessible = true
        return method.invoke(tick, ctx)
    }

    private fun preOnsetRuntime(): PkPdRuntime = PkPdRuntime(
        params = PkPdParams(diaHrs = 5.0, peakMin = 75.0),
        tailFraction = 0.0,
        fusedIsf = 50.0,
        profileIsf = 50.0,
        tddIsf = 50.0,
        pkpdScale = 1.0,
        weightKineticFactor = 1.0,
        physioAbsorptionFactor = 1.0,
        physioSiFactor = 1.0,
        damping = SmbDamping(),
        activity = InsulinActivityState(
            window = InsulinActivityWindow(onsetMin = 15.0, peakMin = 75.0, offsetMin = 180.0, diaMin = 300.0),
            relativeActivity = 0.10,
            normalizedPosition = 0.0,
            postWindowFraction = 0.0,
            anticipationWeight = 1.0,
            minutesUntilOnset = 10.0,
            stage = InsulinActivityStage.PRE_ONSET,
        ),
    )

    private fun invokePkpdGuard(ctx: AimiTickContext, rT: RT): Any? {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runPkpdGuardEndoDampenRedCarpetAndCapSmb" && it.parameterCount == 24
        }
        method.isAccessible = true
        return method.invoke(
            tick,
            ctx,
            rT,
            null,
            SmbInstructionExecutor.Result(
                predictedSmb = 0f,
                basal = 1.0,
                finalSmb = 0f,
                highBgOverrideUsed = false,
                newSmbInterval = null,
            ),
            false,
            false,
            false,
            110.0,
            0.0f,
            0.0f,
            110.0f,
            110.0,
            100.0,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            0,
            4,
            0.0f,
            1.0f,
        )
    }

    private fun invokePkpdGuardActive(ctx: AimiTickContext, rT: RT, runtime: PkPdRuntime): Any? {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runPkpdGuardEndoDampenRedCarpetAndCapSmb" && it.parameterCount == 24
        }
        method.isAccessible = true
        return method.invoke(
            tick,
            ctx,
            rT,
            runtime,
            SmbInstructionExecutor.Result(
                predictedSmb = 2f,
                basal = 1.0,
                finalSmb = 2f,
                highBgOverrideUsed = false,
                newSmbInterval = null,
            ),
            false,
            false,
            true,
            160.0,
            2.0f,
            2.0f,
            170.0f,
            170.0,
            100.0,
            false,
            false,
            false,
            false,
            false,
            false,
            false,
            0,
            3,
            2.0f,
            1.0f,
        )
    }

    private fun invokeT9(
        ctx: AimiTickContext,
        glucose: GlucoseStatusAIMI,
        rT: RT,
        iobTotal: Double,
    ): Any {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runT9PhysioEarlyPkpdAndTubeBootstrap" && it.parameterCount == 4
        }
        method.isAccessible = true
        return method.invoke(tick, ctx, glucose, rT, iobTotal)
    }

    @Test
    fun tickClockKeepsTheStandardMaxSmbAtAFlat110() {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.ApsSmbMaxIob to 10.0,
                DoubleKey.OApsAIMIMaxSMB to 0.50,
                DoubleKey.OApsAIMIHighBGMaxSMB to 1.20,
            ),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "bg", 180.0)
        setField(tick, "now", now)
        val profile = profileStub()
        val glucose = GlucoseStatusAIMI(glucose = 110.0, delta = 0.0, shortAvgDelta = 0.0, date = now)
        val rT = RT(runningDynamicIsf = false)
        val trace = capture {
            invokeTickClock(tickContext(profile, 110.0), glucose, rT, combinedDelta = 0.0f)
        }.replace(Regex("(?<![A-Za-z])ts=\\d+"), "ts=<clock>")
        assertEquals(0.50, getField(tick, "maxSMB") as Double, 1e-6)
        assertEquals("phrase", rT.reason.toString())
        assertEquals(TICK_CLOCK_TRACE, trace)
    }

    private fun invokeTickClock(
        ctx: AimiTickContext,
        glucose: GlucoseStatusAIMI,
        rT: RT,
        combinedDelta: Float,
    ): Any? {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runTickClockMaxSmbTirCarbAndGlucoseCopy" && it.parameterCount == 4
        }
        method.isAccessible = true
        return method.invoke(tick, ctx, glucose, rT, combinedDelta)
    }

    @Test
    fun mealHyperFastingForcesBasalFromThePositiveDelta() {
        val prefs = recordingPreferences(emptyMap())
        setField(tick, "preferences", prefs)
        setField(tick, "fastingTime", true)
        setField(tick, "bg", 110.0)
        setField(tick, "delta", 2.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "cob", 0.0f)
        val profile = profileStub()
        val ctx = tickContext(profile, 110.0)
        val rT = RT(runningDynamicIsf = false)
        var returned: Any? = null
        val trace = capture {
            returned = invokeMealHyper(ctx, profile, rT, targetBg = 100.0)
        }
        val rate = returned!!.javaClass.getDeclaredField("rate").apply { isAccessible = true }.get(returned) as Double?
        assertEquals(2.0, rate ?: -1.0, 1e-6)
        assertEquals("0m@1.00 AI Force basal because fastingTime", rT.reason.toString())
        assertEquals(MEAL_HYPER_FASTING_TRACE, trace)
    }

    private fun invokeMealHyper(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        targetBg: Double,
    ): Any? {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "resolveMealHyperBasalBoostOutcome" && it.parameterCount == 9
        }
        method.isAccessible = true
        return method.invoke(
            tick,
            ctx,
            profile,
            rT,
            1.0,
            1.0,
            false,
            targetBg,
            Double.MAX_VALUE,
            0.0,
        )
    }

    private fun invokeRbtResolve(
        rT: RT,
        profile: OapsProfileAimi,
        htr: HyperTrajectoryReleaseResult = HyperTrajectoryReleaseResult(
            active = false,
            tier = HyperSeverityTier.OFF,
            severityWeight = 0.0,
            smbFloorU = 0.0,
            v3SmbBeforeU = 0.0,
            v3SmbAfterU = 0.0,
            absorptionOffsetMgdl = 0.0,
            suppressTrajBasalShift = false,
            hypoMinPredIgnored = false,
            reason = "off",
        ),
    ): Any? {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runRecursiveBeliefResolve" && it.parameterCount == 13
        }
        method.isAccessible = true
        return method.invoke(
            tick,
            0.0,
            htr,
            rT,
            2.0f,
            30.0,
            profile,
            mock(AutosensResult::class.java),
            GlucoseStatusAIMI(glucose = 180.0, date = now),
            0,
            0,
            false,
            null,
            null,
        )
    }

    private fun invokeFinalize(rT: RT, proposedUnits: Double) {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "finalizeAndCapSMB" && it.parameterCount == 10
        }
        method.isAccessible = true
        method.invoke(
            tick,
            rT,
            proposedUnits,
            "micro",
            MealData(mealCOB = 0.0),
            70.0,
            false,
            "GlobalAIMI",
            false,
            0.0,
            false,
        )
    }

    @Test
    fun engagedHypoWithMealContextRecordsTheEngineCommand() {
        val (trace, applied) = autodriveTrace(
            glucose = 54.0,
            delta = 0.4f,
            shortAvg = 0.3f,
            meal = true,
        )
        assertFalse(applied)
        assertEquals(HYPO_MEAL_ENGINE_TRACE, trace)
    }

    private fun rbtTrace(
        brittle: Boolean,
        bg: Double,
        delta: Float,
        shortAvg: Float,
        adaptive: Double,
        maxBasal: Double,
        currentBasal: Double = maxBasal,
        eventual: Double = 0.0,
        recentGlucose: List<Float> = listOf(110f, 100f, 80f, 65f),
    ): String {
        val prefs = recordingPreferences(
            doubles = mapOf(
                DoubleKey.OApsAIMIT3cAnticipationStrength to 0.40,
                DoubleKey.OApsAIMILastEstimatedCarbs to 5.0,
                DoubleKey.OApsAIMISmbTailDamping to 0.25,
                DoubleKey.OApsAIMIT3cActivationThreshold to 140.0,
            ),
            bools = mapOf(BooleanKey.OApsAIMIT3cBrittleMode to brittle),
        )
        setField(tick, "preferences", prefs)
        setField(tick, "bg", bg)
        setField(tick, "delta", delta)
        setField(tick, "shortAvgDelta", shortAvg)
        setField(tick, "targetBg", 90.0f)
        setField(tick, "hourOfDay", 12)
        setField(tick, "adaptiveMult", adaptive)
        setField(tick, "eventualBG", eventual)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "variableSensitivity", 50.0f)
        setField(
            tick,
            "lastUamHypothesisState",
            UamHypothesisState(
                mealProb = 0.72,
                dominant = UamHypothesisId.MEAL,
                dominantConfidence = 0.72,
            ),
        )
        val glucose = mock(GlucoseStatusCalculatorAimi::class.java)
        whenever(glucose.getRecentGlucose()).thenReturn(recentGlucose)
        whenever(glucose.getAimiFeatures(true)).thenReturn(null)
        setField(tick, "glucoseStatusCalculatorAimi", glucose)
        AimiUamHandler.updateRuntimeConfidence(0.70)
        val profile = profileStub()
        whenever(profile.current_basal).thenReturn(currentBasal)
        whenever(profile.max_basal).thenReturn(maxBasal)
        val autosens = mock(AutosensResult::class.java)
        whenever(autosens.ratio).thenReturn(1.0)
        return captureSignals {
            tick.buildRbtExtendedSignals(
                rT = RT(runningDynamicIsf = false),
                profile = profile,
                htr = HyperTrajectoryReleaseResult(
                    active = false,
                    tier = HyperSeverityTier.OFF,
                    severityWeight = 0.0,
                    smbFloorU = 0.0,
                    v3SmbBeforeU = 0.0,
                    v3SmbAfterU = 0.0,
                    absorptionOffsetMgdl = 0.0,
                    suppressTrajBasalShift = false,
                    hypoMinPredIgnored = false,
                    reason = "trace",
                ),
                v3SmbU = 0.0,
                autosens = autosens,
                glucoseStatus = GlucoseStatusAIMI(glucose = bg),
                mpcFeedForwardRa = null,
                cbfShieldDeltaU = null,
            )
        }
    }

    private fun autodriveTrace(
        glucose: Double,
        delta: Float,
        shortAvg: Float,
        meal: Boolean,
        activityLockout: Boolean = false,
        activityFactor: Double? = null,
    ): Pair<String, Boolean> {
        val doubles = mutableMapOf(
            DoubleKey.OApsAIMIweight to 70.0,
            DoubleKey.OApsAIMIautodrivesmallPrebolus to 0.50,
            DoubleKey.OApsAIMIautodrivePrebolus to 1.50,
        )
        if (activityFactor != null) doubles[DoubleKey.OApsAIMIActivityBasalCapFactor] = activityFactor
        val prefs = recordingPreferences(
            doubles = doubles,
            bools = mapOf(BooleanKey.OApsAIMIautoDriveActive to true),
        )
        val engine = mock(AutodriveEngine::class.java, Answer { inv: InvocationOnMock ->
            if (inv.method.name == "tick") {
                AutoDriveCommand(
                    scheduledMicroBolus = 0.80,
                    temporaryBasalRate = 2.40,
                    isSafe = true,
                    reason = "meal-rise",
                )
            } else if (inv.method.returnType == Void.TYPE) {
                null
            } else {
                zeroFor(inv)
            }
        })
        tick = newTick(prefs, engine)
        armShell()
        setField(tick, "mealTime", meal)
        setField(tick, "bg", glucose)
        setField(tick, "delta", delta)
        setField(tick, "shortAvgDelta", shortAvg)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "maxSMB", 2.0)
        setField(tick, "hourOfDay", 12)
        setField(tick, "variableSensitivity", 50.0f)
        setField(tick, "targetBg", 100.0f)
        setField(tick, "exerciseInsulinLockoutActive", activityLockout)
        val profile = profileStub()
        var applied = true
        val trace = capture {
            applied = tick.runAutodriveV3MultiVariableBranch(
                ctx = tickContext(profile, glucose),
                profile = profile,
                rT = RT(runningDynamicIsf = false),
                bg = glucose,
                combinedDelta = delta,
                shortAvgDeltaAdj = shortAvg,
                hypoThresholdMgdl = 70.0,
                pkpdRuntime = null,
            ).appliedAction
        }.replace(Regex("ts=\\d+"), "ts=<clock>")
        return trace to applied
    }

    private fun armShell() {
        setField(tick, "dateUtil", dateUtil)
        setField(tick, "consoleLog", ProbingLog())
        setField(tick, "aapsLogger", mock(AAPSLogger::class.java))
        setField(tick, "physioAdapter", physioStub())
        setField(tick, "basalNeuralLearner", learnerStub())
        setField(tick, "basalLearner", basalLearnerStub())
        setField(tick, "trajectoryGuard", mock(TrajectoryGuard::class.java))
        val glucose = mock(GlucoseStatusCalculatorAimi::class.java)
        whenever(glucose.getBucketedGlucoseSinceMinutes(any())).thenReturn(emptyList())
        whenever(glucose.getRecentGlucose()).thenReturn(emptyList())
        whenever(glucose.getAimiFeatures(any())).thenReturn(null)
        setField(tick, "glucoseStatusCalculatorAimi", glucose)
        setField(tick, "storage", mock(AimiStorage::class.java))
        setField(tick, "tddCalculator", mock(TddCalculator::class.java))
        val ads = mock(AutosensDataStore::class.java)
        whenever(ads.getBucketedDataTableCopy()).thenReturn(null)
        val iobCalc = mock(IobCobCalculator::class.java)
        whenever(iobCalc.ads).thenReturn(ads)
        setField(tick, "iobCobCalculator", iobCalc)
        val estimator = mock(ContinuousStateEstimator::class.java)
        whenever(estimator.getLastRa()).thenReturn(0.40)
        whenever(estimator.runCount).thenReturn(-1L)
        setField(tick, "continuousStateEstimator", estimator)
        val health = mock(AimiHealthContext::class.java)
        whenever(health.fetchSnapshotForAutodriveGater()).thenReturn(HealthContextSnapshot(hrNow = 72, rhrResting = 60))
        setField(tick, "autodriveGater", AutoDriveGater(health, mock(AAPSLogger::class.java)))
    }

    private fun physioStub(): AIMIInsulinDecisionAdapterMTR =
        mock(AIMIInsulinDecisionAdapterMTR::class.java, Answer { inv: InvocationOnMock ->
            when (inv.method.name) {
                "getLatestSnapshot" -> HealthContextSnapshot(hrNow = 72, rhrResting = 60)
                "getEffectiveContext" -> PhysioContextMTR.NEUTRAL
                else -> null
            }
        })

    private fun basalLearnerStub(): app.aaps.plugins.aps.openAPSAIMI.learning.BasalLearner {
        val learner = mock(app.aaps.plugins.aps.openAPSAIMI.learning.BasalLearner::class.java)
        whenever(learner.shortTermMultiplier).thenReturn(1.0)
        whenever(learner.mediumTermMultiplier).thenReturn(1.0)
        whenever(learner.longTermMultiplier).thenReturn(1.0)
        return learner
    }

    private fun learnerStub(): BasalNeuralLearner =
        mock(BasalNeuralLearner::class.java, Answer { inv: InvocationOnMock ->
            if (inv.method.name == "getGovernanceSnapshot") {
                BasalNeuralLearner.GovernanceSnapshot(
                    action = BasalNeuralLearner.GovernanceAction.KEEP,
                    confidence = 0.0,
                    sampleCount = 0,
                    hypoRate = 0.0,
                    severeHypoCount = 0,
                    highRate = 0.0,
                    meanAbsTargetError = 0.0,
                    reason = "trace",
                    timestamp = now,
                )
            } else if (inv.method.returnType == Void.TYPE) {
                null
            } else {
                zeroFor(inv)
            }
        })

    private fun profileStub(): OapsProfileAimi {
        val profile = mock(OapsProfileAimi::class.java)
        whenever(profile.current_basal).thenReturn(1.0)
        whenever(profile.max_basal).thenReturn(3.0)
        whenever(profile.max_daily_basal).thenReturn(1.0)
        whenever(profile.sens).thenReturn(50.0)
        whenever(profile.dia).thenReturn(5.0)
        whenever(profile.target_bg).thenReturn(100.0)
        whenever(profile.lgsThreshold).thenReturn(70)
        whenever(profile.temptargetSet).thenReturn(false)
        return profile
    }

    private fun tickContext(profile: OapsProfileAimi, glucose: Double = 160.0): AimiTickContext = AimiTickContext(
        glucoseStatus = GlucoseStatusAIMI(glucose = glucose, date = now),
        currentTemp = CurrentTemp(duration = 0, rate = 1.0, minutesrunning = 0),
        iobDataArray = arrayOf(IobTotal(time = now, iob = 1.0)),
        profile = profile,
        autosensData = mock(AutosensResult::class.java),
        mealData = MealData(mealCOB = 0.0),
        microBolusAllowed = true,
        currentTime = now,
        flatBGsDetected = false,
        dynIsfMode = false,
        uiInteraction = mock(UiInteraction::class.java),
        extraDebug = "",
    )

    private fun capture(block: () -> Unit): String {
        val lines = mutableListOf<String>()
        AimiEffectProbe.lines.set(lines)
        try {
            block()
        } finally {
            AimiEffectProbe.lines.remove()
        }
        return lines.joinToString("\n")
    }

    private fun captureSignals(block: () -> RbtExtendedSignals): String {
        val lines = mutableListOf<String>()
        AimiEffectProbe.lines.set(lines)
        try {
            val signals = block()
            lines += listOf(
                "SIGNAL postHypoOrdinal=${signals.postHypoOrdinal}",
                "SIGNAL uamDominant=${signals.uamHypothesisDominant}",
                "SIGNAL uamMealProb=${fmtOrNull(signals.uamMealProb)}",
                "SIGNAL uamSuppress=${signals.uamSuppressMealInterpretation}",
                "SIGNAL t3cActive=${signals.t3cActive}",
                "SIGNAL t3cDemand=${fmtOrNull(signals.t3cBasalDemandRateUph)}",
                "SIGNAL ngrSmb=${fmtOrNull(signals.ngrSmbMult)}",
                "SIGNAL ngrBasal=${fmtOrNull(signals.ngrBasalMult)}",
                "SIGNAL tuning=${signals.tuningContextLabel}",
                "SIGNAL ngrMember=${aimiFmt2(getField(tick, "lastNgrBasalMultiplier") as Double)}",
            ).joinToString("\n")
            return lines.joinToString("\n")
        } finally {
            AimiEffectProbe.lines.remove()
        }
    }

    private fun fmtOrNull(value: Double?): String = if (value == null) "null" else aimiFmt2(value)

    private fun newTick(
        preferences: Preferences,
        engine: AutodriveEngine = mock(AutodriveEngine::class.java),
        learnedState: PkPdLearnedState = mock(PkPdLearnedState::class.java),
    ): DetermineBasalaimiSMB2 {
        val rh = mock(TextResolver::class.java, Answer { inv: InvocationOnMock ->
            if (inv.method.name == "gs") "phrase" else null
        })
        return DetermineBasalaimiSMB2(
            profileUtil = mock(ProfileUtil::class.java),
            fabricPrivacy = mock(FabricPrivacy::class.java),
            preferences = preferences,
            pkPdLearnedState = learnedState,
            gestationalAutopilot = mock(GestationalAutopilot::class.java),
            auditorOrchestrator = mock(AimiAuditor::class.java),
            behaviorProfileSource = mock(AimiBehaviorProfileSource::class.java),
            uiInteraction = mock(UiInteraction::class.java),
            notificationManager = mock(NotificationManager::class.java),
            wCycleFacade = mock(WCycleFacade::class.java),
            wCyclePreferences = mock(WCyclePreferences::class.java),
            wCycleLearner = mock(WCycleLearner::class.java),
            pumpCapabilityValidator = mock(PumpCapabilityValidator::class.java),
            dynamicBasalController = mock(DynamicBasalController::class.java),
            autodriveEngine = engine,
            rh = rh,
        )
    }

    private fun recordingPreferences(
        doubles: Map<DoubleKey, Double>,
        bools: Map<BooleanKey, Boolean> = emptyMap(),
    ): Preferences = mock(Preferences::class.java, Answer { inv ->
        val key = inv.arguments.firstOrNull()
        val label = when (key) {
            is Enum<*> -> "${key::class.simpleName}.${key.name}"
            null -> "null"
            else -> key.toString()
        }
        when (inv.method.name) {
            "get" -> {
                val value: Any = when (key) {
                    is DoubleKey -> doubles[key] ?: 0.0
                    is BooleanKey -> bools[key] ?: false
                    else -> zeroFor(inv)
                }
                AimiEffectProbe.add(aimiTraceRead(label, traceValue(value)))
                value
            }
            "put" -> {
                AimiEffectProbe.add(aimiTraceWrite(label, traceValue(inv.arguments.getOrNull(1))))
                null
            }
            "getIfExists" -> {
                AimiEffectProbe.add(aimiTraceRead(label, "null"))
                null
            }
            else -> zeroFor(inv)
        }
    })

    private fun zeroFor(inv: InvocationOnMock): Any = when (inv.method.returnType) {
        java.lang.Boolean.TYPE -> false
        Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Double.TYPE -> 0.0
        java.lang.Float.TYPE -> 0f
        else -> ""
    }

    private fun traceValue(value: Any?): String = when (value) {
        is Double -> aimiFmt2(value)
        is Float -> aimiFmt2(value.toDouble())
        null -> "null"
        else -> value.toString()
    }

    private fun getField(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                return field.get(target)
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("no field $name")
    }

    private fun holdRefresh(name: String) {
        (getField(tick, name) as AtomicBoolean).set(true)
    }

    private fun setAtomic(name: String, value: Any?) {
        @Suppress("UNCHECKED_CAST")
        (getField(tick, name) as AtomicReference<Any?>).set(value)
    }

    private fun privateData(simpleName: String, args: List<Any?>): Any {
        val clazz = tick.javaClass.declaredClasses.first { it.simpleName == simpleName }
        val ctor = clazz.declaredConstructors
            .filter { ctor -> ctor.parameterTypes.none { it.name.contains("DefaultConstructorMarker") } }
            .maxBy { it.parameterCount }
        ctor.isAccessible = true
        return ctor.newInstance(*args.toTypedArray())
    }

    private fun setField(target: Any, name: String, value: Any?) {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            try {
                val field = type.getDeclaredField(name)
                field.isAccessible = true
                field.set(target, value)
                return
            } catch (_: NoSuchFieldException) {
                type = type.superclass
            }
        }
        error("no field $name")
    }

    private class ProbingLog : MutableList<String> by mutableListOf() {
        override fun add(element: String): Boolean {
            AimiEffectProbe.add(aimiTraceLog(element))
            return true
        }
    }

    companion object {
        private val ENABLE_SMB_ALWAYS_TRACE = """
            LOG phrase
        """.trimIndent()

        private val RA_OBSERVATION_ISF_TRACE = """
            READ key=DoubleKey.OApsAIMIweight value=70.00
        """.trimIndent()

        private val TRAJECTORY_PREP_MICROBOLUS_TRACE = """
            READ key=BooleanKey.OApsAIMITrajectoryGuardEnabled value=false
            LOG 🌀 Trajectory: ⏸ Disabled
            READ key=BooleanKey.OApsAIMIautoDriveActive value=false
            READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
            READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            LOG 🌀🛡️ TRAJECTORY_SAFETY_BRIDGE (deferred): TRAJ_TIGHT_SPIRAL: E=4.0U κ=0.40 IOB=1.00U → Basale proactive 25% [STACKING_SPIRAL]
            READ key=DoubleKey.OApsAIMIweight value=0.00
            READ key=BooleanKey.OApsAIMIContextEnabled value=false
            LOG ═══════════════════════════════════
            LOG 📦 CACHE TDD1D_SPARSE=MISSING reason=tdd_1day_sparse_missing
        """.trimIndent()

        private val EARLY_TICK_TDD_TRACE = """
            READ key=BooleanKey.OApsAIMIMealAdvisorTrigger value=false
            READ key=BooleanKey.OApsAIMIMealAdvisorTrigger value=false
            READ key=DoubleKey.OApsAIMITDD7 value=35.00
        """.trimIndent()

        private val DRIFT_TERMINATOR_TAP_TRACE = """
            READ key=DoubleKey.OApsAIMIMaxSMB value=0.40
            LOG ⚡ DriftTerminator: Overrode Basal-First block (MaxSMB 0.0 -> 0.40)
            LOG AD_EARLY_TBR_TRIGGER rate=0.0 duration=0 reason=DriftTerminator_Tap
            LOG AD_SMALL_PREBOLUS_TRIGGER amount=0.3 reason=DriftTerminator
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIhoneymoon value=false
            READ key=BooleanKey.OApsAIMIHyperDroppingExemptEnabled value=false
            READ key=BooleanKey.OApsAIMIhoneymoon value=false
            READ key=BooleanKey.OApsAIMIMealAdvisorTrigger value=false
            READ key=BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled value=false
            READ key=DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor value=0.00
            READ key=BooleanKey.OApsAIMIhoneymoon value=false
            READ key=BooleanKey.OApsAIMInight value=false
            READ key=IntKey.OApsAIMISnackinterval value=0
            READ key=IntKey.OApsAIMImealinterval value=0
            READ key=IntKey.OApsAIMIBFinterval value=0
            READ key=IntKey.OApsAIMILunchinterval value=0
            READ key=IntKey.OApsAIMIDinnerinterval value=0
            READ key=IntKey.OApsAIMISleepinterval value=0
            READ key=IntKey.OApsAIMIHCinterval value=0
            READ key=IntKey.OApsAIMIHighBGinterval value=0
            LOG 📦 CACHE TDD24H=MISSING reason=tdd24h_missing
            READ key=BooleanKey.OApsAIMIRiseCeilingGuard value=false
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIIobSurveillanceGuard value=false
            LOG PKPD_THROTTLE smbFactor=0.60 intervalAdd=3 preferTbr=true reason=Onset unconfirmed, rising BG → TBR priority
            WRITE key=AimiLongKey.LastPrebolusTime value=1700000000000
            LOG GATE_REFRACTORY sinceLastBolus=999.0m window=5.0
            LOG GATE_MAXIOB allowed=10.00 current=1.00
            LOG GATE_MAXSMB cap=0.40 proposed=0.30
            LOG GATE_ABSORPTION activity=0.000 threshold=0.188 factor=1.00
            LOG GATE_PRED_MISSING fallback=ON
            LOG SMB_CAP: Proposed=0.3 Allowed=0.120000005 Reason=🧹 Drift Terminator: Plateau detected (Δ0.0 Avg0.0 Dev999) -> ENGAGED
             [Drift Override]→ Drift Terminator (Trigger +15.0): Micro-Tap 0.3U

            LOG   -> Limits: MaxSMB=0.4 MaxIOB=10.0 IOB=1.0
            READ key=BooleanKey.OApsAIMIIobSurveillanceGuard value=false
            LOG DECISION_FINAL[DRIFT_TERMINATOR]: smb=0.00U tbr=0.00U/h dur=0m bg=180 Δ=0.0 reason= | 💡 TBR recommended (Onset unconfirmed, rising BG → TBR priority)🧹 Drift Terminator: Plateau detected (Δ0.0 Avg0.0 Dev999) -> ENGAGED |  [Drift Override]→ Drift Terminator (Trig
            LOG 📦 CACHE TDD24H=MISSING reason=tdd24h_not_ready
            LOG 🧭 BASAL_GOV[FINAL]: action=KEEP conf=0.00 n=0 hypo=0.00 hypoG=0.00 hypoAdj=0.00 ant=0.00 wMean=1.00 high=0.00 mae=0.0 latch=false floorB=- floorA=- wBolus=0.12U wCob=?g reason=trace
            LOG TICK ts=<clock> bg=180 d=0.0 iob=1.00 act=0.000 th=0.188 cob=0.0 mode=None autodriveState=IDLE pred=N(sz=0 ev=0) safety=NONE ref=NO maxIOB=10.00 maxSMB=0.40 smb=0.30->0.12->0.12 tbr=0.00 src=DriftTerminator
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
            READ key=BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled value=false
            READ key=BooleanKey.OApsAIMIUnifiedReactivityEnabled value=false
            READ key=BooleanKey.OApsAIMIPkpdEnabled value=false
            READ key=BooleanKey.OApsAIMIautoDriveActive value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
            READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
            READ key=BooleanKey.OApsAIMIPeakGovernorEnabled value=false
            READ key=BooleanKey.OApsAIMIDiaGovernorEnabled value=false
        """.trimIndent()

        private val MEAL_ABSORPTION_FIRST_WAVE_TRACE = """
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            LOG 🍽️ MEAL_ABSORPTION: FIRST_WAVE B=1.00 pri=true waves=1 (FIRST_WAVE B=1.00 π=0.85 K=1.00 T=0.00 P=0.35)
        """.trimIndent()

        private val ADVANCED_PREDICTION_COB_TRACE = """
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=BooleanKey.OApsAIMIUndeclaredCobEnabled value=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdHyperReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdStackAwareGuardB value=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            LOG PKPD_SOFT_FLOOR: raw=146 soft=146 hybT=321 hitFloor=false applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled
            LOG PRED_SET size=49 eventual=322 min=180 uamT=147 source=AdvancedCurves
            LOG Prédiction avancée avec ISF final de 50.0 (Avancé)
        """.trimIndent()

        private val TUBE_ADVISOR_HALF_CAP_TRACE = """
            READ key=BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled value=true
            LOG 📐 TUBE-LINE-D4[pre_rbt]: maxSMB=1.00 basal×1.000 | graded
        """.trimIndent()

        private val PHYSIO_LATENT_SMB_CEILING_TRACE = """
            READ key=BooleanKey.OApsAIMISensorConfidenceCgmFirst value=false
            READ key=BooleanKey.OApsAIMIEffortActivityProtection value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
            READ key=StringKey.AimiTuningContextSelection value=
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=DoubleKey.autodriveMaxBasal value=0.00
            LOG TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
            LOG Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
            LOG MEAL_CERTAINTY level=NONE tree=NONE rise=WEAK terminals=UNKNOWN effortVeto=false
            LOG Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,max_iob_pressure,critical_risk
            READ key=DoubleKey.OApsAIMIMaxSMB value=1.25
            READ key=DoubleKey.OApsAIMIHighBGMaxSMB value=0.40
        """.trimIndent()

        private val CONTEXT_ACTIVITY_TARGET_TRACE = """
            READ key=BooleanKey.OApsAIMIContextEnabled value=true
            LOG ═══ CONTEXT MODULE ═══
            READ key=StringKey.ContextMode value=
            LOG 🎯 Active Contexts: 1
            LOG   • Activity
            LOG   SMB: 0.50→0.38U (×0.75)
            LOG   Interval: 1→6min (+5)
            LOG   ⚠️ Prefers TEMP BASAL over SMB (SMB Disabled)
            LOG   🎯 Sport Target Override -> 150 mg/dL
            LOG   → Activity HIGH → SMB×0.75 +5min preferBasal=true
            LOG ═══════════════════════════════════
        """.trimIndent()

        private val TRAJECTORY_TIGHT_SPIRAL_TRACE = """
            READ key=BooleanKey.OApsAIMIautoDriveActive value=false
            READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
            READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            LOG 🌀🛡️ TRAJECTORY_SAFETY_BRIDGE (deferred): TRAJ_TIGHT_SPIRAL: E=4.0U κ=0.40 IOB=1.00U → Basale proactive 25% [STACKING_SPIRAL]
            READ key=DoubleKey.OApsAIMIweight value=0.00
        """.trimIndent()

        private val BASAL_ENGINE_SPORT_TRACE = """
            READ key=BooleanKey.OApsAIMIBasalProjectedError value=false
        """.trimIndent()

        private val DOSE_TERMINAL_MEAL_UPLIFT_TRACE = """
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=true
            READ key=BooleanKey.OApsAIMIPredictionAuthorityShadow value=false
            READ key=BooleanKey.OApsAIMIMealConfirmedEarlyRelease value=false
            READ key=BooleanKey.OApsAIMIAnticipMealEvidence value=true
            LOG PRED_AUTHORITY: src=SCENARIO_MEAL_UPLIFT predT=120 evT=180 pkpd=140 best=180 mealSupp=false uplift=true meal_evidence phase=NONE mealCert=NONE trunk=NONE lead=40.0 cause=UNKNOWN [pre_rbt]
            LOG PRED_AUTHORITY_C1[pre_rbt]: eventual=180 predT=120 curves=true src=SCENARIO_MEAL_UPLIFT
            LOG DOSE_TERMINAL_SNAPSHOT: ev=180 minPred=160 src=SCENARIO_MEAL_UPLIFT auth=true clamp=false plateauLift=false curves=true [pre_rbt]
            READ key=BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled value=false
        """.trimIndent()

        private val THERAPY_EXERCISE_LOCKOUT_TRACE = """
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=BooleanKey.OApsAIMIContextEnabled value=false
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            LOG 🏃 EXERCISE_LOCKOUT[therapy]: SMB off (sportTime=true aimiActivity=false) | basale autorisée seulement si BG>220 (T3c PI ou flux standard)
            READ key=BooleanKey.OApsAIMIMealAdvisorTrigger value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
            LOG 🏃 EXERCISE_LOCKOUT: flux standard interrompu → 0 U/h (BG=100)
            LOG DECISION_FINAL[EXERCISE_LOCKOUT]: smb=0.00U tbr=0.00U/h dur=0m bg=100 Δ=0.0 reason=🏃 Sport / contexte AIMI activité : basale & SMB arrêtés (BG≤220). | 
            LOG 📦 CACHE TDD24H=MISSING reason=tdd24h_missing
            LOG 🧭 BASAL_GOV[FINAL]: action=KEEP conf=0.00 n=0 hypo=0.00 hypoG=0.00 hypoAdj=0.00 ant=0.00 wMean=1.00 high=0.00 mae=0.0 latch=false floorB=- floorA=- wBolus=0.00U wCob=?g reason=trace
            LOG TICK ts=<clock> bg=100 d=0.0 iob=0.00 act=0.000 th=0.188 cob=0.0 mode=None autodriveState=IDLE pred=N(sz=0 ev=0) safety=NONE ref=NO maxIOB=0.00 maxSMB=0.00 smb=0.00->0.00->0.00 tbr=0.00 src=AIMI
            EFFECT SetTbr rate=0.00 dur=30 override=false forceExact=false adaptive=1.00
        """.trimIndent()

        private val UAM_POST_HYPO_REBOUND_TRACE = """
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            LOG 🛡️ POST_HYPO_REBOUND: SMB=0 → TBR bridge 1.05 U/h (0min depuis BG<70, COB=0.0g)
        """.trimIndent()

        private val CARBS_SMB_SAFETY_TRACE = """
            READ key=DoubleKey.meal_modes_MaxBasal value=0.00
            READ key=DoubleKey.autodriveMaxBasal value=0.00
            LOG phrase
            LOG 📦 CACHE TDD1D_SPARSE=MISSING reason=tdd_1day_sparse_missing
            LOG 📦 CACHE TIR65180_1D=MISSING reason=tir_1day_65180_missing
            READ key=BooleanKey.OApsAIMIhoneymoon value=false
        """.trimIndent()

        private val T3C_BASAL_FIRST_TRACE = """
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
            READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
            READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
            READ key=BooleanKey.OApsAIMIBasalChannelSafetyGuards value=false
            READ key=BooleanKey.OApsAIMIEffectiveIobReleaseEnabled value=false
            READ key=AimiLongKey.LastPrebolusTime value=0
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            LOG 🌳 T3C_NATIVE: ready rate=1.30U/h demand=2.00U/h
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
            READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
            READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
            READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
            READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
        """.trimIndent()

        private val RBT_MERGE_LIFT_TRACE = """
            LOG 🪜 RBT_GATE: req=HARD eff=HARD score=1.00 blend=1.00 reasons=LIFT
        """.trimIndent()

        private val PKPD_ABSORPTION_GUARD_TRACE = """
            READ key=BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled value=false
            READ key=DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor value=0.00
            LOG INTERVAL_ADJUSTED: +4m → 5m total
            LOG SMB_GUARDED: 2.00U → 1.00U
        """.trimIndent()

        private val SPORT_MEAL_SMB_TRACE = """
            READ key=BooleanKey.OApsAIMIhoneymoon value=false
            READ key=BooleanKey.OApsAIMIHyperDroppingExemptEnabled value=false
            READ key=BooleanKey.OApsAIMIhoneymoon value=false
            READ key=BooleanKey.OApsAIMIMealAdvisorTrigger value=false
            READ key=BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled value=false
            READ key=DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor value=0.00
        """.trimIndent()

        private val AUTOSENS_HR_TRACE = """
            LOG phrase
            LOG phrase
            READ key=BooleanKey.OApsAIMIEnableStepsFromWatch value=true
            LOG 💓 HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)
        """.trimIndent()

        private val LOW_PREDICTION_TBR_TRACE = """
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdHyperReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdStackAwareGuardB value=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            LOG PKPD_SOFT_FLOOR: raw=39 soft=39 hybT=39 hitFloor=true applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=AimiLongKey.LastPrebolusTime value=0
            READ key=DoubleKey.OApsAIMITDD7 value=0.00
            READ key=BooleanKey.OApsAIMIautoDriveActive value=false
            READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
            READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=DoubleKey.OApsAIMITDD7 value=0.00
            READ key=BooleanKey.OApsAIMIautoDriveActive value=false
            READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
            READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=BooleanKey.OApsAIMIContextEnabled value=false
            READ key=BooleanKey.OApsAIMISensorConfidenceCgmFirst value=false
            READ key=BooleanKey.OApsAIMIEffortActivityProtection value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
            READ key=StringKey.AimiTuningContextSelection value=
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=DoubleKey.autodriveMaxBasal value=0.00
            LOG TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
            LOG Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
            LOG MEAL_CERTAINTY level=NONE tree=NONE rise=WEAK terminals=HYPO_CONFLICT effortVeto=false
            LOG Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,low_or_falling_bg,max_iob_pressure,critical_risk
            LOG SCENARIO: floorT=39 bestT=43 floorMin=39 bestMin=39 gap=4 contrib=[PKPD_IOB_FLOOR,TARGET_BLEND]
            LOG PRED_PIPE: bg=100 delta=0.0 bestT=44 floorT=39 floorMin=39 min=44 th=70 noise=0.0 dataAge=1.0m pumpReachable=true sanity=ok
            LOG RISK_EARLY: compositeMin=39 hypoTh=70 predT=39 evT=43 pathRaw=n/a pathClamp=n/a
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=AimiLongKey.LastPrebolusTime value=0
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            LOG RISK_SAFETY_EARLY: compositeMin=39 predT=39 evT=39 bestT=43 floorT=39 mealRise=false suppressed=false
            LOG 🟠 SAFETY_LGS_TIER2 LGS_PRED_LOW: pred=39 <= Th=70 (BG actuel=100 OK) — Basale réduite 25%
            LOG SAFETY_APPLIED_TBR intent=0.25 haltPipeline=false
            EFFECT SetTbr rate=0.25 dur=30 override=true forceExact=false adaptive=1.00
        """.trimIndent()

        private val T3C_BYPASS_TRACE = """
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
            READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
            READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
            READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
            READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
            READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
            READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
            LOG ⚡ T3c Brittle Mode Active: Bypassing standard AIMI algorithm.
            READ key=AimiLongKey.LastPrebolusTime value=0
            READ key=BooleanKey.OApsAIMIT3cAutodriveBasalAuthority value=false
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=BooleanKey.OApsAIMISensorConfidenceCgmFirst value=false
            READ key=BooleanKey.OApsAIMIEffortActivityProtection value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=StringKey.AimiTuningContextSelection value=
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=DoubleKey.autodriveMaxBasal value=3.00
            LOG TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
            LOG Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
            LOG MEAL_CERTAINTY level=NONE tree=NONE rise=OK terminals=OK effortVeto=false
            LOG Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,critical_risk
            READ key=DoubleKey.OApsAIMIweight value=0.00
            LOG 👻 [T3c_SHADOW] DataLake tick fired for V3 ML continuity.
            READ key=AimiLongKey.LastPrebolusTime value=0
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=BooleanKey.OApsAIMIUndeclaredCobEnabled value=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdHyperReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdStackAwareGuardB value=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            LOG PKPD_SOFT_FLOOR: raw=180 soft=180 hybT=229 hitFloor=false applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled
            LOG PRED_SET size=49 eventual=229 min=180 uamT=229 source=AdvancedCurves
            LOG Prédiction avancée avec ISF final de 12.5 (Avancé)
            READ key=BooleanKey.OApsAIMITrajectoryGuardEnabled value=false
            LOG 🌀 Trajectory: ⏸ Disabled
            READ key=BooleanKey.OApsAIMIT3cCfrdMode value=false
            LOG 🛡️ T3c predict+traj: min=180 ev=229 LGS=70 traj=— E=—
            READ key=BooleanKey.OApsAIMIT3cAutodriveBasalAuthority value=false
            READ key=DoubleKey.OApsAIMIT3cActivationThreshold value=140.00
            READ key=DoubleKey.autodriveMaxBasal value=3.00
            READ key=DoubleKey.meal_modes_MaxBasal value=3.00
            READ key=BooleanKey.OApsAIMIT3cCfrdMode value=false
            READ key=BooleanKey.OApsAIMIT3cPhysioInformedEnabled value=false
            READ key=DoubleKey.OApsAIMIT3cAggressiveness value=1.00
            READ key=DoubleKey.OApsAIMIT3cAnticipationStrength value=0.00
            LOG T3C_AD_BASAL: pi=2.06 ad=— fused=2.06 unlock=false (tree_critical) cap=3.00 step=0.30 smbStripped=0.00
            READ key=BooleanKey.OApsAIMIT3cHyperBasalFloor value=false
            LOG 🧭 BASAL_GOV[T3C]: action=KEEP conf=0.00 n=0 hypo=0.00 hypoG=0.00 hypoAdj=0.00 ant=0.00 wMean=1.00 high=0.00 mae=0.0 latch=false floorB=- floorA=- wBolus=0.00U wCob=?g reason=trace
            LOG 🛡️T3c | Thresh: 140 | Agg: 0.3 (raw=0.0 AML=1.00) | ANT:0.00 | unlock=false | PI/AD: 1.30U/h (target=2.06 cap=3.00 stepUp=0.30)
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled value=false
            READ key=BooleanKey.OApsAIMIUnifiedReactivityEnabled value=false
            READ key=BooleanKey.OApsAIMIPkpdEnabled value=false
            READ key=BooleanKey.OApsAIMIautoDriveActive value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
            READ key=BooleanKey.OApsAIMIPeakGovernorEnabled value=false
            READ key=BooleanKey.OApsAIMIDiaGovernorEnabled value=false
        """.trimIndent()

        private val RBT_LIVE_TICK_TRACE = """
            READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
            READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
            READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
            READ key=BooleanKey.OApsAIMIautoDriveActive value=true
            READ key=BooleanKey.OApsAIMIHyperTrajectoryRelease value=true
            READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
            READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHighBg value=140.00
            READ key=DoubleKey.OApsAIMIHighBg value=140.00
            READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
            READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
            READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
            READ key=DoubleKey.OApsAIMIHighBg value=140.00
            READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIIobSurveillanceGuard value=false
            READ key=DoubleKey.OApsAIMIT3cAnticipationStrength value=0.00
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=IntKey.OApsAIMINightGrowthAgeYears value=0
            READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
            READ key=StringKey.OApsAIMINightGrowthStart value=
            READ key=StringKey.OApsAIMINightGrowthEnd value=
            READ key=DoubleKey.OApsAIMINightGrowthMaxIobExtra value=0.00
            READ key=BooleanKey.AimiEndometriosisEnable value=false
            LOG 😴 SLEEP_LIVE: wearable steps15=0 hr=72/rhr=60 conf=0.57 conf=0.57
            READ key=DoubleKey.OApsAIMISmbTailDamping value=0.00
            READ key=StringKey.AimiTuningContextSelection value=
            READ key=BooleanKey.OApsAIMIContextEnabled value=false
            READ key=DoubleKey.OApsAIMIHighBg value=140.00
            READ key=DoubleKey.OApsAIMIHighBGMaxSMB value=0.00
            READ key=DoubleKey.OApsAIMIMaxSMB value=0.00
            READ key=BooleanKey.OApsAIMISensorConfidenceCgmFirst value=false
            READ key=BooleanKey.OApsAIMIEffortActivityProtection value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
            READ key=StringKey.AimiTuningContextSelection value=
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=DoubleKey.autodriveMaxBasal value=0.00
            LOG TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
            LOG Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
            LOG MEAL_CERTAINTY level=NONE tree=NONE rise=OK terminals=OK effortVeto=false
            LOG Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,critical_risk
            LOG 🫀 PATIENT_MODE: mode=ABSORPTION_UNCERTAIN conf=0.95 strat=PKPD_REASSESS mealBias=0.30 protect=0.86 reasons=CAUSAL_ABSORPTION_UNCERTAIN
            READ key=DoubleKey.OApsAIMIHighBg value=140.00
            READ key=DoubleKey.OApsAIMIweight value=0.00
            LOG 🌳 RBT: auth=NONE smb=0.40U tbr×1.00 paradoxes=0 τ*=60 LG=FULL g=1.00shadow
            LOG 🚀 POST_HYPO_AGGRESSIVE_RISE_EXIT: bg=226 ≥ target+30 (130) Δ=20.0 > 15 → act normally
            READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
            READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
            READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
            LOG 🔌 RBT_WIRE: hypo=FULL meal=NORMAL auth=NONE chaos=0.10
            LOG 🪜 RBT_GATE: req=NONE eff=NONE score=0.21 blend=0.00 reasons=PREF_OFF
        """.trimIndent()

        private val SMB_ONESHOT_TRACE = """
            READ key=BooleanKey.OApsAIMIMealAdvisorTrigger value=true
            WRITE key=BooleanKey.OApsAIMIMealAdvisorTrigger value=false
            LOG 🚀 MEAL ADVISOR ONE-SHOT: Forcing Aggression. MaxSMB raised to 30U.
            LOG SMB Decision: BG=180, Delta=2.0, IOB=1.00, HasPred=true, HyperKicker=true, UAM=0.00, Proposed=0.00
            LOG AUTODRIVE_V3_AUTHORITATIVE: SMB 1.50 U from V3 (legacy blender skipped)
        """.trimIndent()

        private val TRAJECTORY_SMB_TRACE = """
            READ key=BooleanKey.OApsAIMITrajectoryGuardEnabled value=true
            LOG 🌀 Trajectory: ⭕ Stable orbit maintained | κ=0.00 conv=0.0 health=70%
            LOG     ●●●
            LOG    ●   ●  (orbit)
            LOG     ●●●
            LOG   📊 Metrics: Coherence=0.00 Energy=0.0U Openness=0.00
            LOG   🎛 Modulation: SMB×0.50 Int×1.00 (damped)
            LOG     → SMB: 2.00U → 1.00U
        """.trimIndent()

        private val SIGNAL_PREP_TRACE = """
            READ key=DoubleKey.OApsAIMIautodrivesmallPrebolus value=0.00
            READ key=DoubleKey.OApsAIMIautodrivePrebolus value=0.00
            LOG 📦 CACHE TDD24H_PKPD=MISSING reason=tdd24h_missing
            READ key=AimiLongKey.LastPrebolusTime value=0
            READ key=DoubleKey.OApsAIMIPkpdStateDiaH value=0.00
            READ key=DoubleKey.OApsAIMIweight value=0.00
            READ key=BooleanKey.OApsAIMIIntelligenceSingleLearnPath value=false
            LOG 📊 PKPD_LEARNER:
            LOG   │ DIA (learned): 5.00h
            LOG   │ Peak (learned): 75min
            LOG   │ fusedISF: 50.0 mg/dL/U
            LOG 🛡️ BASAL-FIRST ACTIVE: Fragile BG (<110 & falling) -> SMB DISABLED
            LOG   └ adaptiveMode: ACTIVE
        """.trimIndent()

        private val PKPD_TARGET_TRACE = """
            LOG Debug: computePkpdPredictions called with delta=2.0
            LOG PKPD_PRED_MOD: src=fallback sens=0.00 ins=1.00 carb=1.00 uam=1.00 hyb=0.96 decay=1.03 meal=0.00 nonMeal=0.00 suppress=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdHyperReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdStackAwareGuardB value=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            LOG PKPD_SOFT_FLOOR: raw=180 soft=180 hybT=195 hitFloor=false applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled
            LOG PKPD predictions → eventual=195 mg/dL from 49 steps uamT=196 pathMinRaw=180 pathMinClamp=180
            LOG PRED_DIVERGENCE: bg=180 evPkpd=195 bestScn=- Δ=- phase=- meal=- clampPkpd=false clampScn=-
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIPredictionAuthorityShadow value=false
            READ key=BooleanKey.OApsAIMIMealConfirmedEarlyRelease value=false
            LOG PRED_AUTHORITY: src=PKPD_ONLY predT=195 evT=195 pkpd=195 best=- mealSupp=false uplift=false no_scenario_projection [late_pkpd]
            LOG DOSE_TERMINAL_SNAPSHOT: ev=195 minPred=180 src=PKPD_RAW auth=false clamp=false plateauLift=false curves=false [late_pkpd]
            READ key=BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled value=false
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=AimiLongKey.LastPrebolusTime value=0
            LOG RISK_DECISION: compositeMin=180 hypoTh=110 predT=195 evT=195 pathRaw=180 pathClamp=180 iob=1.00→1.00(AAPS_DEFAULT) src=PKPD_ONLY pkpd=195
            LOG phrase
            LOG phrase
        """.trimIndent()

        private val HARMONIA_RAMP_TRACE = """
            READ key=BooleanKey.OApsAIMIBasalChannelSafetyGuards value=false
            READ key=BooleanKey.OApsAIMIEffectiveIobReleaseEnabled value=false
            READ key=AimiLongKey.LastPrebolusTime value=0
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            LOG 🌿 HARMONIA_PROD: ready action=BASAL_FIRST rate=1.30U/h requested=2.00U/h
        """.trimIndent()

        private val MAX_IOB_TBR_TRACE = """
            EFFECT SetTbr rate=2.00 dur=30 override=false forceExact=false adaptive=1.00
            LOG DECISION_FINAL[MAX_IOB]: smb=0.00U tbr=0.00U/h dur=0m bg=160 Δ=2.0 reason=phrasephrase
            LOG 📦 CACHE TDD24H=MISSING reason=tdd24h_missing
            LOG 🧭 BASAL_GOV[FINAL]: action=KEEP conf=0.00 n=0 hypo=0.00 hypoG=0.00 hypoAdj=0.00 ant=0.00 wMean=1.00 high=0.00 mae=0.0 latch=false floorB=- floorA=- wBolus=0.00U wCob=?g reason=trace
            LOG TICK ts=<clock> bg=160 d=2.0 iob=0.00 act=0.000 th=0.188 cob=0.0 mode=None autodriveState=IDLE pred=N(sz=0 ev=0) safety=NONE ref=NO maxIOB=0.00 maxSMB=0.50 smb=0.00->0.00->0.00 tbr=0.00 src=AIMI
        """.trimIndent()

        private val INSULIN_REQ_ACTIVITY_TRACE = """
            LOG SMB capped by Activity/Recovery (Limit: 0.50)
        """.trimIndent()

        private val MEAL_FIRST_30_TRACE = """
            EFFECT SetTbr rate=2.00 dur=30 override=true forceExact=false adaptive=1.00
        """.trimIndent()

        private val BASAL_TDD_PAI_TRACE = """
            READ key=DoubleKey.OApsAIMIweight value=70.00
            READ key=DoubleKey.OApsAIMICHO value=15.00
            READ key=DoubleKey.OApsAIMICHO value=15.00
            READ key=BooleanKey.OApsAIMIpregnancy value=true
            READ key=BooleanKey.OApsAIMIUnifiedReactivityEnabled value=false
            LOG Basal boosté (+20%) pour accélération BG.
            LOG PAI Logic: Base ISF=50.0
            LOG PAI: BG rising & IOB badly timed. AGGRESSIVE.
            LOG PAI: Urgency factor 0.60 applied. New ISF=30.0
        """.trimIndent()

        private val BASAL_FIRST_REDUCTION_TRACE = """
            READ key=BooleanKey.OApsAIMIBasalChannelSafetyGuards value=true
            LOG 🛡️ BASAL_FIRST_GOV: adaptiveMult conservé à 0.70x (legacy forçait 1.00x)
        """.trimIndent()

        private val RBT_REFINE_ACTIVE_TRACE = """
            READ key=BooleanKey.OApsAIMIIobSurveillanceGuard value=true
            LOG 🪜 RBT_GATE: req=HARD eff=HARD score=0.80 blend=1.00 reasons=READY
            LOG RBT_REFINE_AFTER_DOSE_SNAPSHOT: 1.20→0.38U ev=160 minPred=150
        """.trimIndent()

        private val DECISION_CONTEXT_RISE_TRACE = """
            READ key=BooleanKey.OApsAIMIAuditorProfileFactors value=false
            READ key=BooleanKey.AimiAuditorEnabled value=false
            LOG ═══════════════════════════════
            LOG 🛡️ AIMI LEARNERS HEALTH
            LOG Storage: ok
            LOG UnifiedReactivity: factor=1.000
            LOG BasalLearner: multiplier=1.000
            LOG PkPdEstimator: runtime-only
            LOG ═══════════════════════════════
            LOG ⚠️ FLAT OVERRIDE: Delta=6.0 > 3.0 -> Sensor ALIVE.
        """.trimIndent()

        private val T9_ACTIVE_TRACE = """
            READ key=BooleanKey.AimiPhysioAssistantEnable value=true
            LOG 🏥 PHYSIO: ISF×1.100 Basal×1.050 SMB×1.080 Conf=80%
            READ key=BooleanKey.OApsAIMIIntelligenceSingleLearnPath value=false
            READ key=DoubleKey.OApsAIMIPkpdStateDiaH value=5.00
            READ key=DoubleKey.OApsAIMIweight value=70.00
            READ key=BooleanKey.OApsAIMIPkpdEnabled value=true
            READ key=DoubleKey.OApsAIMIPkpdBoundsDiaMinH value=3.00
            READ key=DoubleKey.OApsAIMIPkpdBoundsDiaMaxH value=8.00
            READ key=DoubleKey.OApsAIMIPkpdBoundsPeakMinMin value=30.00
            READ key=DoubleKey.OApsAIMIPkpdBoundsPeakMinMax value=180.00
            READ key=DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH value=0.50
            READ key=DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin value=10.00
            READ key=DoubleKey.OApsAIMIIsfFusionMinFactor value=0.70
            READ key=DoubleKey.OApsAIMIIsfFusionMaxFactor value=1.30
            READ key=DoubleKey.OApsAIMIIsfFusionMaxChangePerTick value=0.05
            READ key=DoubleKey.OApsAIMISmbTailDamping value=0.50
            READ key=DoubleKey.OApsAIMISmbTailThreshold value=0.25
            READ key=DoubleKey.OApsAIMISmbExerciseDamping value=0.60
            READ key=DoubleKey.OApsAIMISmbLateFatDamping value=0.70
            READ key=DoubleKey.OApsAIMIPkpdAnchorDiaH value=5.00
            READ key=DoubleKey.OApsAIMIPkpdAnchorPeakMin value=75.00
            READ key=LongNonKey.OApsAIMIPkpdLearnedStateGeneration value=0
            READ key=DoubleKey.OApsAIMIPkpdStateDiaH value=5.00
            READ key=DoubleKey.OApsAIMIPkpdStatePeakMin value=75.00
            LOG PKPD_FAMILY: prot=1 meal=1 stab=1 phys=1 auto=0 mealF=1.00 physBlend=0.73
            LOG Debug: computePkpdPredictions called with delta=4.0
            LOG PKPD_PRED_MOD: src=runtime sens=73.50 ins=1.07 carb=1.00 uam=1.00 hyb=0.96 decay=1.03 meal=0.00 nonMeal=0.00 suppress=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdHyperReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdStackAwareGuardB value=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            LOG PKPD_SOFT_FLOOR: raw=160 soft=160 hybT=227 hitFloor=false applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled
            LOG PKPD predictions → eventual=228 mg/dL from 49 steps uamT=231 pathMinRaw=160 pathMinClamp=160
            LOG 🏥 PHYSIO APPLIED: MaxSMB=1.08 MaxBasal=1.00
            READ key=BooleanKey.OApsAIMIDiaGovernorEnabled value=false
            READ key=AimiStringKey.OApsAIMIPkpdLastPeakGovLogLine value=
        """.trimIndent()

        private val TICK_CLOCK_RISE_TRACE = """
            READ key=BooleanKey.OApsAIMIhoneymoon value=false
            READ key=AimiLongKey.LastPrebolusTime value=0
            READ key=AimiLongKey.PendingLegacyPrebolusUnitMilli value=0
            READ key=AimiLongKey.PendingLegacyPrebolusUnitMilli value=0
            READ key=DoubleKey.ApsSmbMaxIob value=10.00
            LOG MAX_IOB_STATIC: Pref=10.0 (Dynamic disabled by request)
            READ key=DoubleKey.OApsAIMIMaxSMB value=0.50
            READ key=DoubleKey.OApsAIMIHighBGMaxSMB value=1.20
            READ key=DoubleKey.OApsAIMIHighBGMaxSMB value=1.20
            LOG MAXSMB_SLOPE_HIGH BG=180 slope=2.00 Δ=3.0 -> maxSMBHB=1.20U (confirmed rise)
            READ key=DoubleKey.OApsAIMIMaxSMB value=0.50
            READ key=IntKey.OApsAIMINightGrowthAgeYears value=0
            READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
            READ key=StringKey.OApsAIMINightGrowthStart value=
            READ key=StringKey.OApsAIMINightGrowthEnd value=
            READ key=DoubleKey.OApsAIMINightGrowthMaxIobExtra value=0.00
        """.trimIndent()

        private val PKPD_GUARD_ACTIVE_TRACE = """
            READ key=BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled value=false
            READ key=DoubleKey.OApsAIMIRedCarpetRestoreThreshold value=0.00
            READ key=DoubleKey.OApsAIMIPriorityMaxIobFactor value=0.00
            READ key=DoubleKey.OApsAIMIPriorityMaxIobExtraU value=0.00
            READ key=BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled value=false
            READ key=DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor value=0.00
            LOG INTERVAL_ADJUSTED: +2m → 3m total
            LOG SMB_GUARDED: 2.00U → 1.60U
            LOG SMB_ENDO_DAMPEN: 1.60U → 0.80U (x0.50)
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIIobSurveillanceGuard value=false
            LOG ✨ RED CARPET: Restoring meal bolus blocked by minor safety (Proposed=2.00 vs Gated=0.80)
            LOG 🍱 MEAL_FORCE_EXECUTED (MealMode): 2.00 U (Overrides minor safety checks)
        """.trimIndent()

        private val PKPD_GUARD_FLAT_TRACE = """
            READ key=BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled value=false
            READ key=DoubleKey.OApsAIMIRedCarpetRestoreThreshold value=0.00
            READ key=DoubleKey.OApsAIMIPriorityMaxIobFactor value=0.00
            READ key=DoubleKey.OApsAIMIPriorityMaxIobExtraU value=0.00
            READ key=BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled value=false
            READ key=DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor value=0.00
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIIobSurveillanceGuard value=false
        """.trimIndent()

        private val T9_NEUTRAL_TRACE = """
            READ key=BooleanKey.AimiPhysioAssistantEnable value=false
            READ key=BooleanKey.OApsAIMIIntelligenceSingleLearnPath value=false
            READ key=DoubleKey.OApsAIMIPkpdStateDiaH value=0.00
            READ key=DoubleKey.OApsAIMIweight value=0.00
            READ key=BooleanKey.OApsAIMIPkpdEnabled value=false
            READ key=DoubleKey.OApsAIMIPkpdBoundsDiaMinH value=0.00
            READ key=DoubleKey.OApsAIMIPkpdBoundsDiaMaxH value=0.00
            READ key=DoubleKey.OApsAIMIPkpdBoundsPeakMinMin value=0.00
            READ key=DoubleKey.OApsAIMIPkpdBoundsPeakMinMax value=0.00
            READ key=DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH value=0.00
            READ key=DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin value=0.00
            READ key=DoubleKey.OApsAIMIIsfFusionMinFactor value=0.00
            READ key=DoubleKey.OApsAIMIIsfFusionMaxFactor value=0.00
            READ key=DoubleKey.OApsAIMIIsfFusionMaxChangePerTick value=0.00
            READ key=DoubleKey.OApsAIMISmbTailDamping value=0.00
            READ key=DoubleKey.OApsAIMISmbTailThreshold value=0.00
            READ key=DoubleKey.OApsAIMISmbExerciseDamping value=0.00
            READ key=DoubleKey.OApsAIMISmbLateFatDamping value=0.00
            READ key=DoubleKey.OApsAIMIPkpdAnchorDiaH value=0.00
            READ key=DoubleKey.OApsAIMIPkpdAnchorPeakMin value=0.00
            READ key=LongNonKey.OApsAIMIPkpdLearnedStateGeneration value=0
            LOG PKPD Debug: Config ENABLED is FALSE. Check OApsAIMIPkpdEnabled preference.
            LOG Debug: computePkpdPredictions called with delta=0.0
            LOG PKPD_PRED_MOD: src=fallback sens=50.00 ins=1.00 carb=1.00 uam=1.00 hyb=0.96 decay=1.03 meal=0.00 nonMeal=0.00 suppress=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdHyperReversion value=false
            READ key=BooleanKey.OApsAIMIPkpdStackAwareGuardB value=false
            READ key=BooleanKey.OApsAIMIPkpdEndogenousReversion value=false
            LOG PKPD_SOFT_FLOOR: raw=110 soft=110 hybT=110 hitFloor=false applied=false endo=false fallSuppressed=false reason=endo_reversion_disabled
            LOG PKPD predictions → eventual=110 mg/dL from 49 steps uamT=110 pathMinRaw=110 pathMinClamp=110
            READ key=BooleanKey.OApsAIMIDiaGovernorEnabled value=false
            READ key=AimiStringKey.OApsAIMIPkpdLastPeakGovLogLine value=
        """.trimIndent()

        private val TICK_CLOCK_TRACE = """
            READ key=BooleanKey.OApsAIMIhoneymoon value=false
            READ key=AimiLongKey.LastPrebolusTime value=0
            READ key=AimiLongKey.PendingLegacyPrebolusUnitMilli value=0
            READ key=AimiLongKey.PendingLegacyPrebolusUnitMilli value=0
            READ key=DoubleKey.ApsSmbMaxIob value=10.00
            LOG MAX_IOB_STATIC: Pref=10.0 (Dynamic disabled by request)
            READ key=DoubleKey.OApsAIMIMaxSMB value=0.50
            READ key=DoubleKey.OApsAIMIHighBGMaxSMB value=1.20
            READ key=DoubleKey.OApsAIMIHighBGMaxSMB value=1.20
            LOG MAXSMB_STANDARD BG=110 -> 0.50U
            READ key=DoubleKey.OApsAIMIMaxSMB value=0.50
            READ key=IntKey.OApsAIMINightGrowthAgeYears value=0
            READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
            READ key=StringKey.OApsAIMINightGrowthStart value=
            READ key=StringKey.OApsAIMINightGrowthEnd value=
            READ key=DoubleKey.OApsAIMINightGrowthMaxIobExtra value=0.00
        """.trimIndent()

        private val MEAL_HYPER_FASTING_TRACE = "READ key=DoubleKey.meal_modes_MaxBasal value=0.00"

        private val RBT_ACTIVE_HTR_TRACE = """
            READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=true
            READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
            READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
            READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
            READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
            READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=BooleanKey.OApsAIMIIobSurveillanceGuard value=false
            READ key=DoubleKey.OApsAIMIT3cAnticipationStrength value=0.00
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=IntKey.OApsAIMINightGrowthAgeYears value=0
            READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
            READ key=StringKey.OApsAIMINightGrowthStart value=
            READ key=StringKey.OApsAIMINightGrowthEnd value=
            READ key=DoubleKey.OApsAIMINightGrowthMaxIobExtra value=0.00
            READ key=BooleanKey.AimiEndometriosisEnable value=false
            READ key=DoubleKey.OApsAIMISmbTailDamping value=0.00
            READ key=StringKey.AimiTuningContextSelection value=
            READ key=BooleanKey.OApsAIMIContextEnabled value=false
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=DoubleKey.OApsAIMIHighBGMaxSMB value=2.00
            READ key=DoubleKey.OApsAIMIMaxSMB value=2.00
            READ key=BooleanKey.OApsAIMISensorConfidenceCgmFirst value=false
            READ key=BooleanKey.OApsAIMIEffortActivityProtection value=false
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
            READ key=StringKey.AimiTuningContextSelection value=
            READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
            READ key=DoubleKey.autodriveMaxBasal value=0.00
            LOG TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
            LOG Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
            LOG MEAL_CERTAINTY level=NONE tree=NONE rise=OK terminals=OK effortVeto=false
            LOG Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,critical_risk
            LOG 🫀 PATIENT_MODE: mode=ABSORPTION_UNCERTAIN conf=0.94 strat=PKPD_REASSESS mealBias=0.30 protect=0.86 reasons=CAUSAL_ABSORPTION_UNCERTAIN
            READ key=DoubleKey.OApsAIMIHighBg value=0.00
            READ key=DoubleKey.OApsAIMIweight value=0.00
        """.trimIndent()

        private val RBT_RESOLVE_TRACE = """
READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=true
READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=BooleanKey.OApsAIMIIobSurveillanceGuard value=false
READ key=DoubleKey.OApsAIMIT3cAnticipationStrength value=0.00
READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
READ key=IntKey.OApsAIMINightGrowthAgeYears value=0
READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
READ key=StringKey.OApsAIMINightGrowthStart value=
READ key=StringKey.OApsAIMINightGrowthEnd value=
READ key=DoubleKey.OApsAIMINightGrowthMaxIobExtra value=0.00
READ key=BooleanKey.AimiEndometriosisEnable value=false
LOG 😴 SLEEP_LIVE: wearable steps15=0 hr=72/rhr=60 conf=0.57 conf=0.57
READ key=DoubleKey.OApsAIMISmbTailDamping value=0.00
READ key=StringKey.AimiTuningContextSelection value=
READ key=BooleanKey.OApsAIMIContextEnabled value=false
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMIHighBGMaxSMB value=0.00
READ key=DoubleKey.OApsAIMIMaxSMB value=0.00
READ key=BooleanKey.OApsAIMISensorConfidenceCgmFirst value=false
READ key=BooleanKey.OApsAIMIEffortActivityProtection value=false
READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
READ key=StringKey.AimiTuningContextSelection value=
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=DoubleKey.autodriveMaxBasal value=0.00
LOG TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
LOG Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
LOG MEAL_CERTAINTY level=NONE tree=NONE rise=OK terminals=OK effortVeto=false
LOG Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,critical_risk
LOG 🫀 PATIENT_MODE: mode=ABSORPTION_UNCERTAIN conf=0.95 strat=PKPD_REASSESS mealBias=0.30 protect=0.86 reasons=CAUSAL_ABSORPTION_UNCERTAIN
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMIweight value=0.00
""".trimIndent()

        private val FINALIZE_CAP_TRACE = """
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=BooleanKey.OApsAIMIhoneymoon value=false
READ key=BooleanKey.OApsAIMIHyperDroppingExemptEnabled value=false
READ key=BooleanKey.OApsAIMIhoneymoon value=false
READ key=BooleanKey.OApsAIMIMealAdvisorTrigger value=false
READ key=BooleanKey.OApsAIMIPkpdPragmaticReliefEnabled value=false
READ key=DoubleKey.OApsAIMIPkpdPragmaticReliefMinFactor value=0.00
LOG Safety Precautions reduced SMB: 3.0 -> 0.5 (BaseLimit=0.50)
READ key=BooleanKey.OApsAIMIhoneymoon value=false
READ key=BooleanKey.OApsAIMInight value=false
READ key=IntKey.OApsAIMISnackinterval value=0
READ key=IntKey.OApsAIMImealinterval value=0
READ key=IntKey.OApsAIMIBFinterval value=0
READ key=IntKey.OApsAIMILunchinterval value=0
READ key=IntKey.OApsAIMIDinnerinterval value=0
READ key=IntKey.OApsAIMISleepinterval value=0
READ key=IntKey.OApsAIMIHCinterval value=0
READ key=IntKey.OApsAIMIHighBGinterval value=0
LOG 📦 CACHE TDD24H=MISSING reason=tdd24h_missing
READ key=BooleanKey.OApsAIMIRiseCeilingGuard value=false
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=BooleanKey.OApsAIMIIobSurveillanceGuard value=false
LOG PKPD_THROTTLE smbFactor=0.60 intervalAdd=3 preferTbr=true reason=Onset unconfirmed, rising BG → TBR priority
WRITE key=AimiLongKey.LastPrebolusTime value=1700000000000
LOG GATE_REFRACTORY sinceLastBolus=999.0m window=5.0
LOG GATE_MAXIOB allowed=10.00 current=1.00
LOG GATE_MAXSMB cap=0.50 proposed=3.00
LOG GATE_ABSORPTION activity=0.000 threshold=0.188 factor=1.00
LOG GATE_PRED_MISSING fallback=ON
LOG SMB_CAP: Proposed=3.0 Allowed=0.15 Reason=micro
LOG   -> Limits: MaxSMB=0.5 MaxIOB=10.0 IOB=1.0
READ key=BooleanKey.OApsAIMIIobSurveillanceGuard value=false
""".trimIndent()

        private val MEAL_ADVISOR_TRACE = """
READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=40.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=<clock>
READ key=DoubleKey.meal_modes_MaxBasal value=2.00
LOG ADVISOR_CALC carbs=40g IC=10.0 → 4.00U
LOG ADVISOR_CALC IOB_raw=1.00U × discount=0.7 → IOB_effective=0.70U
LOG ADVISOR_CALC minimumGuaranteed=1.00U (25% of carb need)
LOG ADVISOR_CALC calculated=3.30U → netSMB=3.30U (max of calculated and minimum)
LOG ADVISOR_CALC TBR=2.0U/h (will deliver 1.00U over 30min as complement)
LOG ADVISOR_CALC TOTAL delivery: SMB 3.30U + TBR 1.00U = 4.30U delta=2.0 modesOK=true
LOG MEAL_ADVISOR_APPLIED source=MealAdvisor bolus=3.3
EFFECT SetTbr rate=2.00 dur=30 override=true forceExact=false adaptive=1.00
EFFECT Smb units=3.30 owner=MealAdvisor
LOG 🍱 MEAL_ADVISOR_DIRECT_SEND (Auto) Pushed=3.30U (Limits Bypassed)
WRITE key=AimiLongKey.LastPrebolusTime value=1700000000000
LOG DECISION_FINAL[MEAL_ADVISOR]: smb=0.00U tbr=0.00U/h dur=0m bg=160 Δ=2.0 reason=📸 Meal Advisor: 40g -> 3.30U + TBR 2.0U/hphrase | 
LOG 📦 CACHE TDD24H=MISSING reason=tdd24h_missing
LOG 🧭 BASAL_GOV[FINAL]: action=KEEP conf=0.00 n=0 hypo=0.00 hypoG=0.00 hypoAdj=0.00 ant=0.00 wMean=1.00 high=0.00 mae=0.0 latch=false floorB=- floorA=- wBolus=3.30U wCob=?g reason=trace
LOG TICK ts=<clock> bg=160 d=2.0 iob=0.00 act=0.000 th=0.188 cob=0.0 mode=None autodriveState=IDLE pred=N(sz=0 ev=0) safety=NONE ref=NO maxIOB=0.00 maxSMB=0.50 smb=0.00->3.30->3.30 tbr=0.00 src=AIMI
READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
READ key=BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled value=false
READ key=BooleanKey.OApsAIMIUnifiedReactivityEnabled value=false
READ key=BooleanKey.OApsAIMIPkpdEnabled value=false
READ key=BooleanKey.OApsAIMIautoDriveActive value=false
READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
READ key=BooleanKey.OApsAIMIPeakGovernorEnabled value=false
READ key=BooleanKey.OApsAIMIDiaGovernorEnabled value=false
""".trimIndent()

        private val BRITTLE_ACTIVE_TRACE = """
READ key=DoubleKey.OApsAIMIT3cActivationThreshold value=140.00
READ key=DoubleKey.autodriveMaxBasal value=3.00
READ key=DoubleKey.meal_modes_MaxBasal value=3.00
READ key=BooleanKey.OApsAIMIT3cCfrdMode value=false
READ key=BooleanKey.OApsAIMIT3cPhysioInformedEnabled value=false
READ key=DoubleKey.OApsAIMIT3cAggressiveness value=1.00
READ key=DoubleKey.OApsAIMIT3cAnticipationStrength value=0.00
LOG T3C_AD_BASAL: pi=3.00 ad=— fused=3.00 unlock=true (rise_no_tree) cap=3.00 step=1.00 smbStripped=0.00
READ key=BooleanKey.OApsAIMIT3cHyperBasalFloor value=false
LOG 🧭 BASAL_GOV[T3C]: action=KEEP conf=0.00 n=0 hypo=0.00 hypoG=0.00 hypoAdj=0.00 ant=0.00 wMean=1.00 high=0.00 mae=0.0 latch=false floorB=- floorA=- wBolus=0.00U wCob=?g reason=trace
LOG 🛡️T3c | Thresh: 140 | Agg: 0.3 (raw=0.0 AML=1.00) | ANT:0.00 | unlock=true | PI/AD: 2.00U/h (target=3.00 cap=3.00 stepUp=1.00)
READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
READ key=BooleanKey.OApsAIMIT3cAdaptiveBasalEnabled value=false
READ key=BooleanKey.OApsAIMIUnifiedReactivityEnabled value=false
READ key=BooleanKey.OApsAIMIPkpdEnabled value=false
READ key=BooleanKey.OApsAIMIautoDriveActive value=false
READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
READ key=BooleanKey.OApsAIMIPeakGovernorEnabled value=false
READ key=BooleanKey.OApsAIMIDiaGovernorEnabled value=false
""".trimIndent()

        private val HYPO_MEAL_ENGINE_TRACE = """
READ key=BooleanKey.OApsAIMIautoDriveActive value=true
READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
READ key=DoubleKey.OApsAIMITDD7 value=0.00
READ key=BooleanKey.OApsAIMIautoDriveActive value=true
READ key=BooleanKey.OApsAIMIHyperTrajectoryRelease value=false
READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
READ key=AimiLongKey.LastPrebolusTime value=0
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=BooleanKey.OApsAIMISensorConfidenceCgmFirst value=false
READ key=BooleanKey.OApsAIMIEffortActivityProtection value=false
READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
READ key=StringKey.AimiTuningContextSelection value=
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=DoubleKey.autodriveMaxBasal value=0.00
LOG TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
LOG Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
LOG MEAL_CERTAINTY level=NONE tree=NONE rise=WEAK terminals=UNKNOWN effortVeto=false
LOG Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,critical_risk
READ key=BooleanKey.OApsAIMIautoDriveActive value=true
READ key=BooleanKey.OApsAIMIHyperTrajectoryRelease value=false
READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMIweight value=70.00
LOG 🧠 ATTN_MASK: auto=0.17 inflam=0.00 hormonal=0.00
LOG 🧠 LATENT: meal=0.00 endo=0.00 siCirc=1.00 resist=0.12 sleep=0.00 sensor=0.11
EFFECT SetTbr rate=2.40 dur=30 override=true forceExact=false adaptive=1.00
READ key=BooleanKey.OApsAIMIautodriveAggressiveSmbFloor value=false
READ key=DoubleKey.OApsAIMIautodrivesmallPrebolus value=0.50
READ key=DoubleKey.OApsAIMIautodrivePrebolus value=1.50
READ key=StringKey.AimiTuningContextSelection value=
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=DoubleKey.autodriveMaxBasal value=0.00
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=BooleanKey.OApsAIMIPredictionAuthorityShadow value=false
READ key=BooleanKey.OApsAIMIMealConfirmedEarlyRelease value=false
LOG PRED_AUTHORITY: src=PKPD_ONLY predT=54 evT=54 pkpd=54 best=- mealSupp=true uplift=false no_scenario_projection [pre_v3_rbt]
LOG DOSE_TERMINAL_SNAPSHOT: ev=54 minPred=54 src=PKPD_RAW auth=false clamp=false plateauLift=false curves=false [pre_v3_rbt]
READ key=BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled value=false
READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=false
READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
LOG 🚀 🚀 V3 ENGAGED [Meal-aware rise] (BG=54.0, Trend=0.4000000059604645, COB=0.0, UAM=0.0) intent=0.8 actual=0.0 tbr=2.4
LOG DECISION_FINAL[AUTODRIVE_V3]: smb=0.00U tbr=0.00U/h dur=0m bg=54 Δ=0.4 reason=
LOG 📦 CACHE TDD24H=MISSING reason=tdd24h_not_ready
LOG 🧭 BASAL_GOV[FINAL]: action=KEEP conf=0.00 n=0 hypo=0.00 hypoG=0.00 hypoAdj=0.00 ant=0.00 wMean=1.00 high=0.00 mae=0.0 latch=false floorB=- floorA=- wBolus=0.00U wCob=?g reason=trace
LOG TICK ts=<clock> bg=54 d=0.4 iob=1.00 act=0.000 th=0.188 cob=0.0 mode=Meal autodriveState=ENGAGED pred=N(sz=0 ev=54) safety=NONE ref=NO maxIOB=10.00 maxSMB=2.00 smb=0.00->0.00->0.00 tbr=0.00 src=AIMI
""".trimIndent()

        private val GATE_CLOSED_TRACE = """
READ key=BooleanKey.OApsAIMIautoDriveActive value=true
READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
READ key=DoubleKey.OApsAIMIweight value=70.00
LOG 🍽️ RA_OBSERVE[gate_disengaged]: Ra=0.40
""".trimIndent()

        private val MEAL_RISE_TRACE = """
READ key=BooleanKey.OApsAIMIautoDriveActive value=true
READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
READ key=DoubleKey.OApsAIMITDD7 value=0.00
READ key=BooleanKey.OApsAIMIautoDriveActive value=true
READ key=BooleanKey.OApsAIMIHyperTrajectoryRelease value=false
READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
READ key=AimiLongKey.LastPrebolusTime value=0
READ key=DoubleKey.OApsAIMIHighBg value=0.00
LOG 🍽️ MEAL_ABSORPTION: FIRST_WAVE B=0.72 pri=true waves=1 (FIRST_WAVE B=0.72 π=0.85 K=0.50 T=0.00 P=0.35)
READ key=BooleanKey.OApsAIMISensorConfidenceCgmFirst value=false
READ key=BooleanKey.OApsAIMIEffortActivityProtection value=false
READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
READ key=StringKey.AimiTuningContextSelection value=
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=DoubleKey.autodriveMaxBasal value=0.00
LOG TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
LOG Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
LOG MEAL_CERTAINTY level=MED tree=NONE rise=OK terminals=UNKNOWN effortVeto=false
LOG Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,critical_risk
READ key=BooleanKey.OApsAIMIautoDriveActive value=true
READ key=BooleanKey.OApsAIMIHyperTrajectoryRelease value=false
READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMIweight value=70.00
LOG 🧠 ATTN_MASK: auto=0.17 inflam=0.00 hormonal=0.00
LOG 🧠 LATENT: meal=0.92 endo=0.00 siCirc=1.00 resist=0.12 sleep=0.00 sensor=0.11
EFFECT SetTbr rate=2.40 dur=30 override=true forceExact=false adaptive=1.00
READ key=BooleanKey.OApsAIMIautodriveAggressiveSmbFloor value=false
READ key=DoubleKey.OApsAIMIautodrivesmallPrebolus value=0.50
READ key=DoubleKey.OApsAIMIautodrivePrebolus value=1.50
READ key=StringKey.AimiTuningContextSelection value=
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=DoubleKey.autodriveMaxBasal value=0.00
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=BooleanKey.OApsAIMIPredictionAuthorityShadow value=false
READ key=BooleanKey.OApsAIMIMealConfirmedEarlyRelease value=false
LOG PRED_AUTHORITY: src=PKPD_ONLY predT=160 evT=160 pkpd=160 best=- mealSupp=false uplift=false no_scenario_projection [pre_v3_rbt]
LOG DOSE_TERMINAL_SNAPSHOT: ev=160 minPred=160 src=PKPD_RAW auth=false clamp=false plateauLift=false curves=false [pre_v3_rbt]
READ key=BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled value=false
READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=false
READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
LOG 🚀 🚀 V3 ENGAGED [Meal-aware rise] (BG=160.0, Trend=3.0, COB=0.0, UAM=0.0) intent=0.8 actual=0.0 tbr=2.4
LOG DECISION_FINAL[AUTODRIVE_V3]: smb=0.00U tbr=0.00U/h dur=0m bg=160 Δ=3.0 reason=
LOG 📦 CACHE TDD24H=MISSING reason=tdd24h_not_ready
LOG 🧭 BASAL_GOV[FINAL]: action=KEEP conf=0.00 n=0 hypo=0.00 hypoG=0.00 hypoAdj=0.00 ant=0.00 wMean=1.00 high=0.00 mae=0.0 latch=false floorB=- floorA=- wBolus=0.00U wCob=?g reason=trace
LOG TICK ts=<clock> bg=160 d=3.0 iob=1.00 act=0.000 th=0.188 cob=0.0 mode=Meal autodriveState=ENGAGED pred=N(sz=0 ev=160) safety=NONE ref=NO maxIOB=10.00 maxSMB=2.00 smb=0.00->0.00->0.00 tbr=0.00 src=AIMI
""".trimIndent()

        private val ACTIVITY_CEILING_TRACE = """
READ key=BooleanKey.OApsAIMIautoDriveActive value=true
READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
READ key=DoubleKey.OApsAIMITDD7 value=0.00
READ key=BooleanKey.OApsAIMIautoDriveActive value=true
READ key=BooleanKey.OApsAIMIHyperTrajectoryRelease value=false
READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=0.00
READ key=AimiLongKey.LastPrebolusTime value=0
READ key=DoubleKey.OApsAIMIHighBg value=0.00
LOG 🍽️ MEAL_ABSORPTION: FIRST_WAVE B=0.72 pri=true waves=1 (FIRST_WAVE B=0.72 π=0.85 K=0.50 T=0.00 P=0.35)
READ key=BooleanKey.OApsAIMISensorConfidenceCgmFirst value=false
READ key=BooleanKey.OApsAIMIEffortActivityProtection value=false
READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
READ key=StringKey.AimiTuningContextSelection value=
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=DoubleKey.autodriveMaxBasal value=0.00
LOG TREE_DEPLOYED trunk=SENSOR_UNCERTAIN conf=0.90 risk=CRITICAL kinetics=NO_STAGE
LOG Tree: sensor uncertain | conf 90% | risk critical | sensor uncertain
LOG MEAL_CERTAINTY level=MED tree=NONE rise=OK terminals=UNKNOWN effortVeto=false
LOG Harmonia sim: blocked SENSOR_UNCERTAIN | sensor_uncertain,critical_risk
READ key=BooleanKey.OApsAIMIautoDriveActive value=true
READ key=BooleanKey.OApsAIMIHyperTrajectoryRelease value=false
READ key=BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive value=false
READ key=DoubleKey.OApsAIMIHyperEstablishedDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHyperDeepDevMgdl value=0.00
READ key=DoubleKey.OApsAIMIHighBg value=0.00
READ key=DoubleKey.OApsAIMIweight value=70.00
LOG 🧠 ATTN_MASK: auto=0.17 inflam=0.00 hormonal=0.00
LOG 🧠 LATENT: meal=0.92 endo=0.00 siCirc=1.00 resist=0.12 sleep=0.00 sensor=0.11
READ key=DoubleKey.OApsAIMIActivityBasalCapFactor value=1.30
LOG 🏃 ACTIVITY_BASAL_CAP[AUTODRIVE_V3_DIRECT]: 2.40→1.30 U/h (≤ 1.30× profile 1.00)
EFFECT SetTbr rate=1.30 dur=30 override=true forceExact=false adaptive=1.00
READ key=BooleanKey.OApsAIMIautodriveAggressiveSmbFloor value=false
READ key=DoubleKey.OApsAIMIautodrivesmallPrebolus value=0.50
READ key=DoubleKey.OApsAIMIautodrivePrebolus value=1.50
READ key=StringKey.AimiTuningContextSelection value=
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=DoubleKey.autodriveMaxBasal value=0.00
READ key=BooleanKey.OApsAIMIPredictionAuthorityEnabled value=false
READ key=BooleanKey.OApsAIMIPredictionAuthorityShadow value=false
READ key=BooleanKey.OApsAIMIMealConfirmedEarlyRelease value=false
LOG PRED_AUTHORITY: src=PKPD_ONLY predT=160 evT=160 pkpd=160 best=- mealSupp=false uplift=false no_scenario_projection [pre_v3_rbt]
LOG DOSE_TERMINAL_SNAPSHOT: ev=160 minPred=160 src=PKPD_RAW auth=false clamp=false plateauLift=false curves=false [pre_v3_rbt]
READ key=BooleanKey.OApsAIMIStraightLineTubeAdvisorEnabled value=false
READ key=BooleanKey.OApsAIMIRecursiveBeliefShadow value=false
READ key=BooleanKey.OApsAIMIRecursiveBeliefAuthority value=false
READ key=BooleanKey.OApsAIMIRecursiveBeliefWavelet value=false
READ key=BooleanKey.OApsAIMIMealHyperBypassEnabled value=false
READ key=BooleanKey.OApsAIMITreeMealRiseFrontLoad value=false
LOG 🚀 🚀 V3 ENGAGED [Meal-aware rise] (BG=160.0, Trend=3.0, COB=0.0, UAM=0.0) intent=0.8 actual=0.0 tbr=2.4
LOG DECISION_FINAL[AUTODRIVE_V3]: smb=0.00U tbr=0.00U/h dur=0m bg=160 Δ=3.0 reason=
LOG 📦 CACHE TDD24H=MISSING reason=tdd24h_not_ready
LOG 🧭 BASAL_GOV[FINAL]: action=KEEP conf=0.00 n=0 hypo=0.00 hypoG=0.00 hypoAdj=0.00 ant=0.00 wMean=1.00 high=0.00 mae=0.0 latch=false floorB=- floorA=- wBolus=0.00U wCob=?g reason=trace
LOG TICK ts=<clock> bg=160 d=3.0 iob=1.00 act=0.000 th=0.188 cob=0.0 mode=Meal autodriveState=ENGAGED pred=N(sz=0 ev=160) safety=NONE ref=NO maxIOB=10.00 maxSMB=2.00 smb=0.00->0.00->0.00 tbr=0.00 src=AIMI
""".trimIndent()

        private val UAM_TRACE = """
READ key=DoubleKey.OApsAIMIT3cAnticipationStrength value=0.40
READ key=BooleanKey.OApsAIMIT3cBrittleMode value=false
READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=5.00
READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
READ key=IntKey.OApsAIMINightGrowthAgeYears value=0
READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
READ key=StringKey.OApsAIMINightGrowthStart value=
READ key=StringKey.OApsAIMINightGrowthEnd value=
READ key=DoubleKey.OApsAIMINightGrowthMaxIobExtra value=0.00
READ key=BooleanKey.AimiEndometriosisEnable value=false
LOG 😴 SLEEP_LIVE: wearable steps15=0 hr=72/rhr=60 conf=0.57 conf=0.57
READ key=DoubleKey.OApsAIMISmbTailDamping value=0.25
READ key=StringKey.AimiTuningContextSelection value=
SIGNAL postHypoOrdinal=2
SIGNAL uamDominant=MEAL
SIGNAL uamMealProb=0.72
SIGNAL uamSuppress=false
SIGNAL t3cActive=false
SIGNAL t3cDemand=null
SIGNAL ngrSmb=null
SIGNAL ngrBasal=null
SIGNAL tuning=
SIGNAL ngrMember=1.00
""".trimIndent()

        private val BRITTLE_HYPO_TRACE = """
            READ key=DoubleKey.OApsAIMIT3cAnticipationStrength value=0.40
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=5.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=IntKey.OApsAIMINightGrowthAgeYears value=0
            READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
            READ key=StringKey.OApsAIMINightGrowthStart value=
            READ key=StringKey.OApsAIMINightGrowthEnd value=
            READ key=DoubleKey.OApsAIMINightGrowthMaxIobExtra value=0.00
            READ key=BooleanKey.AimiEndometriosisEnable value=false
            LOG 😴 SLEEP_LIVE: wearable steps15=0 hr=72/rhr=60 conf=0.57 conf=0.57
            READ key=DoubleKey.OApsAIMIT3cActivationThreshold value=140.00
            READ key=DoubleKey.OApsAIMISmbTailDamping value=0.25
            READ key=StringKey.AimiTuningContextSelection value=
            SIGNAL postHypoOrdinal=2
            SIGNAL uamDominant=MEAL
            SIGNAL uamMealProb=0.72
            SIGNAL uamSuppress=false
            SIGNAL t3cActive=true
            SIGNAL t3cDemand=0.00
            SIGNAL ngrSmb=null
            SIGNAL ngrBasal=null
            SIGNAL tuning=
            SIGNAL ngrMember=1.00
        """.trimIndent()

        private val BRITTLE_CEILING_TRACE = """
            READ key=DoubleKey.OApsAIMIT3cAnticipationStrength value=0.40
            READ key=BooleanKey.OApsAIMIT3cBrittleMode value=true
            READ key=DoubleKey.OApsAIMILastEstimatedCarbs value=5.00
            READ key=DoubleKey.OApsAIMILastEstimatedCarbTime value=0.00
            READ key=IntKey.OApsAIMINightGrowthAgeYears value=0
            READ key=BooleanKey.OApsAIMINightGrowthEnabled value=null
            READ key=StringKey.OApsAIMINightGrowthStart value=
            READ key=StringKey.OApsAIMINightGrowthEnd value=
            READ key=DoubleKey.OApsAIMINightGrowthMaxIobExtra value=0.00
            READ key=BooleanKey.AimiEndometriosisEnable value=false
            LOG 😴 SLEEP_LIVE: wearable steps15=0 hr=72/rhr=60 conf=0.57 conf=0.57
            READ key=DoubleKey.OApsAIMIT3cActivationThreshold value=140.00
            READ key=DoubleKey.OApsAIMISmbTailDamping value=0.25
            READ key=StringKey.AimiTuningContextSelection value=
            SIGNAL postHypoOrdinal=0
            SIGNAL uamDominant=MEAL
            SIGNAL uamMealProb=0.72
            SIGNAL uamSuppress=false
            SIGNAL t3cActive=true
            SIGNAL t3cDemand=1.20
            SIGNAL ngrSmb=null
            SIGNAL ngrBasal=null
            SIGNAL tuning=
            SIGNAL ngrMember=1.00
        """.trimIndent()
    }

}
