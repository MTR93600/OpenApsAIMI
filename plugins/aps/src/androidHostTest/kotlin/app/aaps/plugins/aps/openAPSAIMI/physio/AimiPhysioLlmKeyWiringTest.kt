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
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The API key this analyser sends must be the one the user typed on the AI keys screen.
 *
 * Until this test existed the analyser read four preference names that nothing in the app ever
 * wrote, so it never had a key, never called anything, and no test noticed. These tests state the
 * stored preference names in full, so a rename on either side fails here rather than silently
 * turning the feature off again.
 *
 * They also state the mapping provider by provider. A swap between two providers would compile and
 * would send one provider's key to another provider's endpoint, which leaks the key to a third
 * party, so the mapping is checked one line per provider rather than in a loop.
 */
class AimiPhysioLlmKeyWiringTest {

    private companion object {

        /** The names the AI keys screen writes. Spelled out, not read from the enum. */
        const val OPENAI_SETTING = "aimi_advisor_openai_key"
        const val GEMINI_SETTING = "aimi_advisor_gemini_key"
        const val CLAUDE_SETTING = "aimi_advisor_claude_key"
        const val DEEPSEEK_SETTING = "aimi_advisor_deepseek_key"

        const val OPENAI_REPLY = """{"choices":[{"message":{"content":"ok"}}]}"""
    }

    /** Records every request and lets the test wait for the background refresh to send it. */
    private class RecordingAimiHttp(private val body: String) : AimiHttp {

        val sent = mutableListOf<AimiHttpRequest>()
        private val arrived = CountDownLatch(1)

        override fun execute(request: AimiHttpRequest): AimiHttpResponse {
            synchronized(sent) { sent.add(request) }
            arrived.countDown()
            return AimiHttpResponse(code = 200, reason = "OK", body = body)
        }

        override fun classify(error: Throwable): AimiHttpFailure = AimiHttpFailure.OTHER

        /** True when a request arrived. The refresh runs off the caller's thread. */
        fun awaitRequest(): Boolean = arrived.await(5, TimeUnit.SECONDS)
    }

