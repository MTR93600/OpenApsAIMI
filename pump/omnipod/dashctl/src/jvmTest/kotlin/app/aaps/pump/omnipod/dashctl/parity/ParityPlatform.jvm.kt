package app.aaps.pump.omnipod.dashctl.parity

import app.aaps.pump.omnipod.dashctl.crypto.AesCcmCipher
import app.aaps.pump.omnipod.dashctl.crypto.AesCmac
import app.aaps.pump.omnipod.dashctl.crypto.AesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.crypto.JavaxAesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.crypto.SpongyCastleAesCcm
import app.aaps.pump.omnipod.dashctl.crypto.SpongyCastleAesCmac
import app.aaps.pump.omnipod.dashctl.crypto.TinkX25519Dh
import app.aaps.pump.omnipod.dashctl.crypto.X25519Dh

internal actual fun parityAesCcm(): AesCcmCipher = SpongyCastleAesCcm()

internal actual fun parityAesCmac(): AesCmac = SpongyCastleAesCmac()

internal actual fun parityAesEcb(): AesEcbBlockCipher = JavaxAesEcbBlockCipher()

internal actual fun parityX25519(): X25519Dh = TinkX25519Dh()
