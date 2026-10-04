package app.aaps.core.objects.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * AES-CMAC and AES-CCM, on every platform. The expectations are the published packets, not a
 * round trip of this implementation against itself.
 */
class LinkCryptoAesVectorsTest {

    private val sut: LinkCrypto = platformLinkCrypto()

    @Test
    fun `aes cmac matches RFC 4493 example 1 empty message`() {
        assertEquals(CMAC_EMPTY, sut.aesCmac(CMAC_KEY.fromHexString(), ByteArray(0)).toHexString())
    }

    @Test
    fun `aes cmac matches RFC 4493 example 2`() {
        assertEquals(CMAC_16, sut.aesCmac(CMAC_KEY.fromHexString(), CMAC_M16.fromHexString()).toHexString())
    }

    @Test
    fun `aes cmac matches RFC 4493 example 3`() {
        assertEquals(CMAC_40, sut.aesCmac(CMAC_KEY.fromHexString(), CMAC_M40.fromHexString()).toHexString())
    }

    @Test
    fun `aes cmac matches RFC 4493 example 4`() {
        assertEquals(CMAC_64, sut.aesCmac(CMAC_KEY.fromHexString(), CMAC_M64.fromHexString()).toHexString())
    }

    @Test
    fun `aes ccm encrypt matches RFC 3610 packets 1 to 24`() {
        for (packet in RFC_3610_PACKETS) {
            val out = sut.aesCcmEncrypt(
                packet.key.fromHexString(),
                packet.nonce.fromHexString(),
                packet.plaintext.fromHexString(),
                packet.associatedData.fromHexString(),
                packet.tagBits,
            )
            assertEquals(packet.ciphertextAndTag, out.toHexString(), "RFC 3610 packet ${packet.number}")
        }
    }

    @Test
    fun `aes ccm decrypt matches RFC 3610 packets 1 to 24`() {
        for (packet in RFC_3610_PACKETS) {
            val plain = sut.aesCcmDecrypt(
                packet.key.fromHexString(),
                packet.nonce.fromHexString(),
                packet.ciphertextAndTag.fromHexString(),
                packet.associatedData.fromHexString(),
                packet.tagBits,
            )
            assertEquals(packet.plaintext, plain.toHexString(), "RFC 3610 packet ${packet.number}")
        }
    }

    @Test
    fun `aes ccm matches NIST SP 800-38C examples C1 to C3`() {
        for (example in NIST_CCM) {
            val out = sut.aesCcmEncrypt(
                example.key.fromHexString(),
                example.nonce.fromHexString(),
                example.plaintext.fromHexString(),
                example.associatedData.fromHexString(),
                example.tagBits,
            )
            assertEquals(example.ciphertextAndTag, out.toHexString(), example.name)
            val plain = sut.aesCcmDecrypt(
                example.key.fromHexString(),
                example.nonce.fromHexString(),
                example.ciphertextAndTag.fromHexString(),
                example.associatedData.fromHexString(),
                example.tagBits,
            )
            assertEquals(example.plaintext, plain.toHexString(), example.name)
        }
    }

    /** A flipped tag bit must fail the whole decrypt. The plaintext is not returned. */
    @Test
    fun `aes ccm decrypt rejects a flipped tag bit`() {
        val packet = RFC_3610_PACKETS.first()
        val broken = packet.ciphertextAndTag.fromHexString()
        val last = broken.lastIndex
        broken[last] = (broken[last].toInt() xor 0x01).toByte()

        val error = assertFails {
            sut.aesCcmDecrypt(
                packet.key.fromHexString(),
                packet.nonce.fromHexString(),
                broken,
                packet.associatedData.fromHexString(),
                packet.tagBits,
            )
        }
        assertIs<AesCcmAuthenticationException>(error)
        assertTrue(error.message!!.contains("authentication tag"))
    }

    @Test
    fun `aes ccm rejects a nonce shorter than 7 bytes`() {
        val error = assertFails {
            sut.aesCcmEncrypt(CMAC_KEY.fromHexString(), ByteArray(6), ByteArray(1), ByteArray(0), tagBits = 64)
        }
        assertIs<IllegalArgumentException>(error)
        assertTrue(error.message!!.contains("nonce"))
    }

    @Test
    fun `aes ccm rejects a nonce longer than 13 bytes`() {
        val error = assertFails {
            sut.aesCcmEncrypt(CMAC_KEY.fromHexString(), ByteArray(14), ByteArray(1), ByteArray(0), tagBits = 64)
        }
        assertIs<IllegalArgumentException>(error)
        assertTrue(error.message!!.contains("nonce"))
    }

    @Test
    fun `aes ccm rejects an odd tag length`() {
        val error = assertFails {
            sut.aesCcmEncrypt(CMAC_KEY.fromHexString(), ByteArray(13), ByteArray(1), ByteArray(0), tagBits = 40)
        }
        assertIs<IllegalArgumentException>(error)
        assertTrue(error.message!!.contains("tag"))
    }

    @Test
    fun `aes ccm rejects a tag shorter than 4 bytes`() {
        val error = assertFails {
            sut.aesCcmEncrypt(CMAC_KEY.fromHexString(), ByteArray(13), ByteArray(1), ByteArray(0), tagBits = 24)
        }
        assertIs<IllegalArgumentException>(error)
        assertTrue(error.message!!.contains("tag"))
    }

    /** L = 15 - 13 = 2, so a payload longer than 65535 bytes does not fit in the length field. */
    @Test
    fun `aes ccm rejects a payload that does not fit in L`() {
        val error = assertFails {
            sut.aesCcmEncrypt(CMAC_KEY.fromHexString(), ByteArray(13), ByteArray(65536), ByteArray(0), tagBits = 32)
        }
        assertIs<IllegalArgumentException>(error)
        assertTrue(error.message!!.contains("L"))
    }

    @Test
    fun `aes ccm rejects a ciphertext shorter than the tag`() {
        val error = assertFails {
            sut.aesCcmDecrypt(CMAC_KEY.fromHexString(), ByteArray(13), ByteArray(3), ByteArray(0), tagBits = 32)
        }
        assertIs<IllegalArgumentException>(error)
        assertTrue(error.message!!.contains("tag"))
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

        /** NIST SP 800-38C appendix C, examples 1 to 3. `ciphertextAndTag` is the published C. */
        private val NIST_CCM = listOf(
            NistCcm(
                "C.1",
                "404142434445464748494a4b4c4d4e4f",
                "10111213141516",
                "0001020304050607",
                "20212223",
                "7162015b4dac255d",
                32,
            ),
            NistCcm(
                "C.2",
                "404142434445464748494a4b4c4d4e4f",
                "1011121314151617",
                "000102030405060708090a0b0c0d0e0f",
                "202122232425262728292a2b2c2d2e2f",
                "d2a1f0e051ea5f62081a7792073d593d1fc64fbfaccd",
                48,
            ),
            NistCcm(
                "C.3",
                "404142434445464748494a4b4c4d4e4f",
                "101112131415161718191a1b",
                "000102030405060708090a0b0c0d0e0f10111213",
                "202122232425262728292a2b2c2d2e2f3031323334353637",
                "e3b201a9f5b71a7a9b1ceaeccd97e70b6176aad9a4428aa5484392fbc1b09951",
                64,
            ),
        )
    }
}

private data class NistCcm(
    val name: String,
    val key: String,
    val nonce: String,
    val associatedData: String,
    val plaintext: String,
    val ciphertextAndTag: String,
    val tagBits: Int,
)
