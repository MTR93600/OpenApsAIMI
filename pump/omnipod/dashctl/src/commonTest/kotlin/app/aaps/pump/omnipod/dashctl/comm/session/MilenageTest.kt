package app.aaps.pump.omnipod.dashctl.comm.session

import app.aaps.pump.omnipod.dashctl.crypto.DashMilenage
import app.aaps.pump.omnipod.dashctl.parity.parityAesEcb
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of the Android `MilenageTest`, same vectors, on [DashMilenage] (the Milenage that
 * `SessionEstablisher` uses here) with the AES-ECB seam of each target.
 */
class MilenageTest {

    @Test fun testMilenage() {
        val m = DashMilenage(
            aesEcb = parityAesEcb(),
            k = parityHex("c0772899720972a314f557de66d571dd"),
            sqn = byteArrayOf(0, 0, 0, 0, 0, 2),
            randParam = parityHex("c2cd1248451103bd77a6c7ef88c441ba")
        )
        assertEquals("a40bc6d13861447e", m.res.toParityHex())
        assertEquals("55799fd26664cbf6e476525e2dee52c6", m.ck.toParityHex())
        assertEquals("00c55c78e8d3b9b9e935860a7259f6c0", m.autn.toParityHex())
    }

    @Test fun testMilenage2() {
        val m = DashMilenage(
            aesEcb = parityAesEcb(),
            k = parityHex("78411ccad0fd0fb6f381a47fb3335ecb"),
            sqn = byteArrayOf(0, 0, 0, 0, 0, 2), // 1 + 1
            randParam = parityHex("4fc01ac1a94376ae3e052339c07d9e1f")
        )
        assertEquals("ec549e00fa668a19", m.res.toParityHex())
        assertEquals("ee3dac761fe358a9f476cc5ee81aa3e9", m.ck.toParityHex())
        assertEquals("a3e7a71430c8b9b95245b33b3bd679c4", m.autn.toParityHex())
    }

    @Test fun testMilenageIncrementedSQN() {
        val m = DashMilenage(
            aesEcb = parityAesEcb(),
            k = parityHex("c0772899720972a314f557de66d571dd"),
            //  byteArrayOf(0,0,0,0,0x01,0x5d), this is in logs. SQN has to be incremented.
            sqn = byteArrayOf(0, 0, 0, 0, 0x01, 0x5e),
            randParam = parityHex("d71cc44820e5419f42c62ae97c035988")
        )
        assertEquals("5f807a379a5c5d30", m.res.toParityHex())
        assertEquals("8dd4b3ceb849a01766e37f9d86045c39", m.ck.toParityHex())
        assertEquals("0e0264d056fcb9b9752227365a090955", m.autn.toParityHex())
    }

    @Test fun testMileageSynchronization() {
        val m = DashMilenage(
            aesEcb = parityAesEcb(),
            k = parityHex("689b860fde3331dd7e1671ad39985e3b"),
            sqn = byteArrayOf(0, 0, 0, 0, 0, 8), // 1 + 1
            auts = parityHex("84ff173947a67567985de71e4890"),
            randParam = parityHex("396707041ca3a5931fc0e52d2d7b9ecf"),
            amf = byteArrayOf(0, 0),
        )
        assertEquals(m.macS.toParityHex(), m.receivedMacS.toParityHex())
        assertEquals(m.synchronizationSqn.toParityHex(), m.sqn.toParityHex())
    }
}
