package app.aaps.implementation.notifications

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Who keeps AAPS awake, and when the keep-awake may set the audio category. The category matters:
 * set in the middle of an alarm, it would make an alarm ring through the silent switch that the
 * person asked to respect.
 */
class KeepAwakeStateTest {

    @Test
    fun `nobody asking means not wanted`() {
        val state = KeepAwakeState()
        assertFalse(state.wanted)
        assertFalse(state.mayClaimCategory)
    }

    @Test
    fun `one owner is enough and only a real change is reported`() {
        val state = KeepAwakeState()
        assertTrue(state.set("garmin", true))
        assertTrue(state.wanted)
        assertFalse(state.set("garmin", true))
    }

    @Test
    fun `it stays wanted until the last owner lets go`() {
        val state = KeepAwakeState()
        state.set("garmin", true)
        state.set("other", true)
        assertFalse(state.set("garmin", false))
        assertTrue(state.wanted)
        assertTrue(state.set("other", false))
        assertFalse(state.wanted)
    }

    @Test
    fun `an owner letting go that never asked changes nothing`() {
        val state = KeepAwakeState()
        assertFalse(state.set("garmin", false))
    }

    @Test
    fun `the category is left to a playing alarm`() {
        val state = KeepAwakeState()
        state.set("garmin", true)
        state.alarmPlaying = true
        assertFalse(state.mayClaimCategory)
        state.alarmPlaying = false
        assertTrue(state.mayClaimCategory)
    }
}
