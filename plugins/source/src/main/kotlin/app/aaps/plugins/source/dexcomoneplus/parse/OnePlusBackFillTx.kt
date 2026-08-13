package app.aaps.plugins.source.dexcomoneplus.parse

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Control BackFill request for short-TxId / G7 / ONE+ (opcode 0x59).
 *
 * Layout (9 bytes, no CRC): opcode | startDex(u32 LE) | endDex(u32 LE)
 *
 * Provenance: xDrip `BackFillTxMessage2` at pin
 * `1e86d9a2a52577ed2c30fbf7b69d75fd56e6918f` (GPL-3.0).
 */
object OnePlusBackFillTx {

    const val OPCODE: Byte = 0x59
    const val PACKET_LENGTH: Int = 9

    fun build(startDexTimeSeconds: Int, endDexTimeSeconds: Int): ByteArray {
        val buf = ByteBuffer.allocate(PACKET_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
        buf.put(OPCODE)
        buf.putInt(startDexTimeSeconds)
        buf.putInt(endDexTimeSeconds)
        return buf.array()
    }
}
