package app.aaps.pump.omnipod.dashctl.parity

/**
 * Byte vectors recorded from the Android Dash driver tests.
 *
 * Android is the reference: it is proven against real pods. Every value below is copied
 * from a test in `pump/omnipod/dash/src/test/kotlin/app/aaps/pump/omnipod/common/bledriver/comm`
 * (the driver tests live in the `dash` module, the code under test lives in `pump/omnipod/common`).
 * The source test is named next to each value. Do not edit a value to make a test pass.
 * If a value looks wrong, check the Android test it came from.
 *
 * Hex strings have no separators. The Android tests write them as `54,57,11,...` and strip
 * the commas; the bytes are the same.
 */
internal object AndroidRecordedVectors {

    /**
     * Source: `message/MessagePacketTest` (`payload`, used by `testParseMessagePacket` and
     * `testSerializeMessagePacket`). A real encrypted command packet, pod 136326825 as destination.
     */
    object CapturedCommandPacket {
        const val WIRE_HEX =
            "54571101070003400820" + "2ea808202ea9" + // 16 byte header
                "ab35d831609bb8fe3a3bde5b1837249a16dbf8e4d305e975dc817c3707cc415faf8a" // ciphertext + 8 byte MAC
        const val HEADER_HEX = "545711010700034008202ea808202ea9"
        const val PAYLOAD_HEX = "ab35d831609bb8fe3a3bde5b1837249a16dbf8e4d305e975dc817c3707cc415faf8a"

        // Fields as asserted by `testParseMessagePacket`.
        const val SOURCE = 136326824L
        const val DESTINATION = 136326825L
        const val SEQUENCE_NUMBER = 7
        const val ACK_NUMBER = 0
        const val EQOS = 1
        const val SAS = true
        const val TFS = false
        const val PRIORITY = false
        const val LAST_MESSAGE = false
        const val GATEWAY = false
        const val VERSION = 0
    }

    /**
     * Source: `endecrypt/EnDecryptTest.encrypt`. The header, the wrapped command and the
     * expected wire bytes are taken from the test as written there.
     */
    object EncryptCommand {
        const val CK = "ba1283744b6de9fab6d9b77d95a71d6e"
        const val NONCE_PREFIX = "dda23c090a0a0a0a"
        const val NONCE_SQN = 0L

        /** The 16 byte header. Flags 0x11 0x01 (eqos 1), outer sequence 7, size field 0x0340 = 26 bytes. */
        const val HEADER_HEX = "54571101070003400242000002420001"

        /** Wrapped command: `S0.0=` + 2 byte length + inner command + `,G0.0`. */
        const val WRAPPED_COMMAND_HEX = "53302e303d000effffffff00060704ffffffff82b22c47302e30"

        /** The inner command inside the wrapper (14 bytes, as the length 0x000e says). */
        const val INNER_COMMAND_HEX = "ffffffff00060704ffffffff82b2"

        /** Expected ciphertext (26 bytes) followed by the 8 byte MAC. */
        const val CIPHERTEXT_AND_MAC_HEX =
            "e09158bcb0285a81bf30635f3a17ee73f0afbb3286bc524a8a66" + "fb1bc5b001e56543"
        const val WIRE_HEX = HEADER_HEX + CIPHERTEXT_AND_MAC_HEX
    }

    /**
     * Source: `endecrypt/EnDecryptTest.decrypt`. A real answer of a pod. Note the outer
     * sequence 0x0c and ack number 0x16 (22): the outer counter is not limited to 4 bits.
     */
    object DecryptResponse {
        const val CK = "55799fd26664cbf6e476525e2dee52c6"
        const val NONCE_PREFIX = "6cff5d18b7616cae"
        const val NONCE_SQN = 22L
        const val WIRE_HEX =
            "545711a10c160300" + "08202ea908202ea8" +
                "347cb97b385d45a3c40e404c55715ef3c3865017367e623c7d0b469e81cdfd9a"
        const val HEADER_HEX = "545711a10c16030008202ea908202ea8"
        const val CIPHERTEXT_AND_MAC_HEX = "347cb97b385d45a3c40e404c55715ef3c3865017367e623c7d0b469e81cdfd9a"
        const val PLAINTEXT_HEX = "302e303d001208202ea91c0a1d050016b00000000bff01fe"
    }

    /** Source: `message/StringLengthPrefixEncodingTest` ("from logs"). */
    object KeyValueP0 {
        const val KEY = "P0="
        const val VALUE_HEX = "a5"
        const val ENCODED_HEX = "50303d0001a5"
    }

