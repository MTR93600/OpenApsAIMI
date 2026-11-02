package app.aaps.plugins.aps.openAPSAIMI.context

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.aps.AimiRemoteContext
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn

/**
 * [AimiRemoteContext] on top of [ContextManager].
 *
 * The note handling is the same as the one in the `NsIncomingDataProcessor` of dev_OAPSAIMI: both
 * note forms are read, the JSON goes through [ContextIntentDeserializer], and the PIN check stays in
 * [ContextManager.injectContextFromNS].
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AimiRemoteContextImpl @Inject constructor(
    private val contextManager: ContextManager,
    private val aapsLogger: AAPSLogger
) : AimiRemoteContext {

    override fun applyNightscoutNote(note: String): Boolean =
        applyAimiContextNote(note, aapsLogger) { intentId, intent, pin -> contextManager.injectContextFromNS(intentId, intent, pin) }

    override suspend fun addFromText(text: String, useLlm: Boolean, remotePin: String): List<String> =
        contextManager.addIntent(text, forceLLM = useLlm, remotePin = remotePin)
}

/**
 * Gives the context system also as `AimiRemoteContext?`.
 *
 * `NsIncomingDataProcessor` takes `AimiRemoteContext? = null`, because the desktop client has no
 * AIMI. For Metro a nullable type is a different key from the plain one, so without this binding
 * it would always use the default `null`, and the master would drop every remote context without a
 * word. A graph that excludes [AimiRemoteContextImpl] must exclude this container too.
 */
@ContributesTo(AppScope::class)
@BindingContainer
object AimiRemoteContextOptionalBinding {

    @Provides
    fun optionalAimiRemoteContext(aimiRemoteContext: AimiRemoteContext): AimiRemoteContext? = aimiRemoteContext
}

/**
 * Read [note] and hand the context to [inject].
 *
 * Kept apart from [AimiRemoteContextImpl] so a shared test can run it without a whole
 * [ContextManager]. The PIN is never written to the log.
 *
 * @return what [inject] returns, or false when the note is not a context or cannot be read.
 */
internal fun applyAimiContextNote(
    note: String,
    aapsLogger: AAPSLogger,
    inject: (intentId: String, intent: ContextIntent, pin: String?) -> Boolean
): Boolean {
    if (!note.startsWith(TE.AIMI_CONTEXT_NOTE_PREFIX)) return false
    return try {
        val parsed = AimiContextNote.parse(note)
        if (parsed == null) {
            aapsLogger.warn(LTag.NSCLIENT, "[NS] AIMI note wrong format")
            return false
        }
        aapsLogger.debug(LTag.NSCLIENT, "[NS] Parsing AIMI context: ${parsed.intentId}")
        val intent = ContextIntentDeserializer.deserialize(parsed.intentJson, aapsLogger)
        if (intent == null) {
            aapsLogger.warn(LTag.NSCLIENT, "[NS] Failed to deserialize AIMI context ${parsed.intentId}")
            return false
        }
        val applied = inject(parsed.intentId, intent, parsed.pin)
        if (applied) aapsLogger.info(LTag.NSCLIENT, "[NS] Injected AIMI context: ${parsed.intentId}")
        applied
    } catch (e: Exception) {
        aapsLogger.error(LTag.NSCLIENT, "[NS] Exception parsing AIMI context: ${e.message}", e)
        false
    }
}
