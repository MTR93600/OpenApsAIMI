package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rule "never announce the same verdict twice", without any Apple call. */
class AuditorAnnouncerTest {

    private var posts = 0

    private fun announcer(succeeds: () -> Boolean = { true }) =
        AuditorAnnouncer { posts++; succeeds() }

    @Test
    fun `a new verdict is announced`() {
        assertTrue(announcer().announce(AuditorUIState.ready(2), 100L))
        assertEquals(1, posts)
    }

    @Test
    fun `the same verdict is not announced twice`() {
        val a = announcer()
        assertTrue(a.announce(AuditorUIState.ready(2), 100L))
        assertFalse(a.announce(AuditorUIState.ready(2), 100L))
        assertEquals(1, posts)
    }

    @Test
    fun `a later verdict is announced again`() {
        val a = announcer()
        a.announce(AuditorUIState.ready(2), 100L)
        assertTrue(a.announce(AuditorUIState.ready(2), 200L))
        assertEquals(2, posts)
    }

    @Test
    fun `a failed post does not mark the verdict as announced`() {
        var ok = false
        val a = announcer { ok }
        assertFalse(a.announce(AuditorUIState.ready(2), 100L))
        ok = true
        assertTrue(a.announce(AuditorUIState.ready(2), 100L), "the retry must be allowed")
        assertEquals(2, posts)
    }

    @Test
    fun `a state that does not ask for a notification posts nothing`() {
        val a = announcer()
        assertFalse(a.announce(AuditorUIState.ready(2, shouldNotify = false), 100L))
        assertFalse(a.announce(AuditorUIState.idle(), 100L))
        assertFalse(a.announce(AuditorUIState.processing(), 100L))
        assertEquals(0, posts)
    }

    @Test
    fun `no verdict in the cache posts nothing`() {
        assertFalse(announcer().announce(AuditorUIState.ready(2), null))
        assertEquals(0, posts)
    }
}
