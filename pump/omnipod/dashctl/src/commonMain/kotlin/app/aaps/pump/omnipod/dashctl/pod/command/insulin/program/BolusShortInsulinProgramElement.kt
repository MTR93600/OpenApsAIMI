package app.aaps.pump.omnipod.dashctl.pod.command.insulin.program

import app.aaps.pump.omnipod.dashctl.pod.util.PodByteBuffer

class BolusShortInsulinProgramElement(
    private val numberOfPulses: Short
) : ShortInsulinProgramElement {

    override val encoded: ByteArray
        get() = PodByteBuffer.allocate(2).putShort(numberOfPulses).array()
}
