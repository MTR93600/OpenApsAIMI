package app.aaps.pump.omnipod.dashctl.queue.command

import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType

/*
 * The queue commands of the Omnipod driver, the same list and the same status texts as Android
 * `app.aaps.pump.omnipod.common.queue.command` (`:pump:omnipod:common`, Android only). The iOS plugin
 * maps each one to the driver call Android maps it to (`IosDashPluginPump.executeCustomCommand`).
 */

/** Android `CommandSilenceAlerts`: silence the alerts the pod reports. */
class CommandSilenceAlerts : CustomCommand {

    override val statusDescription = "ACKNOWLEDGE ALERTS"
}

/**
 * Silence one pod alert, [alert], from its notice or the pump screen. Not in Android (it only has
 * [CommandSilenceAlerts]); from OmnipodKit (Trio) `acknowledgeAlert`, see `IosDashPump.acknowledgeAlert`.
 */
class CommandAcknowledgeAlert(val alert: AlertType) : CustomCommand {

    override val statusDescription = "ACKNOWLEDGE ALERT"
}

/** Android `CommandResumeDelivery`: send the basal program of the running profile to a suspended pod. */
class CommandResumeDelivery : CustomCommand {

    override val statusDescription = "RESUME DELIVERY"
}

/** Android `CommandDeactivatePod`. */
class CommandDeactivatePod : CustomCommand {

    override val statusDescription = "DEACTIVATE POD"
}

/** Android `CommandUpdateAlertConfiguration`: program the expiration and low reservoir alerts of the user. */
class CommandUpdateAlertConfiguration : CustomCommand {

    override val statusDescription = "UPDATE ALERT CONFIGURATION"
}

/** Android `CommandPlayTestBeep`. */
class CommandPlayTestBeep : CustomCommand {

    override val statusDescription = "PLAY TEST BEEP"
}

/**
 * Android `CommandDisableSuspendAlerts`: turn the "suspend ended" alert off. Android reads its status
 * text from a string resource ("Disable Suspend Alerts"); here the all caps form of the other commands,
 * because this module has no string table yet (phase 5).
 */
class CommandDisableSuspendAlerts : CustomCommand {

    override val statusDescription = "DISABLE SUSPEND ALERTS"
}

/** Android `CommandHandleTimeChange`: send the basal program again after a change of the time. */
class CommandHandleTimeChange(val requestedByUser: Boolean) : CustomCommand {

    override val statusDescription = "HANDLE TIME CHANGE"
}

/**
 * Android `CommandDeliverBasalCorrection` (this fork, `:pump:omnipod:common`): one 0.05 U pulse that
 * makes up basal the pod gave too little. Queued after a confirmed temp basal or cancel when the drift
 * needs it (`DashPluginRules.onOutcome`).
 */
class CommandDeliverBasalCorrection : CustomCommand {

    override val statusDescription = "BASAL CORRECTION"
}
