package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.StringKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpFailure
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpRequest
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpResponse
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

/**
 * What the user notices about the daily physiological narrative.
 *
 * Three separate faults used to meet here, and each one on its own was enough to make the feature
 * invisible:
 *
 * 1. The answer arrived for the next run, not this one. `analyze` started the call in the
 *    background and returned the *previous* value, but the caller stores the returned value a few
 *    lines later, so today's text could only have been stored a day later. The held value was in
 *    memory only, so any restart of AAPS reset it to empty.
 * 2. A failed call stored an empty string, wiping the last good text the user could still read.
 * 3. A model that answered nothing produced the narrative "null", because `JsonNull` is a
 *    `JsonPrimitive` whose `content` is the four letters `null`, and `"null".isNotBlank()` is true.
 *
 * These tests are written from what reaches the caller, not from how the class is built, so they
 * keep their meaning if the internals change again.
 */
class AimiPhysioLlmNarrativeDeliveryTest {

    private companion object {

        const val OPENAI_URL = "https://api.openai.com/v1/chat/completions"
        const val CLAUDE_URL = "https://api.anthropic.com/v1/messages"
        const val DEEPSEEK_URL = "https://api.deepseek.com/v1/chat/completions"

        fun openAiReply(content: String) = """{"choices":[{"message":{"content":$content}}]}"""
        fun geminiReply(text: String) = """{"candidates":[{"content":{"parts":[{"text":$text}]}}]}"""
        fun claudeReply(text: String) = """{"content":[{"text":$text}]}"""

        /** A JSON string, quoted, so a test can say "the model really said this". */
        fun quoted(text: String) = "\"$text\""

        /** What a model sends when it has nothing to say, or when its filter emptied the reply. */
        const val JSON_NULL = "null"
    }

    /**
     * Answers each call with the next reply in the list, and records what was sent.
     *
     * A reply of `null` means the call fails, which is how a network error or a server error
     * reaches this class.
     */
    private class FakeAimiHttp(private vararg val replies: String?) : AimiHttp {

        val sent = mutableListOf<AimiHttpRequest>()
        private var index = 0

        override fun execute(request: AimiHttpRequest): AimiHttpResponse {
            sent.add(request)
            // A test that set up no reply at all did not expect any call to go out. Say so plainly
            // instead of failing later with an index error that hides what happened.
            check(replies.isNotEmpty()) { "The analyser sent a request this test did not expect" }
            val reply = replies[minOf(index, replies.size - 1)]
            index++
            return if (reply == null) {
                AimiHttpResponse(code = 500, reason = "Server Error", body = null)
            } else {
                AimiHttpResponse(code = 200, reason = "OK", body = reply)
            }
        }

        override fun classify(error: Throwable): AimiHttpFailure = AimiHttpFailure.OTHER
    }

    private fun preferences(
        provider: String = "OPENAI",
        openAi: String = "openai-key",
        gemini: String = "gemini-key",
        claude: String = "claude-key",
        deepSeek: String = "deepseek-key"
    ): Preferences = mock<Preferences>().also {
        whenever(it.get(StringKey.AimiAdvisorProvider)).thenReturn(provider)
        whenever(it.get(StringKey.AimiAdvisorOpenAIKey)).thenReturn(openAi)
        whenever(it.get(StringKey.AimiAdvisorGeminiKey)).thenReturn(gemini)
        whenever(it.get(StringKey.AimiAdvisorClaudeKey)).thenReturn(claude)
        whenever(it.get(StringKey.AimiAdvisorDeepSeekKey)).thenReturn(deepSeek)
    }

    private fun geminiResolver(): GeminiModelResolver = mock<GeminiModelResolver>().also {
        whenever(it.resolveGenerateContentModel(any(), any())).thenReturn("gemini-pro-latest")
        whenever(it.getGenerateContentUrl(any(), any()))
            .thenAnswer { call -> "https://example.invalid/${call.arguments[0]}:generateContent?key=${call.arguments[1]}" }
    }

    private fun analyzer(
        preferences: Preferences,
        http: AimiHttp,
        resolver: GeminiModelResolver = mock()
    ) = AIMILLMPhysioAnalyzerMTR(
        preferences = preferences,
        aapsLogger = mock<AAPSLogger>(),
        geminiResolver = resolver,
        aimiHttp = http
    )

    private suspend fun AIMILLMPhysioAnalyzerMTR.run(): String =
        analyze(PhysioFeaturesMTR(), PhysioBaselineMTR(), PhysioContextMTR())

    // ── The answer lands on the run that paid for it ───────────────────────────────────────────

