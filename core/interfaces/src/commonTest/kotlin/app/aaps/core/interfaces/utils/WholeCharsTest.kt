package app.aaps.core.interfaces.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Text cuts that must never leave half an emoji, which made a device status unreadable. */
class WholeCharsTest {

    private val turtle = "🐢" // 🐢, two UTF-16 units

    @Test
    fun `a cut in the middle of an emoji drops the half`() {
        val text = "pause $turtle now"

        // "pause " is 6 units, so 7 ends between the two halves of the turtle.
        assertEquals("pause ", text.takeWholeChars(7))
    }

    @Test
    fun `a cut after a whole emoji keeps it`() {
        assertEquals("pause $turtle", "pause $turtle now".takeWholeChars(8))
    }

    @Test
    fun `a cut in plain text is the same as take`() {
        assertEquals("hello".take(3), "hello".takeWholeChars(3))
    }

    @Test
    fun `a limit longer than the text keeps all of it`() {
        assertEquals("ok $turtle", "ok $turtle".takeWholeChars(100))
    }

    @Test
    fun `a limit of zero gives an empty text`() {
        assertEquals("", "abc".takeWholeChars(0))
    }

    @Test
    fun `a lone high half is replaced`() {
        assertEquals("pause �", "pause \uD83D".withoutLoneSurrogates())
    }

    @Test
    fun `a lone low half is replaced`() {
        assertEquals("� end", "\uDC22 end".withoutLoneSurrogates())
    }

    @Test
    fun `two high halves in a row are both replaced`() {
        assertEquals("��x", "\uD83D\uD83Dx".withoutLoneSurrogates())
    }

    @Test
    fun `whole emojis are kept`() {
        assertEquals("a $turtle b 🛡", "a $turtle b 🛡".withoutLoneSurrogates())
    }

    @Test
    fun `clean text comes back as the same instance`() {
        val text = "nothing to clean $turtle"

        assertSame(text, text.withoutLoneSurrogates())
    }
}
