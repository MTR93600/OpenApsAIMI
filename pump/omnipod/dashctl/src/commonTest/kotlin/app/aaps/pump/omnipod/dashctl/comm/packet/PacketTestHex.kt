package app.aaps.pump.omnipod.dashctl.comm.packet

/**
 * Hex helpers for the packet tests. They replace `Hex.decode` (Tink) and `toHex()` (core:utils)
 * used by the Android tests. Plain Kotlin, so they run on every target. Lower case, like Android.
 */
internal fun packetHex(s: String): ByteArray {
    require(s.length % 2 == 0) { "Hex string must have an even length" }
    return ByteArray(s.length / 2) { i -> s.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

internal fun ByteArray.toPacketHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
