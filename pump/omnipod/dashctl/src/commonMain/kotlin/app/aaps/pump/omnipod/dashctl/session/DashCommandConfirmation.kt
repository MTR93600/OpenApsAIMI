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
