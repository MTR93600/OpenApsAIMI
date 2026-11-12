package app.aaps.pump.omnipod.dashctl.comm.packet

import app.aaps.pump.omnipod.dashctl.comm.message.CrcMismatchException
import app.aaps.pump.omnipod.dashctl.comm.message.IncorrectPacketException

/**
 * Puts the Bluetooth packets of one message back together and checks the CRC-32.
 *
 * Port of Android `app.aaps.pump.omnipod.common.bledriver.comm.packet.PayloadJoiner`.
 * `java.util.LinkedList` is replaced by a plain [MutableList] (only `add` and `map` are used, so
 * the behaviour is the same), and `java.nio.ByteBuffer` by a direct copy into one array.
 *
 * Note, kept as in Android: [finalize] builds `CrcMismatchException(expected = computed CRC,
 * actual = received CRC)`, so the two names in the error text are swapped.
 */
class PayloadJoiner(firstPacket: ByteArray) {

    var oneExtraPacket: Boolean
    val fullFragments: Int
    var crc: Long = 0
    private var expectedIndex = 0
    private val fragments: MutableList<BlePacket> = mutableListOf()

    init {
        val firstPacket = FirstBlePacket.parse(firstPacket)
        fragments.add(firstPacket)
        fullFragments = firstPacket.fullFragments
        crc = firstPacket.crc32 ?: 0
        oneExtraPacket = firstPacket.oneExtraPacket
    }

    fun accumulate(packet: ByteArray) {
        if (packet.size < 3) { // idx, size, at least 1 byte of payload
            throw IncorrectPacketException(packet, (expectedIndex + 1).toByte())
        }
        val idx = packet[0].toInt()
        if (idx != expectedIndex + 1) {
            throw IncorrectPacketException(packet, (expectedIndex + 1).toByte())
        }
        expectedIndex++
        when {
            idx < fullFragments                        -> {
                fragments.add(MiddleBlePacket.parse(packet))
            }

            idx == fullFragments                       -> {
                val lastPacket = LastBlePacket.parse(packet)
                fragments.add(lastPacket)
                crc = lastPacket.crc32
                oneExtraPacket = lastPacket.oneExtraPacket
            }

            idx == fullFragments + 1 && oneExtraPacket -> {
                fragments.add(LastOptionalPlusOneBlePacket.parse(packet))
            }

            idx > fullFragments                        -> {
                throw IncorrectPacketException(packet, idx.toByte())
            }
        }
    }

    fun finalize(): ByteArray {
        val payloads = fragments.map { x -> x.payload }
        val totalLen = payloads.fold(0) { acc, elem -> acc + elem.size }
        val bytes = ByteArray(totalLen)
        var pos = 0
        for (p in payloads) {
            p.copyInto(bytes, pos)
            pos += p.size
        }
        if (bytes.crc32() != crc) {
            throw CrcMismatchException(bytes.crc32(), crc, bytes)
        }
        return bytes.copyOfRange(0, bytes.size)
    }
}

/** Same as `Integer.toUnsignedLong`: the 32 bits of the Int as a non-negative Long. */
internal fun Int.toUnsignedLong() = this.toLong() and 0xffffffffL
