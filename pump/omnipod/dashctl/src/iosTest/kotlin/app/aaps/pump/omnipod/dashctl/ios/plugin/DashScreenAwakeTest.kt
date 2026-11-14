package app.aaps.pump.omnipod.dashctl.ios.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The iPhone does not lock during the pod wizard, and leaving the wizard puts back what was there. */
class DashScreenAwakeTest {

    private var idleTimerDisabled = false
    private val awake = DashScreenAwake(read = { idleTimerDisabled }, write = { idleTimerDisabled = it })

    @Test
    fun `the wizard keeps the phone awake and gives the auto lock back when it closes`() {
        awake.hold()
        assertTrue(idleTimerDisabled)

        awake.release()
        assertFalse(idleTimerDisabled)
    }

    @Test
    fun `leaving the wizard does not switch off the keep screen on setting`() {
        idleTimerDisabled = true

        awake.hold()
        awake.release()

        assertTrue(idleTimerDisabled, "the overview setting held the screen on before the wizard")
    }

    @Test
    fun `a second hold keeps the first value from before`() {
        val writes = mutableListOf<Boolean>()
        val counted = DashScreenAwake(read = { idleTimerDisabled }, write = { idleTimerDisabled = it; writes += it })

        counted.hold()
        counted.hold()
        counted.release()
        counted.release()

        assertEquals(listOf(true, false), writes)
    }
}
