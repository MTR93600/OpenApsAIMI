package app.aaps.plugins.aps.openAPSAIMI.scenario

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InsulinSlopePreserveHysteresisTest {

    @BeforeEach
    fun reset() {
        InsulinSlopePreserveHysteresis.reset()
    }

    @Test
    fun rawTrue_armsHold() {
        assertTrue(InsulinSlopePreserveHysteresis.stabilize(true))
        assertTrue(InsulinSlopePreserveHysteresis.stabilize(false))
    }

    @Test
    fun holdExpiresAfterNFalseTicks() {
        assertTrue(InsulinSlopePreserveHysteresis.stabilize(true))
        repeat(InsulinSlopePreserveHysteresis.HOLD_TICKS_DEFAULT) {
            assertTrue(InsulinSlopePreserveHysteresis.stabilize(false))
        }
        assertFalse(InsulinSlopePreserveHysteresis.stabilize(false))
    }

    @Test
    fun rearmOnTrueDuringHold() {
        assertTrue(InsulinSlopePreserveHysteresis.stabilize(true))
        assertTrue(InsulinSlopePreserveHysteresis.stabilize(false))
        assertTrue(InsulinSlopePreserveHysteresis.stabilize(true))
        repeat(InsulinSlopePreserveHysteresis.HOLD_TICKS_DEFAULT) {
            assertTrue(InsulinSlopePreserveHysteresis.stabilize(false))
        }
        assertFalse(InsulinSlopePreserveHysteresis.stabilize(false))
    }
}
