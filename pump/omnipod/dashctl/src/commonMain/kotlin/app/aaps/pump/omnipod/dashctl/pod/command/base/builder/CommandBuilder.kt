package app.aaps.pump.omnipod.dashctl.pod.command.base.builder

import app.aaps.pump.omnipod.dashctl.pod.command.base.Command

interface CommandBuilder<R : Command> {

    fun build(): R
}
