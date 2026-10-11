package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [HrvDepression] replaced three hardcoded HRV bands with a reading relative to the person.
 *
 * The cases that matter are the ones where it must answer "I do not know", because the dose path
 * reads that as neutral and a wrong answer there would move insulin.
 */
class HrvDepressionTest {

    private fun baseline(mean: Double, sd: Double, n: Int) =
        MetricBaselineMTR(metricName = "hrvRMSSD", mean = mean, stdDev = sd, sampleCount = n)

    @Test
    fun `at the person's own mean there is no depression`() {
        assertEquals(0.0, HrvDepression.of(50.0, baseline(mean = 50.0, sd = 10.0, n = 7)))
    }

    @Test
    fun `above the mean there is no depression`() {
        assertEquals(0.0, HrvDepression.of(80.0, baseline(mean = 50.0, sd = 10.0, n = 7)))
    }

    @Test
    fun `one sigma below the mean is half way`() {
        assertEquals(0.5, HrvDepression.of(40.0, baseline(mean = 50.0, sd = 10.0, n = 7)))
    }

    @Test
    fun `two sigma below the mean is fully depressed`() {
        assertEquals(1.0, HrvDepression.of(30.0, baseline(mean = 50.0, sd = 10.0, n = 7)))
    }

    @Test
    fun `further below is still capped at one`() {
        assertEquals(1.0, HrvDepression.of(5.0, baseline(mean = 50.0, sd = 10.0, n = 7)))
    }

    /**
     * The whole point of the change: two people with the same raw number get different answers,
     * because the number means different things about them. The old fixed band could not do this.
     */
    @Test
    fun `the same reading means different things for two people`() {
        val reading = 25.0
        val lowNormalPerson = HrvDepression.of(reading, baseline(mean = 27.0, sd = 4.0, n = 7))
        val highNormalPerson = HrvDepression.of(reading, baseline(mean = 60.0, sd = 8.0, n = 7))

        assertTrue(lowNormalPerson < 0.3, "someone whose normal is 27 is barely below it at 25")
        assertEquals(1.0, highNormalPerson, "someone whose normal is 60 is far below it at 25")
    }

    /**
     * A reading taken on an iPhone carries SDNN, which lives in a different range. As long as the
     * baseline was built from the same metric, the answer is the same - which is what lets the
     * decision path stop caring which metric arrived.
     */
    @Test
    fun `the metric does not matter when the baseline holds the same one`() {
        val asRmssd = HrvDepression.of(40.0, baseline(mean = 50.0, sd = 10.0, n = 7))
        val asSdnn = HrvDepression.of(80.0, baseline(mean = 100.0, sd = 20.0, n = 7))

        assertEquals(asRmssd, asSdnn)
    }

    @Test
    fun `no reading answers no depression`() {
        assertEquals(0.0, HrvDepression.of(0.0, baseline(mean = 50.0, sd = 10.0, n = 7)))
        assertEquals(0.0, HrvDepression.of(-1.0, baseline(mean = 50.0, sd = 10.0, n = 7)))
    }

    /** A cold start must not guess. Fewer than three samples is not a personal normal. */
    @Test
    fun `a baseline with too few samples answers no depression`() {
        assertEquals(0.0, HrvDepression.of(10.0, baseline(mean = 50.0, sd = 10.0, n = 2)))
    }

    /** Every sample identical so far: there is no spread to measure distance against. */
    @Test
    fun `a baseline with no spread answers no depression`() {
        assertEquals(0.0, HrvDepression.of(10.0, baseline(mean = 50.0, sd = 0.0, n = 7)))
    }
}
