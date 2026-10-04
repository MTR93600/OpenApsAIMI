package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2
import app.aaps.plugins.aps.openAPSAIMI.advisor.gestation.GestationalAutopilot
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.autodrive.AutodriveEngine
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalController
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMIInsulinDecisionAdapterMTR
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdLearnedState
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAuditor
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiBehaviorProfileSource
import app.aaps.plugins.aps.openAPSAIMI.safety.PostHypoDeliveryAuthority
import app.aaps.plugins.aps.openAPSAIMI.validation.PumpCapabilityValidator
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleFacade
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCycleLearner
import app.aaps.plugins.aps.openAPSAIMI.wcycle.WCyclePreferences
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.invocation.InvocationOnMock
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer

/**
 * Golden traces of the Android shell as it stands, before the decision moves to common code.
 * The expected text is locked from a real call. A later port must keep these bytes.
 */
class LegacyMealEffectTraceTest {

    private val now = 1_700_000_000_000L
    private lateinit var tick: DetermineBasalaimiSMB2
    private lateinit var dateUtil: DateUtil

    @BeforeEach
    fun setUp() {
        DetermineBasalaimiSMB2.resetLegacyPrebolusMemoryForTrace()
        dateUtil = mock(DateUtil::class.java)
        whenever(dateUtil.now()).thenReturn(now)
        tick = newTick(recordingPreferences(emptyMap()))
        setField(tick, "dateUtil", dateUtil)
        setField(tick, "physioAdapter", mock(AIMIInsulinDecisionAdapterMTR::class.java))
        setField(tick, "consoleLog", ProbingLog())
    }

    @AfterEach
    fun tearDown() {
        AimiEffectProbe.lines.remove()
        DetermineBasalaimiSMB2.resetLegacyPrebolusMemoryForTrace()
    }

    @Test
    fun mealModeRecordsTbrThenPrebolus() {
        val prefs = recordingPreferences(mapOf(DoubleKey.OApsAIMIMealPrebolus to 1.5))
        setField(tick, "preferences", prefs)
        setField(tick, "mealTime", true)
        setField(tick, "mealruntime", 1L)
        setField(tick, "bg", 160.0)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        val trace = capture {
            tick.applyLegacyMealModes(mock(OapsProfileAimi::class.java), RT(runningDynamicIsf = false), mock(CurrentTemp::class.java), 2.5)
        }
        assertEquals(
            """
            READ key=AimiLongKey.PendingLegacyPrebolusUnitMilli value=0
            EFFECT SetTbr rate=2.50 dur=30 override=false forceExact=true adaptive=1.00
            LOG MEAL_TBR_MANUAL[MEAL_P1] rate=2.50U/h dur=30m rt=1m
            READ key=DoubleKey.OApsAIMIMealPrebolus value=1.50
            EFFECT Smb units=1.50 owner=LegacyMealModes
            LOG 🍱 LEGACY_MODE_MEAL P1=1.50U
            WRITE key=AimiLongKey.LastPrebolusTime value=1700000000000
            WRITE key=AimiLongKey.PendingLegacyPrebolusUnitMilli value=1500
            WRITE key=AimiLongKey.PendingLegacyPrebolusExpiry value=1700001800000
            WRITE key=AimiLongKey.LastLegacyPrebolusTime value=1700000000000
            """.trimIndent(),
            trace,
        )
    }

    @Test
    fun hypoRecoverySuppressesThePrebolus() {
        val prefs = recordingPreferences(mapOf(DoubleKey.OApsAIMIMealPrebolus to 1.5))
        setField(tick, "preferences", prefs)
        setField(tick, "mealTime", true)
        setField(tick, "mealruntime", 1L)
        setField(tick, "bg", 60.0)
        setField(tick, "iob", 1.0f)
        setField(tick, "maxIob", 10.0)
        setField(tick, "lastContextSnapshot", ContextSnapshot.empty(now).copy(hasHypoRecovery = true))
        val trace = capture {
            tick.applyLegacyMealModes(mock(OapsProfileAimi::class.java), RT(runningDynamicIsf = false), mock(CurrentTemp::class.java), 2.5)
        }
        assertEquals(
            """
            READ key=AimiLongKey.PendingLegacyPrebolusUnitMilli value=0
            EFFECT SetTbr rate=2.50 dur=30 override=false forceExact=true adaptive=1.00
            LOG MEAL_TBR_MANUAL[MEAL_P1] rate=2.50U/h dur=30m rt=1m
            READ key=DoubleKey.OApsAIMIMealPrebolus value=1.50
            LOG 🍬 CTX_HYPO_RECOVERY: legacy prebolus suppressed tag=MEAL_P1 (was 1.50U)
            """.trimIndent(),
            trace,
        )
    }

