package app.aaps.pump.omnipod.dashctl.comm

import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import kotlin.test.Test
import kotlin.test.assertEquals

/** Android has no test for Ids. The expected ids follow from the Android source of `Ids` and `Id`. */
class IdsTest {

    @Test
    fun `our id is the Android controller id 4242`() {
        assertEquals("00001092", Ids(null).myId.address.toParityHex())
    }

    @Test
    fun `a pod with no unique id gets our id with the low bits set to 01`() {
        assertEquals("00001091", Ids(null).podId.address.toParityHex())
    }

    @Test
    fun `a pod with a unique id gets that id`() {
        assertEquals("02420001", Ids(0x02420001L).podId.address.toParityHex())
    }

    @Test
    fun `a pod that is not paired yet is fffffffe`() {
        assertEquals("fffffffe", Ids.notActivated().address.toParityHex())
    }
}
