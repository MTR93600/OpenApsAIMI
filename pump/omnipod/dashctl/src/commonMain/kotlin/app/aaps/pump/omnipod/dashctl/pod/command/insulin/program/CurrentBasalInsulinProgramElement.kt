package app.aaps.pump.omnipod.dashctl.pod.command.insulin.program

class CurrentBasalInsulinProgramElement(
    val index: Byte,
    val delayUntilNextTenthPulseInUsec: Int,
    val remainingTenthPulses: Short
) {

    override fun toString(): String = "CurrentLongInsulinProgramElement{" +
        "index=" + index +
        ", delayUntilNextTenthPulseInUsec=" + delayUntilNextTenthPulseInUsec +
        ", remainingTenthPulses=" + remainingTenthPulses +
        '}'
}