    /** Source: `session/MilenageTest.testMilenage`. */
    object Milenage1 {
        const val K = "c0772899720972a314f557de66d571dd"
        const val SQN = "000000000002"
        const val RAND = "c2cd1248451103bd77a6c7ef88c441ba"
        const val RES = "a40bc6d13861447e"
        const val CK = "55799fd26664cbf6e476525e2dee52c6"
        const val AUTN = "00c55c78e8d3b9b9e935860a7259f6c0"
    }

    /** Source: `session/MilenageTest.testMilenage2`. */
    object Milenage2 {
        const val K = "78411ccad0fd0fb6f381a47fb3335ecb"
        const val SQN = "000000000002"
        const val RAND = "4fc01ac1a94376ae3e052339c07d9e1f"
        const val RES = "ec549e00fa668a19"
        const val CK = "ee3dac761fe358a9f476cc5ee81aa3e9"
        const val AUTN = "a3e7a71430c8b9b95245b33b3bd679c4"
    }

    /** Source: `session/MilenageTest.testMilenageIncrementedSQN`. The SQN in the logs was 0x015d, it is incremented. */
    object Milenage3 {
        const val K = "c0772899720972a314f557de66d571dd"
        const val SQN = "00000000015e"
        const val RAND = "d71cc44820e5419f42c62ae97c035988"
        const val RES = "5f807a379a5c5d30"
        const val CK = "8dd4b3ceb849a01766e37f9d86045c39"
        const val AUTN = "0e0264d056fcb9b9752227365a090955"
    }

    /** Source: `session/MilenageTest.testMileageSynchronization`. Resynchronisation with AUTS. */
    object MilenageResync {
        const val K = "689b860fde3331dd7e1671ad39985e3b"
        const val SQN = "000000000008"
        const val AUTS = "84ff173947a67567985de71e4890"
        const val RAND = "396707041ca3a5931fc0e52d2d7b9ecf"
        const val AMF = "0000"
    }

    /** Source: `pair/KeyExchangeTest.testLTK`. */
    object KeyExchangeLtk {
        const val PDM_PRIVATE = "27ec94b71a201c5e92698d668806ae5ba00594c307cf5566e60c1fc53a6f6bb6"
        const val PDM_NONCE = "edfdacb242c7f4e1d2bc4d93ca3c5706"
        const val POD_PUBLIC = "2fe57da347cd62431528daac5fbb290730fff684afc4cfc2ed90995f58cb3b74"
        const val POD_NONCE = "00000000000000000000000000000000"
        const val PDM_PUBLIC = "f2b6940243aba536a66e19fb9a39e37f1e76a1cd50ab59b3e05313b4fc93975e"
        const val PDM_CONF = "5fc3b4da865e838ceaf1e9e8bb85d1ac"
        const val POD_CONF = "af4f10db5f96e5d9cd6cfc1f54f4a92f"
        const val LTK = "341e16d13f1cbf73b19d1c2964fee02b"
    }

    /** Source: `session/EapMessageTest.testParseAndBack`. An EAP-AKA challenge, identifier 0xbd. */
    object EapChallenge {
        const val IDENTIFIER = 0xbd
        const val PACKET_HEX =
            "01bd0038170100000205000000c55c78e8d3b9b9e935860a7259f6c0" +
                "01050000c2cd1248451103bd77a6c7ef88c441ba" +
                "7e0200006cff5d18"
        const val AUTN = "00c55c78e8d3b9b9e935860a7259f6c0"
        const val RAND = "c2cd1248451103bd77a6c7ef88c441ba"
        const val IV = "6cff5d18"
    }

    /**
     * Source: `pod/command/ProgramBolusCommandTest` (the `dash` module, same golden in `dashctl`).
     * A 5 U bolus: unique id 37879809 (0x02420001), sequence 14, nonce 1229869870, pulse rate 16,
     * reminder (false, true, 0). The first pulse delay is `00030D40`; a pulse rate of 2 gives `00000000`.
     */
    object BolusCommand {
        const val UNIQUE_ID = 37879809L
        const val NONCE = 1229869870
        const val NONCE_HEX = "494e532e"
        const val HEX = "02420001381f1a0e494e532e02010f01064000640064170d4003e800030d4000000000000080f6"
        const val FIRST_PULSE_DELAY_HEX = "00030d40"
    }
}
