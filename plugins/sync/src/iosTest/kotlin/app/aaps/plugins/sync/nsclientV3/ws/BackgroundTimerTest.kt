package app.aaps.plugins.sync.nsclientV3.ws

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The time the app spent in the background, which decides whether the Nightscout socket is rebuilt.
 */
class BackgroundTimerTest {

    private var clock = 1_000L
    private val timer = BackgroundTimer { clock }

    /** At launch the app becomes active without having been in the background. */
    @Test
    fun `becoming active without a background first gives nothing`() {
        assertNull(timer.cameToForeground())
    }

    @Test
    fun `the time away is measured`() {
        timer.wentToBackground()
        clock += 90_000L

        assertEquals(90_000L, timer.cameToForeground())
    }

    /** The control centre makes the app inactive and active again with no background in between. */
    @Test
    fun `a second activation without a new background gives nothing`() {
        timer.wentToBackground()
        clock += 5_000L
        timer.cameToForeground()

        assertNull(timer.cameToForeground())
    }

    /** A clock set back while away must not give a negative time. */
    @Test
    fun `a clock moved back gives zero`() {
        timer.wentToBackground()
        clock -= 60_000L

        assertEquals(0L, timer.cameToForeground())
    }
}
