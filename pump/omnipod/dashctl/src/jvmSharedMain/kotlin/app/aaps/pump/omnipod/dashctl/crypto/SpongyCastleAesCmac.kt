package app.aaps.pump.omnipod.dashctl.crypto

import org.spongycastle.crypto.engines.AESEngine
import org.spongycastle.crypto.macs.CMac
import org.spongycastle.crypto.params.KeyParameter

/** [AesCmac] on SpongyCastle, matching the Android `KeyExchange.aesCmac` construction exactly. */
class SpongyCastleAesCmac : AesCmac {

    override fun compute(key: ByteArray, data: ByteArray): ByteArray {
        require(key.size == 16) { "AES-CMAC key must be 16 bytes" }
        val mac = CMac(AESEngine())
        mac.init(KeyParameter(key))
        mac.update(data, 0, data.size)
        val out = ByteArray(mac.macSize)
        mac.doFinal(out, 0)
        return out
    }
}
