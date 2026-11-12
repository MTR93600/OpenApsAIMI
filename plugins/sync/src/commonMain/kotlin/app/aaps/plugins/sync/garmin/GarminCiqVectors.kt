package app.aaps.plugins.sync.garmin

/**
 * One fixed glucose push, and the bytes Android's `GarminSerializer` produces for it.
 *
 * The map has the same keys, the same order and the same value types as
 * [GarminRequests.getGlucoseMessage]. The hex is the golden: a change in the codec or in this map
 * fails the test, and the Android test checks the same hex against `GarminSerializer` itself.
 */
internal object GarminCiqVectors {

    val glucoseMessage: Map<String, Any> = linkedMapOf(
        "key" to "abc",
        "command" to "glucose",
        "profile" to "D",
        "encodedGlucose" to "0A+6AQ==",
        "remainingInsulin" to 1.25,
        "remainingBasalInsulin" to 0.5,
        "glucoseUnit" to "mgdl",
        "temporaryBasalRate" to 1.0,
        "connected" to true,
        "timestamp" to 1_700_000_000L,
    )

    const val GLUCOSE_JSON: String =
        """{"key":"abc","command":"glucose","profile":"D","encodedGlucose":"0A+6AQ==","remainingInsulin":1.25,"remainingBasalInsulin":0.5,"glucoseUnit":"mgdl","temporaryBasalRate":1.0,"connected":true,"timestamp":1700000000}"""

    const val GLUCOSE_HEX: String =
        "abcdabcd000000b700046b6579000004616263000008636f6d6d616e64000008676c75636f736500000870726f66696c650000024400000f656e636f646564476c75636f736500000930412b3641513d3d00001172656d61696e696e67496e73756c696e00001672656d61696e696e67426173616c496e73756c696e00000c676c75636f7365556e69740000056d67646c00001374656d706f72617279426173616c5261746500000a636f6e6e656374656400000a74696d657374616d7000da7ada7a000000760b0000000a03000000000300000006030000000c03000000160300000020030000002a030000002e030000003f030000004a0f3ff4000000000000030000005d0f3fe000000000000003000000750300000083030000008a0f3ff0000000000000030000009f090103000000ab0e000000006553f100"

    fun glucoseBytes(): ByteArray = hexToBytes(GLUCOSE_HEX)

    fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }
}
