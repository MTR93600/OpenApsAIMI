package app.aaps.pump.omnipod.dashctl.session

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
         */
        fun of(command: DashActiveCommand?, statusResponseCount: Long, lastProgrammingSequence: Int?): DashCommandConfirmation = when {
            command == null                                                     -> NO_ACTIVE_COMMAND
            statusResponseCount > command.statusCountAtCreation &&
                lastProgrammingSequence == command.sequence                     -> SUCCESS

            statusResponseCount > command.statusCountAtCreation                 -> DENIED
            command.sent                                                        -> NOT_CONFIRMED
            else                                                                -> SENDING_FAILURE
        }
    }
}
