package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CapSmbDoseTest : TestBase() {

    @Test
    fun caps_to_max_smb_config() {
        val capped = capSmbDose(
            proposedSmb = 2.0f,
            bg = 150.0,
            maxSmbConfig = 1.0,
            iob = 0.0,
            maxIob = 10.0,
        )
        assertThat(capped).isEqualTo(1.0f)
    }

    @Test
    fun caps_to_max_iob_headroom() {
        val capped = capSmbDose(
            proposedSmb = 2.0f,
            bg = 150.0,
            maxSmbConfig = 5.0,
            iob = 9.5,
            maxIob = 10.0,
        )
        assertThat(capped).isEqualTo(0.5f)
    }
}
