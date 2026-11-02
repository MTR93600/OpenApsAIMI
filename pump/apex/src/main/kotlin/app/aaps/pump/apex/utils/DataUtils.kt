package app.aaps.pump.apex.utils

import app.aaps.core.interfaces.profile.Profile
import app.aaps.pump.apex.connectivity.ProtocolVersion
import app.aaps.pump.apex.interfaces.ApexDeviceInfo
import org.joda.time.DateTime

fun Int.shortMSB(): Byte = (this shr 8).toByte()
fun Int.shortLSB(): Byte = toByte()

// Pump uses little-endian numbers
fun Int.asShortAsByteArray(): ByteArray = byteArrayOf(shortLSB(), shortMSB())

// What were they smoking?
fun Byte.hexAsDecToDec(): Int = ((this.toUByte() / 0x10u * 10u) + (this.toUByte() % 0x10u)).toInt()

fun Boolean.toByte(): Byte = if (this) 1 else 0
fun Byte.toBoolean(): Boolean = this.toUByte() > 0U

fun getDateTime(
    data: ByteArray,
    pos: Int,
    apexDeviceInfo: ApexDeviceInfo,
    alwaysNonHex: Boolean = false,
    ignoreTime: Boolean = false,
): DateTime {
    val day: Int
    val month: Int
    val year: Int
    var hour: Int = 0
    var minute: Int = 0
    var second: Int = 0

    if (alwaysNonHex || apexDeviceInfo.version?.atleastProto(ProtocolVersion.PROTO_4_11) == true) {
        year = data[pos].toUInt().toInt() + 2000
        month = data[pos + 1].toUInt().toInt()
        day = data[pos + 2].toUInt().toInt()
        if (!ignoreTime) {
            hour = data[pos + 3].toUInt().toInt()
            minute = data[pos + 4].toUInt().toInt()
            second = data[pos + 5].toUInt().toInt()
        }
    } else {
        year = data[pos].hexAsDecToDec() + 2000
        month = data[pos + 1].hexAsDecToDec()
        day = data[pos + 2].hexAsDecToDec()
        if (!ignoreTime) {
            hour = data[pos + 3].hexAsDecToDec()
            minute = data[pos + 4].hexAsDecToDec()
            second = data[pos + 5].hexAsDecToDec()
        }
    }

    return DateTime(year, month, day, hour, minute, second)
}

fun validateInt(value: Int, min: Int, max: Int): Boolean {
    return value >= min && value <= max
}

fun validateDateTime(
    data: ByteArray,
    pos: Int,
    apexDeviceInfo: ApexDeviceInfo,
    alwaysNonHex: Boolean = false,
    ignoreTime: Boolean = false,
): Boolean {
    val day: Int
    val month: Int
    val year: Int
    var hour: Int = 0
    var minute: Int = 0
    var second: Int = 0

    if (alwaysNonHex || apexDeviceInfo.version?.atleastProto(ProtocolVersion.PROTO_4_11) == true) {
        year = data[pos].toUInt().toInt() + 2000
        month = data[pos + 1].toUInt().toInt()
        day = data[pos + 2].toUInt().toInt()
        if (!ignoreTime) {
            hour = data[pos + 3].toUInt().toInt()
            minute = data[pos + 4].toUInt().toInt()
            second = data[pos + 5].toUInt().toInt()
        }
    } else {
        year = data[pos].hexAsDecToDec() + 2000
        month = data[pos + 1].hexAsDecToDec()
        day = data[pos + 2].hexAsDecToDec()
        if (!ignoreTime) {
            hour = data[pos + 3].hexAsDecToDec()
            minute = data[pos + 4].hexAsDecToDec()
            second = data[pos + 5].hexAsDecToDec()
        }
    }

    if (year < 2010) return false
    if (!validateInt(month, 1, 12)) return false
    if (!validateInt(day, 1, 31)) return false
    if (!validateInt(hour, 0, 23)) return false
    if (!validateInt(minute, 0, 59)) return false
    if (!validateInt(second, 0, 59)) return false
    return true
}

fun getUnsignedShort(data: ByteArray, pos: Int): Int {
    val msb = data[pos + 1].toUByte().toInt()
    val lsb = data[pos].toUByte().toInt()
    return (msb shl 8) or lsb
}

fun getUnsignedInt(data: ByteArray, pos: Int): Int {
    val msb1 = data[pos + 3].toUByte().toInt()
    val msb2 = data[pos + 2].toUByte().toInt()
    val lsb1 = data[pos + 1].toUByte().toInt()
    val lsb2 = data[pos].toUByte().toInt()
    return (msb1 shl 24) or (msb2 shl 16) or (lsb1 shl 8) or lsb2
}

fun Profile.toApexReadableProfile(): List<Double> = List(48) { getBasalTimeFromMidnight(it * 30 * 60) }
