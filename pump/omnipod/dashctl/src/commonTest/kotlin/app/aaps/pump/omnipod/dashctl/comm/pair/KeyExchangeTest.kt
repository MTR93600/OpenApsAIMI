package app.aaps.pump.omnipod.dashctl.comm.pair

import app.aaps.pump.omnipod.dashctl.crypto.DashKeyExchange
import app.aaps.pump.omnipod.dashctl.parity.parityAesCmac
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.parity.parityX25519
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Port of the Android `KeyExchangeTest`, same vector, on [DashKeyExchange] (the key exchange that
 * `LTKExchanger` uses here). Android fixes the private key and the nonce with Mockito; here they
 * are passed to the constructor.
 */
class KeyExchangeTest {

    @Test fun testLTK() {
        val pdmNonce = parityHex("edfdacb242c7f4e1d2bc4d93ca3c5706")

        val ke = DashKeyExchange(
            x25519 = parityX25519(),
            aesCmac = parityAesCmac(),
            pdmNonce = pdmNonce,
            pdmPrivate = parityHex("27ec94b71a201c5e92698d668806ae5ba00594c307cf5566e60c1fc53a6f6bb6")
        )
        val podPublicKey = parityHex("2fe57da347cd62431528daac5fbb290730fff684afc4cfc2ed90995f58cb3b74")
        val podNonce = parityHex("00000000000000000000000000000000")
        ke.updatePodPublicData(podPublicKey + podNonce)
        assertEquals("f2b6940243aba536a66e19fb9a39e37f1e76a1cd50ab59b3e05313b4fc93975e", ke.pdmPublic.toParityHex())
        assertEquals("5fc3b4da865e838ceaf1e9e8bb85d1ac", ke.pdmConf.toParityHex())
        ke.validatePodConf(parityHex("af4f10db5f96e5d9cd6cfc1f54f4a92f"))
        assertEquals("341e16d13f1cbf73b19d1c2964fee02b", ke.ltk.toParityHex())
    }
}
