package app.aaps.plugins.aps.openAPSAIMI.extensions

import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.utils.Round
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class GlucoseStatusExtensionAIMITest : TestBase() {

    @Test
    fun as_rounded_keeps_dura_isf_and_parabola_separate_from_delta() {
        val status = GlucoseStatusAIMI(
            glucose = 120.04,
            delta = 1.234,
            duraISFminutes = 12.34,
            parabolaMinutes = 8.76,
            corrSqu = 0.98765
        )
        val rounded = status.asRounded()
        assertThat(rounded.glucose).isEqualTo(Round.roundTo(120.04, 0.1))
        assertThat(rounded.delta).isEqualTo(Round.roundTo(1.234, 0.01))
        assertThat(rounded.duraISFminutes).isEqualTo(Round.roundTo(12.34, 0.1))
        assertThat(rounded.parabolaMinutes).isEqualTo(Round.roundTo(8.76, 0.1))
        assertThat(rounded.corrSqu).isEqualTo(Round.roundTo(0.98765, 0.0001))
    }
}
