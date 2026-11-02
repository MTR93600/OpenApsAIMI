package app.aaps.plugins.aps.openAPSAIMI.context

/**
 * One `AIMI_CONTEXT:` note as it is stored in Nightscout, split into its parts.
 *
 * `ContextManager.syncContextToNS` writes two forms:
 * - `AIMI_CONTEXT:<id>:PIN:<pin>:<json>` when the context was sent with a remote PIN
 * - `AIMI_CONTEXT:<id>:<json>` for the old form, and for a context made on the phone itself
 *
 * The JSON has colons in it, so the last part takes the whole rest of the note.
 *
 * @property pin the PIN of the note, or null for the old form.
 */
internal data class AimiContextNote(
    val intentId: String,
    val pin: String?,
    val intentJson: String
) {

    companion object {

        private val withPin = Regex("^AIMI_CONTEXT:([^:]+):PIN:([^:]*):(.*)$")
        private val legacy = Regex("^AIMI_CONTEXT:([^:]+):(.*)$")

        /** @return the parts of [note], or null when it has neither form. */
        fun parse(note: String): AimiContextNote? {
            withPin.matchEntire(note)?.let { match ->
                return AimiContextNote(match.groupValues[1], match.groupValues[2], match.groupValues[3])
            }
            legacy.matchEntire(note)?.let { match ->
                return AimiContextNote(match.groupValues[1], null, match.groupValues[2])
            }
            return null
        }
    }
}

/**
 * Whether a context from Nightscout may be applied.
 *
 * Only when a remote PIN is set on this phone, and the note carries the same PIN. Spaces around
 * either are ignored. A note without a PIN (the old form) is never accepted.
 */
internal fun isRemotePinAccepted(configuredPin: String, receivedPin: String?): Boolean {
    val configured = configuredPin.trim()
    if (configured.isBlank()) return false
    return receivedPin?.trim().orEmpty() == configured
}
