package app.aaps.pump.omnipod.dashctl.parity

import app.aaps.pump.omnipod.dashctl.crypto.AesCcmCipher
import app.aaps.pump.omnipod.dashctl.crypto.AesCmac
import app.aaps.pump.omnipod.dashctl.crypto.AesEcbBlockCipher
import app.aaps.pump.omnipod.dashctl.crypto.X25519Dh

/**
 * The crypto seams have no implementation in commonMain. Each test target supplies its own:
 * SpongyCastle, Tink and javax on the JVM, the pure Kotlin and CommonCrypto classes on iOS.
 * This is what lets one parity test run against both implementations.
 */
internal expect fun parityAesCcm(): AesCcmCipher

internal expect fun parityAesCmac(): AesCmac

internal expect fun parityAesEcb(): AesEcbBlockCipher

internal expect fun parityX25519(): X25519Dh
