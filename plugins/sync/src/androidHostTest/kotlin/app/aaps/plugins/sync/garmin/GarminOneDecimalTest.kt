package app.aaps.plugins.sync.garmin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Locale

/** The shared one decimal format against the Java format the Android plugin used before. */
internal class GarminOneDecimalTest {

    @Test
    fun sameTextAsJavaFormatForEveryBasalRate() {
        // Rates as they come out of profile basal x factor, from 0 to 30 U/h.
        for (hundredths in 0..3_000) {
            for (factor in listOf(1.0, 0.8, 1.15, 0.33, 2.5)) {
                val rate = hundredths / 100.0 * factor
                assertEquals(String.format(Locale.US, "%.1f", rate), GarminRequests.formatOneDecimal(rate), "rate $rate")
            }
        }
    }
}
