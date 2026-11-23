package app.aaps.plugins.aps.openAPSAIMI.extensions

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.plugins.aps.openAPSAIMI.AimiBgFeatures
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The glucose status `OpenAPSAIMIPlugin` gives to `determine_basal`. Until 2026-11 the plugin set
 * the stable band average (`duraISFaverage`) to 0 on every tick, so T3C Safety Guard 2 could
 * never fire.
 */
class AimiTickGlucoseStatusTest {

    private val calculatorStatus = GlucoseStatusAIMI(
        glucose = 182.0,
        noise = 1.0,
        delta = -1.5,
        shortAvgDelta = -1.0,
        longAvgDelta = 0.5,
        date = 1_700_000_000_000L,
        parabolaMinutes = 25.0,
        a0 = 181.0,
        a1 = -1.2,
        a2 = 0.1,
        sourceSensor = null,
    )

    private val features = AimiBgFeatures(
        accel = -0.3,
        delta5Prev = -1.1,
        delta5Next = -1.4,
        corrR2 = 0.93,
        parabolaMinutes = 25.0,
        a0 = 181.0,
        a1 = -1.2,
        a2 = 0.1,
        stable5pctMinutes = 35.0,
        stable5pctAverage = 178.5,
        combinedDelta = -1.2,
        isNightGrowthCandidate = false,
    )

    @Test
    fun theStatusCarriesTheStableBandAverage() {
        val status = aimiTickGlucoseStatus(calculatorStatus, features)
        assertEquals(178.5, status.duraISFaverage)
        assertEquals(35.0, status.duraISFminutes)
    }

    @Test
    fun theStatusKeepsTheCalculatorReadingsAndTheFeatures() {
        val status = aimiTickGlucoseStatus(calculatorStatus, features)
        assertEquals(182.0, status.glucose)
        assertEquals(1.0, status.noise)
        assertEquals(-1.5, status.delta)
        assertEquals(-1.0, status.shortAvgDelta)
        assertEquals(0.5, status.longAvgDelta)
        assertEquals(1_700_000_000_000L, status.date)
        assertEquals(-1.1, status.deltaPl)
        assertEquals(-1.4, status.deltaPn)
        assertEquals(-0.3, status.bgAcceleration)
        assertEquals(0.93, status.corrSqu)
    }

    @Test
    fun theParabolaFieldsStayAtZeroOnPurpose() {
        val status = aimiTickGlucoseStatus(calculatorStatus, features)
        assertEquals(0.0, status.parabolaMinutes)
        assertEquals(0.0, status.a0)
        assertEquals(0.0, status.a1)
        assertEquals(0.0, status.a2)
    }

    @Test
    fun withoutFeaturesTheBandIsZero() {
        val status = aimiTickGlucoseStatus(calculatorStatus, null)
        assertEquals(0.0, status.duraISFaverage)
        assertEquals(0.0, status.duraISFminutes)
        assertEquals(0.0, status.bgAcceleration)
    }

    @Test
    fun withStableBandReplacesOnlyTheBand() {
        val status = calculatorStatus.copy(duraISFminutes = 5.0, duraISFaverage = 90.0).withStableBand(features)
        assertEquals(35.0, status.duraISFminutes)
        assertEquals(178.5, status.duraISFaverage)
        assertEquals(25.0, status.parabolaMinutes)
        assertEquals(182.0, status.glucose)
    }
}
