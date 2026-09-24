package app.aaps.plugins.aps.openAPSAIMI.safety

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmbMaxLimitsTest {

    @Test
    fun capsByMaxSmbFirst() {
        assertEquals(2.0f, clampSmbToMaxSmbAndMaxIob(5f, maxSmb = 2.0, maxIob = 20.0, iob = 10f), 0.001f)
    }

    @Test
    fun thenCapsByMaxIobRoom() {
        assertEquals(2.0f, clampSmbToMaxSmbAndMaxIob(5f, maxSmb = 10.0, maxIob = 12.0, iob = 10f), 0.001f)
    }

    @Test
    fun noChangeWhenUnderBothCaps() {
        assertEquals(3.0f, clampSmbToMaxSmbAndMaxIob(3f, maxSmb = 10.0, maxIob = 20.0, iob = 5f), 0.001f)
    }

    @Test
    fun negativeRoomClampsToZero() {
        assertEquals(0.0f, clampSmbToMaxSmbAndMaxIob(1f, maxSmb = 10.0, maxIob = 5.0, iob = 8f), 0.001f)
    }
}
