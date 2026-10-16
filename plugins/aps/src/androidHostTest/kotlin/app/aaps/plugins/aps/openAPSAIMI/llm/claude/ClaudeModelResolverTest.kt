package app.aaps.plugins.aps.openAPSAIMI.llm.claude

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ClaudeModelResolverTest {

    private fun bindStored(value: String?) {
        val preferences = mock<Preferences>()
        whenever(preferences.get(AimiStringKey.AimiAdvisorClaudeModel)).thenReturn(value ?: "")
        ClaudeModelResolver.bind(preferences)
    }

    @Test
    fun `a known stored model is used`() {
        bindStored(ClaudeModelResolver.OPUS_5_5)
        assertThat(ClaudeModelResolver.current()).isEqualTo("claude-opus-5-5")
    }

    @Test
    fun `an unknown stored model falls back to the default`() {
        bindStored("claude-3-haiku-20240307")
        assertThat(ClaudeModelResolver.current()).isEqualTo(ClaudeModelResolver.DEFAULT_MODEL)
    }

    @Test
    fun `an empty stored model falls back to the default`() {
        bindStored(null)
        assertThat(ClaudeModelResolver.current()).isEqualTo("claude-sonnet-5")
    }

    @Test
    fun `a failing preference read falls back to the default`() {
        val preferences = mock<Preferences>()
        whenever(preferences.get(AimiStringKey.AimiAdvisorClaudeModel)).thenThrow(IllegalStateException("boom"))
        ClaudeModelResolver.bind(preferences)
        assertThat(ClaudeModelResolver.current()).isEqualTo(ClaudeModelResolver.DEFAULT_MODEL)
    }

    @Test
    fun `every model in the settings list is known to the resolver`() {
        assertThat(AimiStringKey.AimiAdvisorClaudeModel.entries.keys).containsExactlyElementsIn(ClaudeModelResolver.KNOWN_MODELS)
        assertThat(AimiStringKey.AimiAdvisorClaudeModel.defaultValue).isEqualTo(ClaudeModelResolver.DEFAULT_MODEL)
    }

    @Test
    fun `only haiku does not support effort`() {
        assertThat(ClaudeModelResolver.supportsEffort(ClaudeModelResolver.HAIKU_4_5)).isFalse()
        assertThat(ClaudeModelResolver.supportsEffort(ClaudeModelResolver.SONNET_5)).isTrue()
        assertThat(ClaudeModelResolver.supportsEffort(ClaudeModelResolver.FABLE_5_1)).isTrue()
        assertThat(ClaudeModelResolver.supportsEffort(ClaudeModelResolver.OPUS_5_5)).isTrue()
    }

    private fun parse(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `extractText skips a thinking block and joins text blocks`() {
        val root = parse("""{"content":[{"type":"thinking","thinking":"hmm"},{"type":"text","text":" one "},{"type":"text","text":"two "}]}""")
        assertThat(ClaudeModelResolver.extractText(root)).isEqualTo("one two")
    }

    @Test
    fun `extractText fails when there is no text block`() {
        val root = parse("""{"stop_reason":"max_tokens","content":[{"type":"thinking","thinking":"hmm"}]}""")
        val error = assertThrows<IllegalStateException> { ClaudeModelResolver.extractText(root) }
        assertThat(error.message).contains("max_tokens")
    }

    @Test
    fun `extractText fails when content is missing`() {
        assertThrows<IllegalStateException> { ClaudeModelResolver.extractText(parse("""{"error":"x"}""")) }
    }
}
