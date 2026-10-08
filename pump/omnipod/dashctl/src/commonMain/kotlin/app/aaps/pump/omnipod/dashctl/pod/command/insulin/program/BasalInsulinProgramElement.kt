package app.aaps.pump.omnipod.dashctl.pod.command.insulin.program

import app.aaps.pump.omnipod.dashctl.pod.command.insulin.program.util.ProgramBasalUtil
import app.aaps.pump.omnipod.dashctl.pod.definition.Encodable
import app.aaps.pump.omnipod.dashctl.pod.util.PodByteBuffer

open class BasalInsulinProgramElement(
    val startSlotIndex: Byte,
    val numberOfSlots: Byte,
    val totalTenthPulses: Short
) : Encodable {

    override val encoded: ByteArray
        get() = PodByteBuffer.allocate(6)
            .putShort(totalTenthPulses)
            .putInt(if (totalTenthPulses.toInt() == 0) Int.MIN_VALUE or delayBetweenTenthPulsesInUsec else delayBetweenTenthPulsesInUsec)
            .array()
    val durationInSeconds: Short
        get() = (numberOfSlots * 1800).toShort()
    val delayBetweenTenthPulsesInUsec: Int
        get() = if (totalTenthPulses.toInt() == 0) {
            ProgramBasalUtil.MAX_DELAY_BETWEEN_TENTH_PULSES_IN_USEC_AND_USECS_IN_BASAL_SLOT
        } else (ProgramBasalUtil.MAX_DELAY_BETWEEN_TENTH_PULSES_IN_USEC_AND_USECS_IN_BASAL_SLOT.toLong() * numberOfSlots / totalTenthPulses.toDouble()).toInt()

    override fun toString(): String {
        return "LongInsulinProgramElement{" +
            "startSlotIndex=" + startSlotIndex +
            ", numberOfSlots=" + numberOfSlots +
            ", totalTenthPulses=" + totalTenthPulses +
            ", delayBetweenTenthPulsesInUsec=" + delayBetweenTenthPulsesInUsec +
            '}'
    }
}
