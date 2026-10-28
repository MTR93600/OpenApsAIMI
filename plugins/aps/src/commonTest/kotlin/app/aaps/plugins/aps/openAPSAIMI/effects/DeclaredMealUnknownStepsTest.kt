package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.basal.BasalHistoryUtils
import app.aaps.plugins.aps.openAPSAIMI.basal.DynamicBasalStackCap
import app.aaps.plugins.aps.openAPSAIMI.basal.StepDataAbsenceBasalTest
import kotlin.math.roundToInt
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rule: a declared meal never gets less insulin because step data is missing.
 *
 * Unknown steps are `null` since 74b9f17836e, and with `null` two gestures stand down: the calm-meal
 * basal x1.5 and the HR-trend ISF x0.9. Before that commit unknown steps read as 0 and both could
 * fire. During a declared meal ([DynamicBasalStackCap.declaredMealActive]) unknown steps read as 0
 * again, so the meal is dosed exactly as with 0 steps. Outside a declared meal they stay unknown.
 *
 * Runs on the JVM and on the iOS simulator. Test names are camelCase, with no comma.
 */
class DeclaredMealUnknownStepsTest {

    private val basal = StepDataAbsenceBasalTest()
    private val heartRateFixtures = DecideHeartRateIsfFromTherapyReadsTest()

    @BeforeTest
    fun emptyBasalHistory() {
        BasalHistoryUtils.historyProvider = BasalHistoryUtils.EmptyProvider
    }

    // ---- the rule itself ------------------------------------------------------------------------

    private val unknown = AimiRecentSteps(null, null, null, null, null, null)

    @Test
    fun duringADeclaredMealEveryUnknownWindowReadsAsZero() {
        assertEquals(AimiRecentSteps(0, 0, 0, 0, 0, 0), unknown.unknownAsZeroDuringDeclaredMeal(declaredMeal = true))
    }

    @Test
    fun duringADeclaredMealAKnownCountIsKept() {
        val mixed = AimiRecentSteps(120, null, 300, null, 900, null)
        assertEquals(AimiRecentSteps(120, 0, 300, 0, 900, 0), mixed.unknownAsZeroDuringDeclaredMeal(declaredMeal = true))
    }

    @Test
    fun withNoDeclaredMealUnknownStaysUnknown() {
        assertEquals(unknown, unknown.unknownAsZeroDuringDeclaredMeal(declaredMeal = false))
    }

    @Test
    fun theDeclaredMealRuleIsTheStackCapRule() {
        // A meal note 90 min old, the mode itself over: still a declared meal.
        assertTrue(DynamicBasalStackCap.declaredMealActive(false, 90L, false))
        // No mode, no note: no declared meal.
        assertFalse(DynamicBasalStackCap.declaredMealActive(false, null, false))
    }

    // ---- through the tick step loading ----------------------------------------------------------

    @Test
    fun aDeclaredMealWithNoStepDataLoadsZeroSteps() {
        val loaded = loadStepsWithNoData(declaredMeal = true)
        assertEquals(AimiRecentSteps(0, 0, 0, 0, 0, 0), loaded)
    }

    @Test
    fun noDeclaredMealWithNoStepDataLoadsUnknownSteps() {
        val loaded = loadStepsWithNoData(declaredMeal = false)
        assertEquals(unknown, loaded)
    }

    // ---- calm-meal basal x1.5 -------------------------------------------------------------------

    @Test
    fun aDeclaredMealWithNoStepDataGetsTheSameCalmMealBasalAsZeroSteps() {
        val steps5 = loadStepsWithNoData(declaredMeal = true).steps5
        // 1.2 is the calm-meal basal: 1.5 times the profile basal 0.8.
        assertEquals(basal.rateWith(steps = 0), basal.rateWith(steps = steps5), absoluteTolerance = 0.001)
        assertEquals(1.2, basal.rateWith(steps = steps5), absoluteTolerance = 0.001)
    }

    @Test
    fun noDeclaredMealWithNoStepDataDoesNotGetTheCalmMealBasal() {
        val steps5 = loadStepsWithNoData(declaredMeal = false).steps5
        assertNull(steps5)
        // 1.3 is the trend block the cascade reaches when no step branch fires.
        assertEquals(1.3, basal.rateWith(steps = steps5), absoluteTolerance = 0.001)
    }

    // ---- HR-trend ISF x0.9 ----------------------------------------------------------------------

    @Test
    fun aDeclaredMealWithNoStepDataGetsTheSameHeartRateTrendIsfAsZeroSteps() {
        val now = aimiWallClockMs()
        val withNoSteps = heartRateIsf(now, steps = emptyList(), declaredMeal = true)
        val withZeroSteps = heartRateIsf(now, steps = listOf(zeroSteps(now - 2 * 60_000L)), declaredMeal = false)
        assertEquals(withZeroSteps.variableSensitivity, withNoSteps.variableSensitivity, 0.001f)
        assertEquals(45.0f, withNoSteps.variableSensitivity, 0.001f)
        assertEquals("💓 HR_TREND_ISF x0.90 (hr10 110 / hr60 88, steps10 0)", withNoSteps.logLine)
    }

