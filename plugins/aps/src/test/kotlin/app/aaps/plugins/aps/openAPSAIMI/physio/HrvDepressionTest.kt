package app.aaps.plugins.aps.openAPSAIMI.physio

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.jupiter.api.Test

/**
 * [HrvDepression] replaced three fixed HRV bands with a reading relative to the person.
 *
 * The cases that matter most are the ones where it must answer "I do not know", because the dose
 * path reads that as neutral and a wrong answer there would move insulin.
 */
class HrvDepressionTest {

    private fun baseline(mean: Double, sd: Double, n: Int) =
        MetricBaselineMTR(metricName = "hrvRMSSD", mean = mean, stdDev = sd, sampleCount = n)

    @Test
    fun `at the person's own mean there is no depression`() {
        assertThat(HrvDepression.of(50.0, baseline(mean = 50.0, sd = 10.0, n = 7))).isEqualTo(0.0)
    }

    @Test
    fun `above the mean there is no depression`() {
        assertThat(HrvDepression.of(80.0, baseline(mean = 50.0, sd = 10.0, n = 7))).isEqualTo(0.0)
    }

    @Test
    fun `one sigma below the mean is half way`() {
        assertThat(HrvDepression.of(40.0, baseline(mean = 50.0, sd = 10.0, n = 7))).isEqualTo(0.5)
    }

    @Test
    fun `two sigma below the mean is fully depressed`() {
        assertThat(HrvDepression.of(30.0, baseline(mean = 50.0, sd = 10.0, n = 7))).isEqualTo(1.0)
    }

    @Test
    fun `further below is still capped at one`() {
        assertThat(HrvDepression.of(5.0, baseline(mean = 50.0, sd = 10.0, n = 7))).isEqualTo(1.0)
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

        assertWithMessage("someone whose normal is 27 is only a little below it at 25")
            .that(lowNormalPerson).isLessThan(0.3)
        assertWithMessage("someone whose normal is 60 is far below it at 25")
            .that(highNormalPerson).isEqualTo(1.0)
    }

    /** The scale of the numbers does not matter, only the distance in the person's own spread. */
    @Test
    fun `the scale does not matter when the baseline holds the same scale`() {
        val small = HrvDepression.of(40.0, baseline(mean = 50.0, sd = 10.0, n = 7))
        val large = HrvDepression.of(80.0, baseline(mean = 100.0, sd = 20.0, n = 7))

        assertThat(small).isEqualTo(large)
    }

    @Test
    fun `no reading answers no depression`() {
        assertThat(HrvDepression.of(0.0, baseline(mean = 50.0, sd = 10.0, n = 7))).isEqualTo(0.0)
        assertThat(HrvDepression.of(-1.0, baseline(mean = 50.0, sd = 10.0, n = 7))).isEqualTo(0.0)
    }

    /** A cold start must not guess. Fewer than three samples is not a personal normal. */
    @Test
    fun `a baseline with too few samples answers no depression`() {
        assertThat(HrvDepression.of(10.0, baseline(mean = 50.0, sd = 10.0, n = 2))).isEqualTo(0.0)
    }

    /** Every sample the same so far: there is no spread to measure a distance against. */
    @Test
    fun `a baseline with no spread answers no depression`() {
        assertThat(HrvDepression.of(10.0, baseline(mean = 50.0, sd = 0.0, n = 7))).isEqualTo(0.0)
    }
}
