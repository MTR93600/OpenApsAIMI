package app.aaps.core.objects.extensions

import app.aaps.core.data.model.ICfg
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pins the peak+DIA heuristic used to recognise an inhaled insulin (Afrezza).
 *
 * The peak bands of inhaled and injected insulin overlap (20..45 vs 35..120), so the DIA half of
 * the test is what keeps the answer unambiguous. These cases are the boundary of that rule.
 */
class InhaledInsulinExtensionTest {

    private fun iCfg(peak: Int, dia: Double) = ICfg(insulinLabel = "test", peak = peak, dia = dia, concentration = 1.0)

    @Test
    fun `Afrezza factory default is inhaled`() {
        assertThat(iCfg(peak = 40, dia = 2.5).looksInhaled()).isTrue()
    }

    @Test
    fun `Afrezza with an edited peak is still inhaled`() {
        assertThat(iCfg(peak = 25, dia = 2.0).looksInhaled()).isTrue()
        assertThat(iCfg(peak = 45, dia = 3.0).looksInhaled()).isTrue()
    }

    @Test
    fun `Lyumjev is not inhaled even though its peak is in the inhaled band`() {
        assertThat(iCfg(peak = 45, dia = 8.0).looksInhaled()).isFalse()
    }

    @Test
    fun `Fiasp is not inhaled`() {
        assertThat(iCfg(peak = 55, dia = 8.0).looksInhaled()).isFalse()
    }

    @Test
    fun `a free peak insulin sitting on the Afrezza peak is not inhaled because its DIA is long`() {
        // The overlap case. OREF_FREE_PEAK allows any peak in 35..120 min, so 40 min alone proves
        // nothing; only the DIA separates the two families.
        assertThat(iCfg(peak = 40, dia = 6.0).looksInhaled()).isFalse()
    }

    @Test
    fun `DIA of 4 hours is not inhaled because it is a valid injected DIA`() {
        assertThat(iCfg(peak = 45, dia = 4.0).looksInhaled()).isFalse()
    }
}
