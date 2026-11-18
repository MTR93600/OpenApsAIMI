package app.aaps.core.ui.compose.pump

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The phone does not lock during a pump wizard, and leaving the wizard puts back what was there. */
class ScreenAwakeTest {

    private var idleTimerDisabled = false
    private val awake = ScreenAwake(read = { idleTimerDisabled }, write = { idleTimerDisabled = it })

    @Test
    fun theWizardKeepsThePhoneAwakeAndGivesTheAutoLockBackWhenItCloses() {
        awake.hold()
        assertTrue(idleTimerDisabled)

        awake.release()
        assertFalse(idleTimerDisabled)
    }

    @Test
    fun leavingTheWizardDoesNotSwitchOffTheKeepScreenOnSetting() {
        idleTimerDisabled = true

        awake.hold()
        awake.release()

        assertTrue(idleTimerDisabled, "the overview setting held the screen on before the wizard")
    }

    @Test
    fun aSecondHoldKeepsTheFirstValueFromBefore() {
        val writes = mutableListOf<Boolean>()
        val counted = ScreenAwake(read = { idleTimerDisabled }, write = { idleTimerDisabled = it; writes += it })

        counted.hold()
        counted.hold()
        counted.release()
        counted.release()

        assertEquals(listOf(true, false), writes)
    }
}
