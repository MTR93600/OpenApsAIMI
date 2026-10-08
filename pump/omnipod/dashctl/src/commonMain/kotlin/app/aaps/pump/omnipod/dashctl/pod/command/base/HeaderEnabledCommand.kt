package app.aaps.pump.omnipod.dashctl.pod.command.base

import app.aaps.pump.omnipod.dashctl.pod.util.MessageUtil
import app.aaps.pump.omnipod.dashctl.pod.util.PodByteBuffer

abstract class HeaderEnabledCommand protected constructor(
    override val commandType: CommandType,
    protected val uniqueId: Int,
    override val sequenceNumber: Short,
    protected val multiCommandFlag: Boolean
) : Command {

    companion object {

        internal fun appendCrc(command: ByteArray): ByteArray =
            PodByteBuffer.allocate(command.size + 2)
                .put(command)
                .putShort(MessageUtil.createCrc(command))
                .array()

        internal fun encodeHeader(
            uniqueId: Int,
            sequenceNumber: Short,
            length: Short,
            multiCommandFlag: Boolean
        ): ByteArray =
            PodByteBuffer.allocate(6)
                .putInt(uniqueId)
                .putShort((sequenceNumber.toInt() and 0x0f shl 10 or length.toInt() or ((if (multiCommandFlag) 1 else 0) shl 15)).toShort())
                .array()

        internal const val HEADER_LENGTH: Short = 6
    }
}
