package app.aaps.pump.omnipod.dashctl.parity

/** Hex helpers for the parity tests. Plain Kotlin, so they run on every target. */
internal fun parityHex(s: String): ByteArray {
    require(s.length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.toParityHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