    @Test
    fun `the very first run already gives back today's narrative`() = runTest {
        val http = FakeAimiHttp(openAiReply(quoted("Slept well, expect normal sensitivity.")))

        val narrative = analyzer(preferences(), http).run()

        // Before the fix this was "" on the first run, and the text only appeared a day later.
        assertThat(narrative).isEqualTo("Slept well, expect normal sensitivity.")
        assertThat(http.sent).hasSize(1)
    }

    @Test
    fun `each run gives back its own answer, not the one before it`() = runTest {
        val http = FakeAimiHttp(openAiReply(quoted("day one")), openAiReply(quoted("day two")))
        val analyzer = analyzer(preferences(), http)

        assertThat(analyzer.run()).isEqualTo("day one")
        assertThat(analyzer.run()).isEqualTo("day two")
    }

    // ── A failure leaves the last good narrative alone ─────────────────────────────────────────

    @Test
    fun `a failed call keeps the narrative from the last run that worked`() = runTest {
        val http = FakeAimiHttp(openAiReply(quoted("the good one")), null)
        val analyzer = analyzer(preferences(), http)

        assertThat(analyzer.run()).isEqualTo("the good one")
        // Before the fix the failure stored "" and the user's card went blank.
        assertThat(analyzer.run()).isEqualTo("the good one")
    }

    @Test
    fun `a failure with nothing good before it gives back nothing`() = runTest {
        val analyzer = analyzer(preferences(), FakeAimiHttp(null))

        assertThat(analyzer.run()).isEmpty()
    }

    // ── An empty answer is no answer, for every provider ───────────────────────────────────────

    @Test
    fun `a JSON null from OpenAI is no answer, not the word null`() = runTest {
        val narrative = analyzer(preferences(), FakeAimiHttp(openAiReply(JSON_NULL))).run()

        assertThat(narrative).isEmpty()
        assertThat(narrative).isNotEqualTo("null")
    }

    @Test
    fun `a JSON null from DeepSeek is no answer, not the word null`() = runTest {
        val http = FakeAimiHttp(openAiReply(JSON_NULL))

        val narrative = analyzer(preferences(provider = "DEEPSEEK"), http).run()

        assertThat(narrative).isEmpty()
        assertThat(narrative).isNotEqualTo("null")
    }

    @Test
    fun `a JSON null from Gemini is no answer, not the word null`() = runTest {
        val http = FakeAimiHttp(geminiReply(JSON_NULL))

        val narrative = analyzer(preferences(provider = "GEMINI"), http, geminiResolver()).run()

        assertThat(narrative).isEmpty()
        assertThat(narrative).isNotEqualTo("null")
    }

    @Test
    fun `a JSON null from Claude is no answer, not the word null`() = runTest {
        val http = FakeAimiHttp(claudeReply(JSON_NULL))

        val narrative = analyzer(preferences(provider = "CLAUDE"), http).run()

        assertThat(narrative).isEmpty()
        assertThat(narrative).isNotEqualTo("null")
    }

    @Test
    fun `an empty reply is no answer`() = runTest {
        val narrative = analyzer(preferences(), FakeAimiHttp(openAiReply(quoted("")))).run()

        assertThat(narrative).isEmpty()
    }

    @Test
    fun `a reply of only spaces is no answer`() = runTest {
        val narrative = analyzer(preferences(), FakeAimiHttp(openAiReply(quoted("   ")))).run()

        assertThat(narrative).isEmpty()
    }

    @Test
    fun `an empty answer does not wipe the last good narrative`() = runTest {
        val http = FakeAimiHttp(openAiReply(quoted("the good one")), openAiReply(JSON_NULL))
        val analyzer = analyzer(preferences(), http)

        assertThat(analyzer.run()).isEqualTo("the good one")
        assertThat(analyzer.run()).isEqualTo("the good one")
    }

    // ── The provider the user picked is the provider that is called ────────────────────────────

    @Test
    fun `OPENAI sends the OpenAI key to the OpenAI endpoint`() = runTest {
        val http = FakeAimiHttp(openAiReply(quoted("ok")))

        analyzer(preferences(provider = "OPENAI"), http).run()

        assertThat(http.sent.single().url).isEqualTo(OPENAI_URL)
        assertThat(http.sent.single().headers["Authorization"]).isEqualTo("Bearer openai-key")
    }

    @Test
    fun `DEEPSEEK sends the DeepSeek key to the DeepSeek endpoint`() = runTest {
        val http = FakeAimiHttp(openAiReply(quoted("ok")))

        analyzer(preferences(provider = "DEEPSEEK"), http).run()

        assertThat(http.sent.single().url).isEqualTo(DEEPSEEK_URL)
        assertThat(http.sent.single().headers["Authorization"]).isEqualTo("Bearer deepseek-key")
    }

