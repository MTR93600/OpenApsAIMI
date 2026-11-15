package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Step S2: the causal notes for the AI coach no longer read the Control Center display levels. Those
 * levels were a guess computed from many settings, and the screen that showed them is gone. A note
 * now stands only on measured numbers and on real switches.
 */
class AimiBehaviorCausalAnalyzerTest {

    private fun metrics(
        below70: Double,
        above180: Double,
        cv: Double,
        severe: Int = 0,
    ) = AdvisorMetrics(
        periodLabel = "7d",
        tir70_180 = 1.0 - below70 - above180,
        tir70_140 = null,
        timeBelow70 = below70,
        timeBelow54 = null,
        timeAbove180 = above180,
        timeAbove250 = null,
        meanBg = 160.0,
        variabilityCv = cv,
        gmi = null,
        tdd = null,
        basalPercent = null,
        hypoEvents = 0,
        severeHypoEvents = severe,
        hyperEvents = 0,
        todayTir = null,
        todayTdd = null,
        dataSufficiency = AdvisorDataSufficiency.GOOD,
    )

    private val manyHighsFewLows = metrics(below70 = 0.01, above180 = 0.30, cv = 0.30)

    private fun ids(prefs: MapPreferences, m: AdvisorMetrics = manyHighsFewLows): List<String> =
        buildAimiBehaviorCausalInsights(prefs, m).map { it.id }

    @Test
    fun `the meal note follows the real Autodrive switches`() {
        val off = MapPreferences().apply { booleans[BooleanKey.OApsAIMIautoDriveActive] = false }
        val authoritative = MapPreferences().apply {
            booleans[BooleanKey.OApsAIMIautoDriveActive] = true
            booleans[BooleanKey.OApsAIMIautoDriveAuthoritative] = true
        }
        assertTrue("meal_latency" in ids(off))
        assertTrue("meal_latency" !in ids(authoritative))
    }

    @Test
    fun `the notes are the same for very different stored dose limits and dampings`() {
        // These values fed the old display levels (protection, meal, stability).
        val low = MapPreferences().apply { DoubleKey.entries.forEach { doubles[it] = it.min } }
        val high = MapPreferences().apply { DoubleKey.entries.forEach { doubles[it] = it.max } }
        assertEquals(ids(low), ids(high))
        val mixed = metrics(below70 = 0.06, above180 = 0.15, cv = 0.40, severe = 1)
        assertEquals(ids(low, mixed), ids(high, mixed))
    }

    @Test
    fun `no note is built on a display level`() {
        // "physio_ambiguity" stood only on two display levels; it is gone.
        val prefs = MapPreferences().apply { booleans[BooleanKey.AimiPhysioAssistantEnable] = false }
        assertTrue("physio_ambiguity" !in ids(prefs, metrics(below70 = 0.02, above180 = 0.10, cv = 0.35)))
    }

    @Test
    fun `no measured numbers means no note`() {
        val insufficient = manyHighsFewLows.copy(dataSufficiency = AdvisorDataSufficiency.INSUFFICIENT)
        assertEquals(emptyList(), ids(MapPreferences(), insufficient))
    }
}
