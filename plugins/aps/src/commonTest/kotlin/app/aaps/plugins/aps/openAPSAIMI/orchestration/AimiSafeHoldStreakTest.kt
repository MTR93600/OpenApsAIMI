package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiSafeHoldStreak.Alert
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AimiSafeHoldStreakTest {

    @Test
    fun `the notice starts at the third hold and is posted again on every hold after`() {
        val streak = AimiSafeHoldStreak()
        assertEquals(Alert.NONE, streak.onHold())
        assertEquals(Alert.NONE, streak.onHold())
        assertEquals(Alert.NOTICE, streak.onHold())
        assertEquals(Alert.NOTICE, streak.onHold())
        assertEquals(Alert.NOTICE, streak.onHold())
        assertEquals(5, streak.count)
    }

    @Test
    fun `the alarm sounds once at the sixth hold`() {
        val streak = AimiSafeHoldStreak()
        repeat(5) { streak.onHold() }
        assertEquals(Alert.ALARM, streak.onHold())
        streak.markAlarmPosted()
        // The notice goes on, the alarm does not sound again in the same streak.
        assertEquals(Alert.NOTICE, streak.onHold())
        assertEquals(Alert.NOTICE, streak.onHold())
    }

    @Test
    fun `an alarm that could not be posted is tried again on the next hold`() {
        val streak = AimiSafeHoldStreak()
        repeat(5) { streak.onHold() }
        assertEquals(Alert.ALARM, streak.onHold())
        // No markAlarmPosted: the post failed.
        assertEquals(Alert.ALARM, streak.onHold())
    }

    @Test
    fun `a normal tick ends the streak and the messages are taken away`() {
        val streak = AimiSafeHoldStreak()
        repeat(6) { streak.onHold() }
        streak.markAlarmPosted()
        assertTrue(streak.onNormalTick())
        assertEquals(0, streak.count)
        // Nothing left to take away.
        assertFalse(streak.onNormalTick())
        // A new streak starts from zero and can sound the alarm again.
        repeat(5) { assertTrue(streak.onHold() != Alert.ALARM) }
        assertEquals(Alert.ALARM, streak.onHold())
    }

    @Test
    fun `holds with normal ticks between them never show anything`() {
        val streak = AimiSafeHoldStreak()
        repeat(10) {
            assertEquals(Alert.NONE, streak.onHold())
            assertEquals(Alert.NONE, streak.onHold())
            assertFalse(streak.onNormalTick())
        }
    }

    @Test
    fun `wrong thresholds are refused`() {
        assertFailsWith<IllegalArgumentException> { AimiSafeHoldStreak(noticeThreshold = 0) }
        assertFailsWith<IllegalArgumentException> { AimiSafeHoldStreak(noticeThreshold = 4, alarmThreshold = 3) }
    }
}
