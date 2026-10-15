package app.aaps.pump.omnipod.dashctl.parity

import app.aaps.pump.omnipod.dashctl.crypto.AesCcmCipher
import app.aaps.pump.omnipod.dashctl.crypto.AesCmac
import app.aaps.pump.omnipod.dashctl.crypto.AesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.crypto.X25519Dh
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCcmCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesCmac
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosAesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.ios.crypto.IosX25519Dh

internal actual fun parityAesCcm(): AesCcmCipher = IosAesCcmCipher()

internal actual fun parityAesCmac(): AesCmac = IosAesCmac()

internal actual fun parityAesEcb(): AesEcbBlockCipher = IosAesEcbBlockCipher()

internal actual fun parityX25519(): X25519Dh = IosX25519Dh()