    @Test
    fun noDeclaredMealWithNoStepDataKeepsTheHeartRateTrendIsfOff() {
        val result = heartRateIsf(aimiWallClockMs(), steps = emptyList(), declaredMeal = false)
        assertEquals(50.0f, result.variableSensitivity, 0.001f)
        assertNull(result.logLine)
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** The four watch samples that give HR-trend ISF x0.90 (50 becomes 45) when the person is still. */
    private fun heartRateIsf(now: Long, steps: List<SC>, declaredMeal: Boolean): HeartRateIsfFromReads =
        decideHeartRateIsfFromTherapyReads(
            reads = MemoryAimiTherapyReads(
                heartRates = listOf(
                    hr(now - 40 * 60_000L, 80.0),
                    hr(now - 30 * 60_000L, 80.0),
                    hr(now - 20 * 60_000L, 80.0),
                    hr(now - 2 * 60_000L, 110.0),
                ),
                steps = steps,
            ),
            nowMs = now,
            stepsFromWatch = true,
            startingSensitivity = 50.0f,
            delta = 2.0f,
            glucoseMgdl = 140.0,
            bgMgdl = 160.0,
            iob = 1.0,
            bolusFromMs = 0L,
            bolusAscending = true,
            profile = heartRateFixtures.profile(),
            preferences = StepsWatchPreferences(enabled = true),
            consoleLog = mutableListOf(),
            declaredMealActive = declaredMeal,
        )

    /** Runs the tick step loading with the watch on and no step record, and returns what it stored. */
    private fun loadStepsWithNoData(declaredMeal: Boolean): AimiRecentSteps {
        val now = aimiWallClockMs()
        var stored: AimiRecentSteps? = null
        decideHeartRateIsf(
            glucoseStatus = GlucoseStatusAIMI(
                glucose = 140.0,
                delta = 2.0,
                shortAvgDelta = 2.0,
                longAvgDelta = 2.0,
                date = now,
                combinedDelta = 2.0,
            ),
            profile = heartRateFixtures.profile(),
            iobData = IobTotal(time = now, iob = 1.0),
            bg = 160.0,
            preferences = StepsWatchPreferences(enabled = true),
            consoleLog = mutableListOf(),
            consoleError = mutableListOf(),
            calls = object : AimiHeartRateIsfCalls {
                override fun iob(): Float = 1.0f
                override fun roundDisplay(value: Double): Int = value.roundToInt()
                override fun stepsCached(now: Long): List<SC> = emptyList()
                override fun logSteps(samples: List<SC>) = Unit
                override fun setRecentSteps(steps5: Int?, steps10: Int?, steps15: Int?, steps30: Int?, steps60: Int?, steps180: Int?) {
                    stored = AimiRecentSteps(steps5, steps10, steps15, steps30, steps60, steps180)
                }
                override fun declaredMealWindowActive(): Boolean = declaredMeal
                override fun phoneSteps5(): Int? = null
                override fun phoneSteps10(): Int? = null
                override fun phoneSteps15(): Int? = null
                override fun phoneSteps30(): Int? = null
                override fun phoneSteps60(): Int? = null
                override fun phoneSteps180(): Int? = null
                override fun heartRatesCached(now: Long): List<HR> = emptyList()
                override fun logHeartRates(samples: List<HR>) = Unit
                override fun setAverageBpm(value: Double) = Unit
                override fun averageBpm(): Double = Double.NaN
                override fun setAverageBpm10(value: Double) = Unit
                override fun setAverageBpm60(value: Double) = Unit
                override fun setAverageBpm180(value: Double) = Unit
                override fun setBaselineReal(value: Boolean) = Unit
                override fun logHeartRateFailure(error: Exception) = Unit
                override fun recentSteps10(): Int? = stored?.steps10
                override fun averageBpm10(): Double = Double.NaN
                override fun averageBpm60(): Double = 80.0
                override fun baselineReal(): Boolean = false
                override fun delta(): Float = 2.0f
                override fun scaleVariableSensitivity(factor: Float) = Unit
            },
        )
        return checkNotNull(stored) { "the step loading did not store any steps" }
    }

    private fun hr(timestamp: Long, bpm: Double) = HR(
        duration = 60_000L,
        timestamp = timestamp,
        beatsPerMinute = bpm,
        device = "watch",
    )

    private fun zeroSteps(timestamp: Long) = SC(
        duration = 60_000L,
        timestamp = timestamp,
        steps5min = 0,
        steps10min = 0,
        steps15min = 0,
        steps30min = 0,
        steps60min = 0,
        steps180min = 0,
        device = "watch",
    )
}
