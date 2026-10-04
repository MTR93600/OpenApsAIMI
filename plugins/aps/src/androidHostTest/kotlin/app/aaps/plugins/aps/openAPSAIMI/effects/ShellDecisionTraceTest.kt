package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.AutosensDataStore
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
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.AimiUamHandler
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import app.aaps.plugins.aps.openAPSAIMI.GlucoseStatusCalculatorAimi
import app.aaps.plugins.aps.openAPSAIMI.advisor.gestation.GestationalAutopilot
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.learning.BasalNeuralLearner
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.CircadianMealProfileStore
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionMemory
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisId
import app.aaps.plugins.aps.openAPSAIMI.physio.UamHypothesisState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.AdvancedPredictionCurves
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLearnedState
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAuditor
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiHealthContext
import app.aaps.plugins.aps.openAPSAIMI.recursive.RbtExtendedSignals
import app.aaps.plugins.aps.openAPSAIMI.recursive.RecursiveBeliefSnapshot
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryGuard
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.plugins.aps.openAPSAIMI.validation.PumpCapabilityValidator
import app.aaps.plugins.aps.openAPSAIMI.wcycle.ThyroidStatus
import app.aaps.plugins.aps.openAPSAIMI.wcycle.VerneuilStatus
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
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
import org.mockito.kotlin.whenever
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
    fun t9WithPhysioAssistantOffKeepsNeutralMultipliers() {
        val prefs = recordingPreferences(emptyMap())
        setField(tick, "preferences", prefs)
        setField(tick, "activePlugin", mock(app.aaps.core.interfaces.plugin.ActivePlugin::class.java))
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
    ) {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runTickClockMaxSmbTirCarbAndGlucoseCopy" && it.parameterCount == 4
        }
        method.isAccessible = true
        method.invoke(tick, ctx, glucose, rT, combinedDelta)
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

    private fun invokeRbtResolve(rT: RT, profile: OapsProfileAimi): Any? {
        val method = tick.javaClass.declaredMethods.first {
            it.name == "runRecursiveBeliefResolve" && it.parameterCount == 13
        }
        method.isAccessible = true
        val htr = HyperTrajectoryReleaseResult(
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
        )
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
    ): DetermineBasalaimiSMB2 {
        val rh = mock(TextResolver::class.java, Answer { inv: InvocationOnMock ->
            if (inv.method.name == "gs") "phrase" else null
        })
        return DetermineBasalaimiSMB2(
            profileUtil = mock(ProfileUtil::class.java),
            fabricPrivacy = mock(FabricPrivacy::class.java),
            preferences = preferences,
            pkPdLearnedState = mock(PkPdLearnedState::class.java),
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
