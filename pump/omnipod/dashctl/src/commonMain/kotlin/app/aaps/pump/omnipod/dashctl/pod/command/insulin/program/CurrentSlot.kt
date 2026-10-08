package app.aaps.pump.omnipod.dashctl.pod.command.insulin.program

class CurrentSlot(
    val index: Byte,
    val eighthSecondsRemaining: Short,
    val pulsesRemaining: Short
) {

    override fun toString(): String = "CurrentSlot{" +
        "index=" + index +
        ", eighthSecondsRemaining=" + eighthSecondsRemaining +
        ", pulsesRemaining=" + pulsesRemaining +
        '}'
}
