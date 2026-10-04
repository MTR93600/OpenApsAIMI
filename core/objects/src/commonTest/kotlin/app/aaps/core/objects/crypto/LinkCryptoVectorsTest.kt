package app.aaps.core.objects.crypto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cross-platform vectors. They live in `commonTest` so the JVM and the iOS simulator run the same
 * assertions. AES-CCM and AES-CMAC are not here: cryptography-kotlin 0.6.0 has no iOS provider for
 * them, and a test that called them would fail for that reason rather than for a wrong answer.
 */
class LinkCryptoVectorsTest {

    private val sut: LinkCrypto = platformLinkCrypto()

    /** RFC 7748 section 6.1. */
    @Test
    fun `x25519 public key matches RFC 7748 Alice`() {
        assertEquals(ALICE_PUBLIC, sut.x25519Public(ALICE_PRIVATE.fromHexString()).toHexString())
    }

    /** RFC 7748 section 6.1. */
    @Test
    fun `x25519 public key matches RFC 7748 Bob`() {
        assertEquals(BOB_PUBLIC, sut.x25519Public(BOB_PRIVATE.fromHexString()).toHexString())
    }

    /** RFC 7748 section 6.1, both directions. A shared secret that depends on who starts is wrong. */
    @Test
    fun `x25519 shared secret matches RFC 7748`() {
        val alice = sut.x25519Shared(ALICE_PRIVATE.fromHexString(), BOB_PUBLIC.fromHexString()).toHexString()
        val bob = sut.x25519Shared(BOB_PRIVATE.fromHexString(), ALICE_PUBLIC.fromHexString()).toHexString()

        assertEquals(SHARED, alice)
        assertEquals(SHARED, bob)
    }

    /**
     * Wycheproof `ecdh_secp256r1_test.json` tcId 1 (source google-wycheproof 0.9rc5, flag Normal).
     * The public point is the uncompressed form inside that vector's SPKI.
     */
    @Test
    fun `ecdh P-256 shared secret matches Wycheproof tcId 1`() {
        val shared = sut.ecdhP256Shared(ECDH_PRIVATE.fromHexString(), ECDH_PUBLIC.fromHexString())

        assertEquals(ECDH_SHARED, shared.toHexString())
    }

    /**
     * Wycheproof `ecdsa_secp256r1_sha256_p1363_test.json` tcId 1. The signature is raw `r || s`.
     * Message bytes are the ASCII of `123400`.
     */
    @Test
    fun `ecdsa P-256 verifies Wycheproof tcId 1`() {
        val ok = sut.ecdsaP256VerifySha256(
            ECDSA_PUBLIC.fromHexString(),
            ECDSA_MESSAGE.fromHexString(),
            ECDSA_SIGNATURE.fromHexString(),
        )

        assertTrue(ok)
    }

    /** The same file, tcId 11: r = 0 and s = 0, flagged InvalidSignature. */
    @Test
    fun `ecdsa P-256 rejects Wycheproof tcId 11`() {
        val ok = sut.ecdsaP256VerifySha256(
            ECDSA_PUBLIC.fromHexString(),
            ECDSA_MESSAGE.fromHexString(),
            ECDSA_ZERO_SIGNATURE.fromHexString(),
        )

        assertFalse(ok)
    }

    private companion object {

        private const val ALICE_PRIVATE = "77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a"
        private const val ALICE_PUBLIC = "8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a"
        private const val BOB_PRIVATE = "5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb"
        private const val BOB_PUBLIC = "de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f"
        private const val SHARED = "4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742"

        private const val ECDH_PRIVATE = "0612465c89a023ab17855b0a6bcebfd3febb53aef84138647b5352e02c10c346"
        private const val ECDH_PUBLIC =
            "0462d5bd3372af75fe85a040715d0f502428e07046868b0bfdfa61d731afe44f26" +
                "ac333a93a9e70a81cd5a95b5bf8d13990eb741c8c38872b4a07d275a014e30cf"
        private const val ECDH_SHARED = "53020d908b0219328b658b525f26780e3ae12bcd952bb25a93bc0895e1714285"

        private const val ECDSA_PUBLIC =
            "042927b10512bae3eddcfe467828128bad2903269919f7086069c8c4df6c732838" +
                "c7787964eaac00e5921fb1498a60f4606766b3d9685001558d1a974e7341513e"
        private const val ECDSA_MESSAGE = "313233343030"
        private const val ECDSA_SIGNATURE =
            "2ba3a8be6b94d5ec80a6d9d1190a436effe50d85a1eee859b8cc6af9bd5c2e18" +
                "4cd60b855d442f5b3c7b11eb6c4e0ae7525fe710fab9aa7c77a67f79e6fadd76"
        private const val ECDSA_ZERO_SIGNATURE =
            "0000000000000000000000000000000000000000000000000000000000000000" +
                "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
