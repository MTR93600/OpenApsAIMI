package app.aaps.core.interfaces.aps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A device status whose log ends in half an emoji could not be read back, and that one status
 * stopped every later upload to Nightscout. The JSON written by [RT.serialize] must never carry such
 * a half, whatever line of the loop produced it.
 */
class RtLoneSurrogateTest {

    private val halfTurtle = "\uD83D" // first half of 🐢

    private fun rtWithHalves(): RT = RT(runningDynamicIsf = false).apply {
        reason.append("basal pause $halfTurtle")
        aimilog.append("aimi $halfTurtle")
        consoleLog = mutableListOf("DECISION_FINAL reason=pause $halfTurtle", "whole 🐢 kept")
        consoleError = mutableListOf("error $halfTurtle")
    }

    @Test
    fun `the written JSON carries no half emoji`() {
        val json = rtWithHalves().serialize()

        assertFalse(json.any { it.isSurrogate() && !isPartOfPair(json, it) }, json)
    }

    @Test
    fun `the written JSON can be read back with the halves replaced`() {
        val back = RT.deserialize(rtWithHalves().serialize())

        assertEquals("basal pause �", back.reason.toString())
        assertEquals("aimi �", back.aimilog.toString())
        assertEquals<List<String>?>(listOf("DECISION_FINAL reason=pause �", "whole 🐢 kept"), back.consoleLog)
        assertEquals<List<String>?>(listOf("error �"), back.consoleError)
    }

    @Test
    fun `the object itself is not changed by writing it`() {
        val rt = rtWithHalves()

        rt.serialize()

        assertEquals("basal pause $halfTurtle", rt.reason.toString())
        assertEquals("DECISION_FINAL reason=pause $halfTurtle", rt.consoleLog?.first())
    }

    @Test
    fun `empty and missing logs are written as before`() {
        val rt = RT(runningDynamicIsf = false)

        val back = RT.deserialize(rt.serialize())

        assertNull(back.consoleLog)
        assertNull(back.consoleError)
        assertEquals(rt.serialize(), RT(runningDynamicIsf = false).serialize())
    }

    @Test
    fun `numbers such as the IOB are written as before`() {
        val rt = RT(runningDynamicIsf = true, IOB = 1.25, COB = 12.0, bg = 133.0)

        val back = RT.deserialize(rt.serialize())

        assertEquals(1.25, back.IOB)
        assertEquals(12.0, back.COB)
        assertEquals(133.0, back.bg)
    }

    /** True when [c], found in [text], belongs to a high-low pair. Checks every position of [c]. */
    private fun isPartOfPair(text: String, c: Char): Boolean {
        for (i in text.indices) {
            if (text[i] != c) continue
            val paired = if (c.isHighSurrogate()) i + 1 < text.length && text[i + 1].isLowSurrogate()
            else i > 0 && text[i - 1].isHighSurrogate()
            if (!paired) return false
        }
        return true
    }
}
