package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The lunch factor is percent (100 = neutral, default 50). The control rule must raise it by
 * percent points and stop at a percent ceiling, 100 (neutral) since step T2. On the old 1.0 scale
 * the ceiling was 1.2, so the rule never proposed anything for a real stored value.
 */
class AdvisorLunchFactorScaleTest {

    private val lowTirMetrics = AdvisorMetrics(
        periodLabel = "7d",
        tir70_180 = 0.60,
        tir70_140 = 0.40,
        timeBelow70 = 0.0,
        timeBelow54 = 0.0,
        timeAbove180 = 0.10,
        timeAbove250 = 0.0,
        meanBg = 160.0,
        variabilityCv = 0.30,
        gmi = 7.0,
        tdd = 40.0,
        basalPercent = 0.40,
        hypoEvents = 0,
        severeHypoEvents = 0,
        hyperEvents = 0,
        todayTir = null,
        todayTdd = null,
    )

    private fun action(lunchFactor: Double): AimiAction.PreferenceUpdate? {
        val prefs = AimiPrefsSnapshot(
            maxSmb = 1.0,
            lunchFactor = lunchFactor,
            unifiedReactivityFactor = 1.0,
            autodriveMaxBasal = 1.0,
        )
        val recs = metricRecommendations(lowTirMetrics, prefs, null)
        assertEquals(1, recs.size)
        return recs[0].action as? AimiAction.PreferenceUpdate
    }

    @Test
    fun theDefaultLunchFactorIsRaisedByTenPercentPoints() {
        val update = assertNotNull(action(50.0))
        assertEquals(DoubleKey.OApsAIMILunchFactor, update.key)
        assertEquals(60.0, update.newValue)
    }

    @Test
    fun aRaiseStopsAtNeutral() {
        // Step T2: 95 + 10 would be 105, the suggestion stops at 100 (neutral).
        val update = assertNotNull(action(95.0))
        assertEquals(100.0, update.newValue)
    }

    @Test
    fun aLunchFactorAtOrAboveNeutralIsLeftAlone() {
        assertNull(action(100.0))
        assertNull(action(115.0))
        assertNull(action(120.0))
    }
}