    /**
     * A preference store that answers the provider and the four keys, and nothing else.
     *
     * Every key is given a different value, so a test that reads the wrong one reads a value it can
     * name rather than an empty string that could mean anything.
     */
    private fun preferences(
        provider: String,
        openAi: String = "",
        gemini: String = "",
        claude: String = "",
        deepSeek: String = ""
    ): Preferences = mock<Preferences>().also {
        whenever(it.get(StringKey.AimiPhysioLLMProvider)).thenReturn(provider)
        whenever(it.get(StringKey.AimiAdvisorOpenAIKey)).thenReturn(openAi)
        whenever(it.get(StringKey.AimiAdvisorGeminiKey)).thenReturn(gemini)
        whenever(it.get(StringKey.AimiAdvisorClaudeKey)).thenReturn(claude)
        whenever(it.get(StringKey.AimiAdvisorDeepSeekKey)).thenReturn(deepSeek)
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

    private fun analyze(analyzer: AIMILLMPhysioAnalyzerMTR): String =
        analyzer.analyze(PhysioFeaturesMTR(), PhysioBaselineMTR(), PhysioContextMTR())

    // ── The mapping, one line per provider ─────────────────────────────────────────────────────

    @Test
    fun `gpt4 reads the OpenAI key the user typed`() {
        assertThat(analyzer(preferences("gpt4"), mock()).apiKeySettingFor("gpt4")?.key)
            .isEqualTo(OPENAI_SETTING)
    }

    @Test
    fun `gemini reads the Gemini key the user typed`() {
        assertThat(analyzer(preferences("gemini"), mock()).apiKeySettingFor("gemini")?.key)
            .isEqualTo(GEMINI_SETTING)
    }

    @Test
    fun `claude reads the Claude key the user typed`() {
        assertThat(analyzer(preferences("claude"), mock()).apiKeySettingFor("claude")?.key)
            .isEqualTo(CLAUDE_SETTING)
    }

    @Test
    fun `deepseek reads the DeepSeek key the user typed`() {
        assertThat(analyzer(preferences("deepseek"), mock()).apiKeySettingFor("deepseek")?.key)
            .isEqualTo(DEEPSEEK_SETTING)
    }

    @Test
    fun `a provider name nobody knows has no key at all`() {
        assertThat(analyzer(preferences("gpt4"), mock()).apiKeySettingFor("bard")).isNull()
    }

    // ── With no key configured the feature stays silent ────────────────────────────────────────

    @Test
    fun `no key configured means nothing is sent`() {
        val http = RecordingAimiHttp(OPENAI_REPLY)
        val result = analyze(analyzer(preferences("gpt4"), http))

        assertThat(result).isEmpty()
        assertThat(http.sent).isEmpty()
    }

    @Test
    fun `no key configured means nothing is sent for any provider`() {
        for (provider in listOf("gpt4", "gemini", "claude", "deepseek", "bard")) {
            val http = RecordingAimiHttp(OPENAI_REPLY)
            val resolver = mock<GeminiModelResolver>()

            assertThat(analyze(analyzer(preferences(provider), http, resolver))).isEmpty()
            assertThat(http.sent).isEmpty()
            // The Gemini path lists models before it analyses. That is a call too, and it carries
            // the key, so a blank key must not reach it either.
            verify(resolver, never()).resolveGenerateContentModel(any(), any())
        }
    }

    @Test
    fun `a key for another provider does not wake the selected one`() {
        // Someone who filled in only the Gemini key but left the provider on its default.
        val http = RecordingAimiHttp(OPENAI_REPLY)
        val preferences = preferences(provider = "gpt4", gemini = "gemini-key")

        assertThat(analyze(analyzer(preferences, http))).isEmpty()
        assertThat(http.sent).isEmpty()
    }

    // ── The configured key is the one that reaches that provider ───────────────────────────────

    @Test
    fun `the OpenAI key reaches the OpenAI endpoint and no other key does`() {
        val http = RecordingAimiHttp(OPENAI_REPLY)
        val preferences = preferences(
            provider = "gpt4",
            openAi = "openai-key",
            gemini = "gemini-key",
            claude = "claude-key",
            deepSeek = "deepseek-key"
        )

        analyze(analyzer(preferences, http))

        assertThat(http.awaitRequest()).isTrue()
        val request = http.sent.single()
        assertThat(request.url).isEqualTo("https://api.openai.com/v1/chat/completions")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer openai-key")
    }

    @Test
    fun `the DeepSeek key reaches the DeepSeek endpoint`() {
        val http = RecordingAimiHttp(OPENAI_REPLY)
        val preferences = preferences(
            provider = "deepseek",
            openAi = "openai-key",
            deepSeek = "deepseek-key"
        )

        analyze(analyzer(preferences, http))

        assertThat(http.awaitRequest()).isTrue()
        val request = http.sent.single()
        assertThat(request.url).isEqualTo("https://api.deepseek.com/v1/chat/completions")
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer deepseek-key")
    }

    @Test
    fun `the Claude key reaches the Claude endpoint in the header Anthropic reads`() {
        val http = RecordingAimiHttp("""{"content":[{"text":"ok"}]}""")
        val preferences = preferences(
            provider = "claude",
            openAi = "openai-key",
            claude = "claude-key"
        )

        analyze(analyzer(preferences, http))

        assertThat(http.awaitRequest()).isTrue()
        val request = http.sent.single()
        assertThat(request.url).isEqualTo("https://api.anthropic.com/v1/messages")
        assertThat(request.headers["x-api-key"]).isEqualTo("claude-key")
        // A bearer header would send the key to the wrong place as well as the right one.
        assertThat(request.headers).doesNotContainKey("Authorization")
    }

    @Test
    fun `the Gemini key reaches the Gemini model resolver`() {
        val http = RecordingAimiHttp("""{"candidates":[{"content":{"parts":[{"text":"ok"}]}}]}""")
        val resolver = mock<GeminiModelResolver>()
        whenever(resolver.resolveGenerateContentModel(any(), any())).thenReturn("gemini-pro-latest")
        whenever(resolver.getGenerateContentUrl(any(), any()))
            .thenAnswer { "https://example.invalid/${it.arguments[0]}:generateContent?key=${it.arguments[1]}" }
        val preferences = preferences(
            provider = "gemini",
            openAi = "openai-key",
            gemini = "gemini-key"
        )

        analyze(analyzer(preferences, http, resolver))

        assertThat(http.awaitRequest()).isTrue()
        verify(resolver).resolveGenerateContentModel(eq("gemini-key"), any())
        assertThat(http.sent.single().url).endsWith("?key=gemini-key")
    }
}
