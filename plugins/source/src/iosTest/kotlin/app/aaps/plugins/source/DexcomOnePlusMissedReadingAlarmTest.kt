package app.aaps.plugins.source

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * When the iPhone missed-reading alarm rings.
 *
 * Only the timing is tested here. Scheduling itself needs `UNUserNotificationCenter`, which needs
 * an app bundle that a test binary does not have. No test name may contain a comma: Kotlin/Native.
 */
class DexcomOnePlusMissedReadingAlarmTest {

    private val minute = 60_000L

    @Test
    fun theAlarmRingsOneStaleLimitAfterTheReading() {
        val delay = DexcomOnePlusMissedReadingAlarm.delaySeconds(
            readingAtMs = 10 * minute,
            nowMs = 11 * minute,
            enabled = true,
            staleLimitMinutes = 30,
        )

        // Reading at minute 10, limit 30: rings at minute 40, which is 29 minutes from now.
        assertEquals(29 * 60.0, delay)
    }

    @Test
    fun nothingIsArmedWhenTheAlarmIsSwitchedOff() {
        assertNull(DexcomOnePlusMissedReadingAlarm.delaySeconds(0L, 0L, enabled = false, staleLimitMinutes = 30))
    }

    @Test
    fun nothingIsArmedForALimitThatIsNotPositive() {
        assertNull(DexcomOnePlusMissedReadingAlarm.delaySeconds(0L, 0L, enabled = true, staleLimitMinutes = 0))
    }

    @Test
    fun nothingIsArmedWhenTheMomentHasAlreadyPassed() {
        // A reading from long ago: the in-app alarm speaks, a notification for now would only
        // ring at once.
        assertNull(DexcomOnePlusMissedReadingAlarm.delaySeconds(0L, 31 * minute, enabled = true, staleLimitMinutes = 30))
    }

    @Test
    fun aDelayUnderOneSecondIsRaisedToOneBecauseIosRefusesLess() {
        val delay = DexcomOnePlusMissedReadingAlarm.delaySeconds(
            readingAtMs = 0L,
            nowMs = 30 * minute - 200L,
            enabled = true,
            staleLimitMinutes = 30,
        )

        assertTrue(delay != null && delay >= 1.0)
    }

    @Test
    fun theIdentifierStaysOutsideThePrefixMuteAllClears() {
        assertTrue(!DexcomOnePlusMissedReadingAlarm.IDENTIFIER.startsWith("aaps-"))
    }
}
