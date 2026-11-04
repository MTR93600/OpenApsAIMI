package app.aaps.plugins.dexcomoneplus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OnePlusCommLogTest {

    @Test
    fun keepsLinesOldestFirst() {
        val log = OnePlusCommLog()
        log.add(1_000L, "INFO", "first")
        log.add(2_000L, "WARN", "second")

        val lines = log.lines(nowMs = 3_000L)

        assertEquals(listOf("first", "second"), lines.map { it.text })
        assertEquals("WARN", lines.last().level)
    }

    @Test
    fun dropsLinesOlderThanOneDay() {
        val log = OnePlusCommLog()
        log.add(0L, "INFO", "old")
        log.add(OnePlusCommLog.MAX_AGE_MS, "INFO", "new")

        assertEquals(listOf("old", "new"), log.lines(OnePlusCommLog.MAX_AGE_MS).map { it.text })
        assertEquals(listOf("new"), log.lines(OnePlusCommLog.MAX_AGE_MS + 1L).map { it.text })
    }

    @Test
    fun neverKeepsMoreThanTheLimit() {
        val log = OnePlusCommLog(maxLines = 3)
        for (i in 1..5) log.add(i.toLong(), "DEBUG", "line $i")

        assertEquals(listOf("line 3", "line 4", "line 5"), log.lines(6L).map { it.text })
    }

    @Test
    fun clearForgetsEverything() {
        val log = OnePlusCommLog()
        log.add(1L, "INFO", "a")
        log.clear()

        assertTrue(log.lines(2L).isEmpty())
    }
}
