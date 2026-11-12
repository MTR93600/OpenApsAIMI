package app.aaps.pump.omnipod.dashctl.comm

/**
 * Lower case hex text of a byte array, two characters per byte, no separator.
 *
 * Same text as `toHex()` from `:core:utils`, which `:pump:omnipod:dashctl` does not depend on.
 * Used only to build log and exception messages, never in protocol logic.
 */
internal fun ByteArray.toCommLogHex(): String =
    joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
