package app.aaps.plugins.aps.openAPSAIMI.llm.claude

import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile

/**
 * Single source of truth for the Claude model id used by every AIMI LLM path
 * (AI Coach, AI Auditor + profile factors, TPO veto, Context parser, Meal vision, Physio narrative).
 *
 * The id is chosen by the user in Preferences → AI keys → "Claude model"
 * ([AimiStringKey.AimiAdvisorClaudeModel]). Before the plugin binds [Preferences]
 * (or if the stored value is unknown) [DEFAULT_MODEL] is used.
 */
object ClaudeModelResolver {

    const val SONNET_5 = "claude-sonnet-5"
    const val FABLE_5_1 = "claude-fable-5-1"
    const val OPUS_5_5 = "claude-opus-5-5"
    const val HAIKU_4_5 = "claude-haiku-4-5"

    const val DEFAULT_MODEL = SONNET_5

    val KNOWN_MODELS: Set<String> = setOf(SONNET_5, FABLE_5_1, OPUS_5_5, HAIKU_4_5)

    @Volatile
    private var preferences: Preferences? = null

    /** Called once from OpenAPSAIMIPlugin.onStart(). */
    fun bind(preferences: Preferences) {
        this.preferences = preferences
    }

    /**
     * Joins every `type == "text"` block of a Messages API response.
     * Newer models may put non-text blocks (e.g. `thinking`) before the text, so `content[0].text`
     * is not safe. Throws with a short diagnostic when no text block is present.
     */
    fun extractText(root: JsonObject): String {
        val content = root["content"] as? JsonArray
            ?: throw IllegalStateException("Claude: no content (stop_reason=${root.optStringCompat("stop_reason")})")
        val sb = StringBuilder()
        val types = mutableListOf<String>()
        for (i in 0 until content.size) {
            val block = content.getOrNull(i) as? JsonObject ?: continue
            val type = block.optStringCompat("type")
            types += type
            if (type == "text") sb.append(block.optStringCompat("text"))
        }
        if (sb.isBlank()) {
            throw IllegalStateException(
                "Claude: no text block (stop_reason=${root.optStringCompat("stop_reason")}, blocks=$types)"
            )
        }
        return sb.toString().trim()
    }

    /** Model id for the next Claude request. Never blank, never throws. */
    fun current(): String {
        val stored = try {
            preferences?.get(AimiStringKey.AimiAdvisorClaudeModel)
        } catch (_: Exception) {
            null
        }
        return stored?.takeIf { it in KNOWN_MODELS } ?: DEFAULT_MODEL
    }

    /**
     * True when the model accepts `output_config.effort`.
     * Haiku 4.5 rejects this field with an error, so we must not send it there.
     */
    fun supportsEffort(model: String): Boolean = model != HAIKU_4_5
}
