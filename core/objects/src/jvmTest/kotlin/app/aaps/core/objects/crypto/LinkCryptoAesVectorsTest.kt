package app.aaps.core.objects.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

/**
 * AES-CCM and AES-CMAC. JVM only.
 *
 * cryptography-kotlin 0.6.0 implements both on the JDK provider and on neither the Apple
 * CommonCrypto provider nor the CryptoKit provider. Putting these assertions in `commonTest` would
 * make the iOS job fail for a missing algorithm, which is a different fact from a wrong answer.
 * The gap is recorded in `_docs/kmp/crypto-kmp-ios-ecarts.md`.
 */
class LinkCryptoAesVectorsTest {

    private val sut: LinkCrypto = platformLinkCrypto()

    /** RFC 4493 example 1, empty message. */
    @Test
    fun `aes cmac of the empty message matches RFC 4493`() {
        assertEquals(CMAC_EMPTY, sut.aesCmac(CMAC_KEY.fromHexString(), ByteArray(0)).toHexString())
    }

    /** RFC 4493 example 2, one block. */
    @Test
    fun `aes cmac of 16 bytes matches RFC 4493`() {
        assertEquals(CMAC_16, sut.aesCmac(CMAC_KEY.fromHexString(), CMAC_M16.fromHexString()).toHexString())
    }

    /** RFC 4493 example 3, 40 bytes. */
    @Test
    fun `aes cmac of 40 bytes matches RFC 4493`() {
        assertEquals(CMAC_40, sut.aesCmac(CMAC_KEY.fromHexString(), CMAC_M40.fromHexString()).toHexString())
    }

    /** RFC 4493 example 4, 64 bytes. */
    @Test
    fun `aes cmac of 64 bytes matches RFC 4493`() {
        assertEquals(CMAC_64, sut.aesCmac(CMAC_KEY.fromHexString(), CMAC_M64.fromHexString()).toHexString())
    }

    /**
     * RFC 3610 packet vector 1. NIST SP 800-38C is the CCM definition this packet uses: 13-byte
     * nonce, 8-byte associated data (the clear header), 64-bit tag. The header stays outside the
     * ciphertext. The tag is appended, which is what `AES/CCM/NoPadding` returns.
     */
    @Test
    fun `aes ccm encrypt matches RFC 3610 packet vector 1`() {
        val out = sut.aesCcmEncrypt(
            CCM_KEY.fromHexString(),
            CCM_NONCE.fromHexString(),
            CCM_PLAIN.fromHexString(),
            CCM_AAD.fromHexString(),
            tagBits = 64,
        )

        assertEquals(CCM_CIPHER_AND_TAG, out.toHexString())
    }

    /** The same vector, the other way. */
    @Test
    fun `aes ccm decrypt matches RFC 3610 packet vector 1`() {
        val plain = sut.aesCcmDecrypt(
            CCM_KEY.fromHexString(),
            CCM_NONCE.fromHexString(),
            CCM_CIPHER_AND_TAG.fromHexString(),
            CCM_AAD.fromHexString(),
            tagBits = 64,
        )

        assertEquals(CCM_PLAIN, plain.toHexString())
    }

    /** One flipped tag bit must not decrypt. The failure is an exception, not a null. */
    @Test
    fun `aes ccm decrypt rejects a flipped tag bit`() {
        val broken = CCM_CIPHER_AND_TAG.fromHexString()
        val last = broken.lastIndex
        broken[last] = (broken[last].toInt() xor 0x01).toByte()

        val error = assertFails {
            sut.aesCcmDecrypt(
                CCM_KEY.fromHexString(),
                CCM_NONCE.fromHexString(),
                broken,
                CCM_AAD.fromHexString(),
                tagBits = 64,
            )
        }
        // NotImplementedError means the cipher never ran. A rejected tag is a different failure.
        assertFalse(error is NotImplementedError)
    }

    private companion object {

        private const val CMAC_KEY = "2b7e151628aed2a6abf7158809cf4f3c"
        private const val CMAC_EMPTY = "bb1d6929e95937287fa37d129b756746"
        private const val CMAC_M16 = "6bc1bee22e409f96e93d7e117393172a"
        private const val CMAC_16 = "070a16b46b4d4144f79bdd9dd04a287c"
        private const val CMAC_M40 = "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e5130c81c46a35ce411"
        private const val CMAC_40 = "dfa66747de9ae63030ca32611497c827"
        private const val CMAC_M64 =
            "6bc1bee22e409f96e93d7e117393172aae2d8a571e03ac9c9eb76fac45af8e51" +
                "30c81c46a35ce411e5fbc1191a0a52eff69f2445df4f9b17ad2b417be66c3710"
        private const val CMAC_64 = "51f0bebf7e3b9d92fc49741779363cfe"

        private const val CCM_KEY = "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf"
        private const val CCM_NONCE = "00000003020100a0a1a2a3a4a5"
        private const val CCM_AAD = "0001020304050607"
        private const val CCM_PLAIN = "08090a0b0c0d0e0f101112131415161718191a1b1c1d1e"
        private const val CCM_CIPHER_AND_TAG = "588c979a61c663d2f066d0c2c0f989806d5f6b61dac38417e8d12cfdf926e0"
    }
}
