package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CapSmbDoseTest {

    @Test
    fun respectsMaxSmb() {
        assertEquals(1.5f, capSmbDose(5f, bg = 150.0, maxSmbConfig = 1.5, iob = 0.0, maxIob = 20.0), 0.001f)
    }

    @Test
    fun clampsByMaxIob() {
        assertEquals(2.0f, capSmbDose(5f, bg = 150.0, maxSmbConfig = 10.0, iob = 8.0, maxIob = 10.0), 0.001f)
    }

    @Test
    fun lowBgStillRespectsMaxSmbConfigUpperBound() {
        assertEquals(1.0f, capSmbDose(3f, bg = 100.0, maxSmbConfig = 1.0, iob = 1.0, maxIob = 15.0), 0.001f)
    }
}