    @Test
    fun severeHypoWithPostHypoAuthoritySuppressesThePrebolus() {
        val prefs = recordingPreferences(mapOf(DoubleKey.OApsAIMIMealPrebolus to 1.5))
        setField(tick, "preferences", prefs)
        setField(tick, "mealTime", true)
        setField(tick, "mealruntime", 1L)
        setField(tick, "bg", 50.0)
        setField(tick, "iob", 0.5f)
        setField(tick, "maxIob", 10.0)
        setField(
            tick,
            "lastPostHypoDeliveryAuthority",
            PostHypoDeliveryAuthority.Decision(
                active = true,
                forceMealInterpretationSuppressed = true,
                suppressMealDelivery = true,
                maxSmbU = 0.0,
                reasonTag = "severe",
            ),
        )
        val trace = capture {
            tick.applyLegacyMealModes(mock(OapsProfileAimi::class.java), RT(runningDynamicIsf = false), mock(CurrentTemp::class.java), 2.5)
        }
        assertEquals(
            """
            READ key=AimiLongKey.PendingLegacyPrebolusUnitMilli value=0
            EFFECT SetTbr rate=2.50 dur=30 override=false forceExact=true adaptive=1.00
            LOG MEAL_TBR_MANUAL[MEAL_P1] rate=2.50U/h dur=30m rt=1m
            READ key=DoubleKey.OApsAIMIMealPrebolus value=1.50
            LOG POST_HYPO_DELIVERY: active=true mealSupp=true smbCap=0.00 tag=severe
            LOG POST_HYPO_DELIVERY: legacy_prebolus_blocked tag=MEAL_P1
            """.trimIndent(),
            trace,
        )
    }

    @Test
    fun iobAboveMaxIobKeepsTheTbrAndDropsThePrebolus() {
        val prefs = recordingPreferences(mapOf(DoubleKey.OApsAIMIMealPrebolus to 1.5))
        setField(tick, "preferences", prefs)
        setField(tick, "mealTime", true)
        setField(tick, "mealruntime", 1L)
        setField(tick, "bg", 180.0)
        setField(tick, "iob", 12.0f)
        setField(tick, "maxIob", 10.0)
        val trace = capture {
            tick.applyLegacyMealModes(mock(OapsProfileAimi::class.java), RT(runningDynamicIsf = false), mock(CurrentTemp::class.java), 2.5)
        }
        assertEquals(
            """
            READ key=AimiLongKey.PendingLegacyPrebolusUnitMilli value=0
            EFFECT SetTbr rate=2.50 dur=30 override=false forceExact=true adaptive=1.00
            LOG MEAL_TBR_MANUAL[MEAL_P1] rate=2.50U/h dur=30m rt=1m
            READ key=DoubleKey.OApsAIMIMealPrebolus value=1.50
            LOG 🛡️ LEGACY prebolus tag=MEAL_P1: IOB 12.00U > MaxIOB — TBR seul
            """.trimIndent(),
            trace,
        )
    }

    @Test
    fun autodriveOffReadsTheSwitchAndSetsNoDose() {
        val prefs = recordingPreferences(emptyMap(), mapOf(BooleanKey.OApsAIMIautoDriveActive to false))
        setField(tick, "preferences", prefs)
        val estimator = mock(ContinuousStateEstimator::class.java)
        whenever(estimator.runCount).thenReturn(-1L)
        setField(tick, "continuousStateEstimator", estimator)
        val ctx = AimiTickContext(
            glucoseStatus = mock(GlucoseStatusAIMI::class.java),
            currentTemp = mock(CurrentTemp::class.java),
            iobDataArray = arrayOf(mock(IobTotal::class.java)),
            profile = mock(OapsProfileAimi::class.java),
            autosensData = mock(AutosensResult::class.java),
            mealData = mock(MealData::class.java),
            microBolusAllowed = true,
            currentTime = now,
            flatBGsDetected = false,
            dynIsfMode = false,
            uiInteraction = mock(UiInteraction::class.java),
            extraDebug = "",
        )
        val trace = capture {
            tick.runAutodriveV3MultiVariableBranch(
                ctx = ctx,
                profile = mock(OapsProfileAimi::class.java),
                rT = RT(runningDynamicIsf = false),
                bg = 100.0,
                combinedDelta = -2f,
                shortAvgDeltaAdj = -1f,
                hypoThresholdMgdl = 70.0,
                pkpdRuntime = null,
            )
        }
        assertEquals("READ key=BooleanKey.OApsAIMIautoDriveActive value=false", trace)
    }

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

    private fun newTick(preferences: Preferences): DetermineBasalaimiSMB2 {
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
            autodriveEngine = mock(AutodriveEngine::class.java),
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
}
