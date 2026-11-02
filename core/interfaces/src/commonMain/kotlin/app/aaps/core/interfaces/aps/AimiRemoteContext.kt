package app.aaps.core.interfaces.aps

/**
 * The AIMI context system (sport, stress, illness, ...) as the sync code sees it.
 *
 * The contexts live in `ContextManager` in `:plugins:aps`. The sync module must not depend on that
 * module, so it reaches the contexts only through this interface.
 *
 * Two sides use it:
 * - The master phone gets `AIMI_CONTEXT:` notes from Nightscout and gives them to
 *   [applyNightscoutNote]. A context is only taken when its PIN matches the AIMI remote PIN set on
 *   the master.
 * - The follower phone (AAPSClient) sends a context with [addFromText]. It is stored and uploaded
 *   to Nightscout as an `AIMI_CONTEXT:` note that carries the PIN.
 */
interface AimiRemoteContext {

    /**
     * Apply one note that came from Nightscout.
     *
     * The note may have one of two forms:
     * - `AIMI_CONTEXT:<id>:PIN:<pin>:<json>`
     * - `AIMI_CONTEXT:<id>:<json>` (old form, without a PIN)
     *
     * Any other text is ignored. Never throws.
     *
     * @return true if a new context was added. False if the note is not a context, cannot be read,
     *   has a wrong or missing PIN, or the context is already known.
     */
    fun applyNightscoutNote(note: String): Boolean

    /**
     * Turn [text] into contexts, store them and upload them to Nightscout with [remotePin].
     *
     * @param useLlm ask the AI model to read the text, even when the AI parser is switched off in
     *   the settings. The offline parser is still used when the AI gives no result.
     * @return the ids of the new contexts. Empty if nothing was understood.
     */
    suspend fun addFromText(text: String, useLlm: Boolean, remotePin: String): List<String>
}
