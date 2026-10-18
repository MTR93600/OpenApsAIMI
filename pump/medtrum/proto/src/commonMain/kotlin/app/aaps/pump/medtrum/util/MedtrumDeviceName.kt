package app.aaps.pump.medtrum.util

/**
 * The name a Medtrum pump is shown with, and the serial number inside that name.
 *
 * A pump is listed in the scan step as `MT-` followed by its serial in hex, for example
 * `MT-2F4A1B`. Android builds that text in its scanner and then keeps only the part after `MT-`
 * as the stored serial, reading it back later with radix 16. iOS has to end up with exactly the
 * same number, so both platforms go through the functions here and nowhere else.
 *
 * A pump reached with the wrong serial never connects, and a pump reached with somebody else's
 * serial is worse. So every function returns `null` rather than a guess: there is no default
 * serial, no cached serial and no zero serial. Zero means "no pump" everywhere else in the
 * driver, so it is refused here too.
 */
object MedtrumDeviceName {

    /** What every Medtrum pump name starts with. */
    const val PREFIX = "MT-"

    /** The name shown to the person, for example `MT-2F4A1B`. */
    fun displayName(serial: Long): String = PREFIX + serialHex(serial)

    /** The serial as the hex text that is stored, for example `2F4A1B`. */
    fun serialHex(serial: Long): String = serial.toString(radix = 16).uppercase()

    /**
     * The serial inside an advertised name, or `null` when this is not a Medtrum pump name.
     *
     * Refused: no name, a name without the [PREFIX], nothing after the prefix, a rest that is not
     * hex, and a serial that is not above zero.
     */
    fun serialFromName(name: String?): Long? {
        if (name == null || !name.startsWith(PREFIX)) return null
        return serialFromHex(name.substring(PREFIX.length))
    }

    /**
     * The serial in stored hex text, or `null` when the text is missing, empty, not hex, or not
     * above zero. This is the read-back side of [serialHex] and matches what the Android driver
     * does with `MedtrumStringNonKey.SnInput`.
     */
    fun serialFromHex(hex: String?): Long? {
        if (hex.isNullOrEmpty()) return null
        val serial = hex.toLongOrNull(radix = 16) ?: return null
        return if (serial > 0L) serial else null
    }
}
