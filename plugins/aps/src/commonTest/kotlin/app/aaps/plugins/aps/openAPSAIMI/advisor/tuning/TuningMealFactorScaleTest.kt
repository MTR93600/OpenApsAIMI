package app.aaps.plugins.aps.openAPSAIMI.advisor.tuning

import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorDataSufficiency
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorMetrics
import app.aaps.plugins.aps.openAPSAIMI.advisor.AdvisorMetricRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lunch and dinner factors are percent (100 = neutral). The tuning engine steps them by whole
 * percent points. On the old 1.0 scale a step was 0.05 to 0.20, which on 50 % did nothing.
 */
class TuningMealFactorScaleTest {

    private fun metrics(timeBelow70: Double, timeAbove180: Double) = AdvisorMetrics(
        periodLabel = "7d",
        tir70_180 = 1.0 - timeBelow70 - timeAbove180,
        tir70_140 = 0.5,
        timeBelow70 = timeBelow70,
        timeBelow54 = 0.0,
        timeAbove180 = timeAbove180,
        timeAbove250 = 0.0,
        meanBg = 150.0,
        variabilityCv = 0.30,
        gmi = 6.8,
        tdd = 40.0,
        basalPercent = 0.45,
        hypoEvents = 0,
        severeHypoEvents = 0,
        hyperEvents = 0,
        todayTir = null,
        todayTdd = null,
        dataSufficiency = AdvisorDataSufficiency.GOOD,
    )

    private fun newValue(plan: TuningPlan, key: DoubleKey): Double =
        plan.changes.first { it.key == key }.newValue as Double

    @Test
    fun hypoGuardLowersTheMealFactorsByWholePercentPoints() {
        val preferences = MapPreferences().apply {
            doubles[DoubleKey.OApsAIMILunchFactor] = 100.0
            doubles[DoubleKey.OApsAIMIDinnerFactor] = 50.0
        }
        // 6 % below 70 is the MODERATE hypo tier: a step of 10 points.
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.HYPO_GUARD,
            metrics(timeBelow70 = 0.06, timeAbove180 = 0.05),
            preferences,
            t3cBrittleMode = false,
        )
        assertEquals(90.0, newValue(plan, DoubleKey.OApsAIMILunchFactor), 1e-9)
        assertEquals(40.0, newValue(plan, DoubleKey.OApsAIMIDinnerFactor), 1e-9)
    }

    @Test
    fun mealRiseRaisesTheLunchFactorByWholePercentPoints() {
        // 30 % above 180 is the MODERATE hyper tier: a step of 10 points.
        val plan = TuningContextEngine.computePlan(
            AimiTuningContext.MEAL_RISE,
            metrics(timeBelow70 = 0.0, timeAbove180 = 0.30),
            MapPreferences(),
            t3cBrittleMode = false,
        )
        assertEquals(60.0, newValue(plan, DoubleKey.OApsAIMILunchFactor), 1e-9)
    }

    private fun mealRisePlan(lunchFactor: Double): TuningPlan = TuningContextEngine.computePlan(
        AimiTuningContext.MEAL_RISE,
        metrics(timeBelow70 = 0.0, timeAbove180 = 0.30),
        MapPreferences().apply { doubles[DoubleKey.OApsAIMILunchFactor] = lunchFactor },
        t3cBrittleMode = false,
    )

    @Test
    fun theRaiseCeilingIsNeutral() {
        // Step T2: an automatic suggestion never goes above 100 (neutral). Above that only the user, by hand.
        assertEquals(100.0, AdvisorMetricRules.LUNCH_FACTOR_RAISE_CEILING, 1e-9)
    }

    @Test
    fun mealRiseStopsAtTheSameCeilingAsTheAdvisor() {
        // 95 + 10 would be 105. The raise stops at the ceiling the Advisor rule uses (100).
        assertEquals(100.0, newValue(mealRisePlan(95.0), DoubleKey.OApsAIMILunchFactor), 1e-9)
    }

    @Test
    fun mealRiseProposesNoRaiseAtOrAboveTheCeiling() {
        assertTrue(mealRisePlan(100.0).changes.none { it.key == DoubleKey.OApsAIMILunchFactor })
        assertTrue(mealRisePlan(115.0).changes.none { it.key == DoubleKey.OApsAIMILunchFactor })
        assertTrue(mealRisePlan(140.0).changes.none { it.key == DoubleKey.OApsAIMILunchFactor })
    }
}
