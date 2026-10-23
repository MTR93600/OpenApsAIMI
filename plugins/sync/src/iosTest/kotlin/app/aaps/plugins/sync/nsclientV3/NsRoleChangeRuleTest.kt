package app.aaps.plugins.sync.nsclientV3

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * When a former client becomes the master, the rows it already holds must not be uploaded.
 *
 * The danger is one-sided: missing the change sends old rows that overwrite the real master's data
 * in Nightscout, while doing it once too often only skips rows that were never meant to be sent.
 */
class NsRoleChangeRuleTest {

    private var stored: String? = null
    private var marked = 0

    private val rule = NsRoleChangeRule(
        readRole = { stored },
        writeRole = { stored = it },
        markAllAsSynced = { marked++ },
        log = {}
    )

    @Test
    fun `a former client that is now the master marks everything as uploaded`() = runTest {
        stored = NsRoleChangeRule.CLIENT

        rule.apply(isClient = false)

        assertEquals(1, marked)
        assertEquals(NsRoleChangeRule.MASTER, stored)
    }

    /** The client builds did not store a role, so nothing stored means "was a client". */
    @Test
    fun `no stored role on a master is treated as a change`() = runTest {
        rule.apply(isClient = false)

        assertEquals(1, marked)
        assertEquals(NsRoleChangeRule.MASTER, stored)
    }

    /** Every later start of the master must leave the marks alone, or nothing would ever upload. */
    @Test
    fun `a master that was already a master changes nothing`() = runTest {
        stored = NsRoleChangeRule.MASTER

        rule.apply(isClient = false)
        rule.apply(isClient = false)

        assertEquals(0, marked)
    }

    @Test
    fun `it happens only once`() = runTest {
        rule.apply(isClient = false)
        rule.apply(isClient = false)

        assertEquals(1, marked)
    }

    /** A client uploads nothing, so becoming one needs no marks moved. */
    @Test
    fun `a master that becomes a client only records it`() = runTest {
        stored = NsRoleChangeRule.MASTER

        rule.apply(isClient = true)

        assertEquals(0, marked)
        assertEquals(NsRoleChangeRule.CLIENT, stored)
    }

    @Test
    fun `client to master to client to master marks twice`() = runTest {
        stored = NsRoleChangeRule.CLIENT

        rule.apply(isClient = false)
        rule.apply(isClient = true)
        rule.apply(isClient = false)

        assertEquals(2, marked)
    }

    /** A master build ran before the role was written: its pending rows must still be uploaded. */
    @Test
    fun `a phone that already uploaded as master keeps its pending rows`() = runTest {
        val alreadyMaster = NsRoleChangeRule(
            readRole = { stored },
            writeRole = { stored = it },
            markAllAsSynced = { marked++ },
            alreadyMaster = { true },
            log = {}
        )

        alreadyMaster.apply(isClient = false)

        assertEquals(0, marked)
        assertEquals(NsRoleChangeRule.MASTER, stored)
    }
}
