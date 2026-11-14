package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus

/**
 * Whether the pod took the active command. Ported from `CommandConfirmationFromState` and
 * `OmnipodDashPodStateManagerImpl.getCommandConfirmationFromState`.
 */
enum class DashCommandConfirmation {

    /** No command is open. */
    NO_ACTIVE_COMMAND,

    /** The bytes never went out: the pod did not get the command. */
    SENDING_FAILURE,

    /** The bytes may have reached the pod and no status came since. Nothing new may be sent. */
    NOT_CONFIRMED,

    /** A later status shows another command as the last one the pod took: it did not take this one. */
    DENIED,

    /** A later status shows this command as the last one the pod took. */
    SUCCESS;

    /** True when the outcome is known and the command can be closed. */
    val isFinal: Boolean get() = this == SENDING_FAILURE || this == DENIED || this == SUCCESS

    companion object {

        /**
         * The same order of checks as Android: a status after the command decides first, then whether
         * the bytes went out.
         *
         * Android bug, kept as is: only the 4-bit sequences are compared. When the last programming
         * command the pod took was built with the same sequence (8 answered exchanges earlier), a
         * status says SUCCESS for a command the pod never got. The caller must not build a delivery
         * command with the sequence the pod reports as its last one; `IosDashPump` reads a status
         * first in that case.
         *
         * Deliberate difference from Android (insulin counted wrong): before DENIED on a sequence that
         * does not match, the delivery status of that later status is checked, as OmnipodKit (Trio)
         * `PodCommsSession.checkCommandAgainstStatus`. A command whose bytes went out ([DashActiveCommand.sent])
         * is taken (SUCCESS) when the pod now does what it asked ([deliveryShowsTaken]): a bolus is
         * running, a temp basal is running, or the basal runs again after a resume. Android says DENIED
         * there, and a bolus the pod gives is then counted as 0 U.
         *
         * @param deliveryStatus the delivery status of the last status, null when none is known.
         * @param trackedBolusRunning a bolus the app follows ([DashPodState.lastBolus]) has not ended:
         *   a bolus seen running is then that one, not this command.
         */
        fun of(
            command: DashActiveCommand?,
            statusResponseCount: Long,
            lastProgrammingSequence: Int?,
            deliveryStatus: DeliveryStatus?,
            trackedBolusRunning: Boolean,
        ): DashCommandConfirmation = when {
            command == null                                                     -> NO_ACTIVE_COMMAND
            statusResponseCount > command.statusCountAtCreation &&
                lastProgrammingSequence == command.sequence                     -> SUCCESS

            statusResponseCount > command.statusCountAtCreation && command.sent &&
                deliveryShowsTaken(command.kind, deliveryStatus, trackedBolusRunning) -> SUCCESS

            statusResponseCount > command.statusCountAtCreation                 -> DENIED
            command.sent                                                        -> NOT_CONFIRMED
            else                                                                -> SENDING_FAILURE
        }

        /**
         * OmnipodKit `checkCommandAgainstStatus`, for the three kinds it checks on a start: true when
         * [deliveryStatus] shows what a command of [kind] starts. Every other kind is never taken this
         * way (a stop, a suspend and a basal program keep the sequence check only).
         */
        fun deliveryShowsTaken(kind: DashCommandKind, deliveryStatus: DeliveryStatus?, trackedBolusRunning: Boolean): Boolean {
            if (deliveryStatus == null) return false
            return when (kind) {
                DashCommandKind.BOLUS           -> deliveryStatus.bolusDeliveringActive() && !trackedBolusRunning
                DashCommandKind.TEMP_BASAL      -> deliveryStatus.tempBasalActive()
                DashCommandKind.RESUME_DELIVERY -> deliveryStatus.basalActive()
                else                            -> false
            }
        }
    }
}