    @Test
    fun `CLAUDE sends the Claude key to the Claude endpoint`() = runTest {
        val http = FakeAimiHttp(claudeReply(quoted("ok")))

        analyzer(preferences(provider = "CLAUDE"), http).run()

        assertThat(http.sent.single().url).isEqualTo(CLAUDE_URL)
        assertThat(http.sent.single().headers["x-api-key"]).isEqualTo("claude-key")
        assertThat(http.sent.single().headers).doesNotContainKey("Authorization")
    }

    @Test
    fun `GEMINI sends the Gemini key to the Gemini endpoint`() = runTest {
        val http = FakeAimiHttp(geminiReply(quoted("ok")))

        analyzer(preferences(provider = "GEMINI"), http, geminiResolver()).run()

        assertThat(http.sent.single().url).endsWith("?key=gemini-key")
    }

    /**
     * The four names the AI keys screen stores, mapped to the four names used inside the class.
     *
     * The physio setting the class used to read is on no preference screen, so it could only hold
     * its default. This is the mapping onto the setting the user really fills in, and it is stated
     * one line per provider: a swap would compile, and it would send one provider's key to another
     * provider's endpoint.
     */
    @Test
    fun `the stored provider names map onto the names this class uses`() {
        val analyzer = analyzer(preferences(), FakeAimiHttp())

        assertThat(analyzer.physioProviderFor("OPENAI")).isEqualTo("gpt4")
        assertThat(analyzer.physioProviderFor("GEMINI")).isEqualTo("gemini")
        assertThat(analyzer.physioProviderFor("CLAUDE")).isEqualTo("claude")
        assertThat(analyzer.physioProviderFor("DEEPSEEK")).isEqualTo("deepseek")
    }

    @Test
    fun `each mapped name picks the key setting of the same provider`() {
        val analyzer = analyzer(preferences(), FakeAimiHttp())

        assertThat(analyzer.apiKeySettingFor(analyzer.physioProviderFor("OPENAI")))
            .isEqualTo(StringKey.AimiAdvisorOpenAIKey)
        assertThat(analyzer.apiKeySettingFor(analyzer.physioProviderFor("GEMINI")))
            .isEqualTo(StringKey.AimiAdvisorGeminiKey)
        assertThat(analyzer.apiKeySettingFor(analyzer.physioProviderFor("CLAUDE")))
            .isEqualTo(StringKey.AimiAdvisorClaudeKey)
        assertThat(analyzer.apiKeySettingFor(analyzer.physioProviderFor("DEEPSEEK")))
            .isEqualTo(StringKey.AimiAdvisorDeepSeekKey)
    }

    @Test
    fun `a stored name in lower case still picks the right provider`() {
        val analyzer = analyzer(preferences(), FakeAimiHttp())

        assertThat(analyzer.physioProviderFor("openai")).isEqualTo("gpt4")
        assertThat(analyzer.physioProviderFor("gemini")).isEqualTo("gemini")
    }

    @Test
    fun `a name nobody knows falls back to OpenAI, like the other AIMI readers`() {
        val analyzer = analyzer(preferences(), FakeAimiHttp())

        assertThat(analyzer.physioProviderFor("bard")).isEqualTo("gpt4")
    }

    // ── With no key, nothing is sent ───────────────────────────────────────────────────────────

    @Test
    fun `no key for the chosen provider means nothing is sent`() = runTest {
        for (provider in listOf("OPENAI", "GEMINI", "CLAUDE", "DEEPSEEK", "bard")) {
            val http = FakeAimiHttp(openAiReply(quoted("should never be asked for")))
            val resolver = geminiResolver()
            val preferences = preferences(
                provider = provider,
                openAi = "",
                gemini = "",
                claude = "",
                deepSeek = ""
            )

            assertThat(analyzer(preferences, http, resolver).run()).isEmpty()
            assertThat(http.sent).isEmpty()
        }
    }

    @Test
    fun `a key for a different provider does not wake the chosen one`() = runTest {
        // Someone who filled in only the Gemini key but left the provider on OpenAI.
        val http = FakeAimiHttp(openAiReply(quoted("should never be asked for")))
        val preferences = preferences(
            provider = "OPENAI",
            openAi = "",
            claude = "",
            deepSeek = ""
        )

        assertThat(analyzer(preferences, http).run()).isEmpty()
        assertThat(http.sent).isEmpty()
    }
}
