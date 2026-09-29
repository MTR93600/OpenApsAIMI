package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorAIService
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusNotifier
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMILLMPhysioAnalyzerMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioBaselineMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioFeaturesMTR
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.lang.reflect.InvocationTargetException

/**
 * The bytes the two LLM clients put on the wire, before and after the move off `org.json`.
 *
 * `org.json` is a JVM and Android library, so a request body written with it cannot go to iOS. The
 * bodies now come from kotlinx `buildJsonObject`, and the rendered text is allowed to differ - key
 * order is not part of JSON, and `org.json.JSONObject` is backed by a hash map, so it never promised
 * an order in the first place. What is **not** allowed to differ is the meaning: the same keys, the
 * same values, the same types, the same nesting, nothing added and nothing dropped. An API that
 * silently lost `max_tokens` or read `"0.3"` where it used to read `0.3` would answer differently,
 * and the user would read a different verdict or a different narrative without anything failing.
 *
 * So every test here builds the old body with the real `org.json`, takes the new body straight out of
 * the client under test, and compares the two after parsing - not as text. [canonical] sorts keys,
 * keeps array order, and tags each scalar with its type, so `2048` and `"2048"` do not compare equal.
 *
 * The prompts on purpose carry a quote, a newline, a tab, a backslash and non-ASCII text, because
 * escaping is the one place where two JSON writers really can disagree about meaning.
 */
class AimiLlmRequestBodyParityTest {

    /** A prompt built to break a JSON writer that gets escaping wrong. */
    private val awkwardPrompt = "He said \"go\"\n\tpath C:\\tmp — 5 °C ✓ \u0007 </script>"

    /** Answers 200 to everything and keeps every request, so a test can read the body that was sent. */
    private class RecordingAimiHttp(private val body: String) : AimiHttp {

        val requests = mutableListOf<AimiHttpRequest>()

        override fun execute(request: AimiHttpRequest): AimiHttpResponse {
            requests += request
            return AimiHttpResponse(code = 200, reason = "OK", body = body)
        }

        override fun classify(error: Throwable): AimiHttpFailure = AimiHttpFailure.OTHER

        /** The body of the one request that was sent. */
        val sentBody: String get() = requests.single().body!!
    }

    /** Calls a private method and lets what it threw travel out as itself. */
    private fun callPrivate(target: Any, name: String, vararg args: Any?): Any? {
        val method = target.javaClass.declaredMethods.first { it.name == name }
        method.isAccessible = true
        return try {
            method.invoke(target, *args)
        } catch (e: InvocationTargetException) {
            throw e.targetException
        }
    }

    // ── Comparing two JSON texts by meaning ────────────────────────────────────────────────────

    /**
     * One text for a JSON value that does not depend on key order but does depend on type.
     *
     * Objects are written with their keys sorted, arrays keep their order because order is meaning
     * in an array, and every scalar carries a one letter type tag so a number that turned into a
     * string cannot pass.
     */
    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().sorted()
            .joinToString(separator = ",", prefix = "{", postfix = "}") { key -> "\"$key\":" + canonical(value.get(key)) }

        is JSONArray  -> (0 until value.length())
            .joinToString(separator = ",", prefix = "[", postfix = "]") { index -> canonical(value.get(index)) }

        is String     -> "s:$value"
        is Int, is Long -> "i:$value"
        is Double, is Float -> "d:$value"
        is Boolean    -> "b:$value"
        else          -> "?:$value"
    }

    private fun assertSameMeaning(old: JSONObject, new: String) {
        assertThat(canonical(JSONObject(new))).isEqualTo(canonical(old))
    }

    // ── The auditor ────────────────────────────────────────────────────────────────────────────

    private fun auditor(http: AimiHttp, resolver: GeminiModelResolver = mock()) = AuditorAIService(
        preferences = mock<Preferences>(),
        aapsLogger = mock<AAPSLogger>(),
        geminiResolver = resolver,
        auditorStatusNotifier = mock<AuditorStatusNotifier>(),
        aimiHttp = http
    )

    private fun geminiResolver(): GeminiModelResolver {
        val resolver = mock<GeminiModelResolver>()
        whenever(resolver.getGenerateContentUrl(any(), any())).thenReturn("https://example.invalid/gen")
        return resolver
    }

    @Test
    fun `the auditor OpenAI body still means what it meant`() {
        val http = RecordingAimiHttp("{}")
        callPrivate(auditor(http), "callOpenAI", "key", awkwardPrompt, false)

        val before = JSONObject().apply {
            put("model", "gpt-4o-mini")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", awkwardPrompt)
                })
            })
            put("max_tokens", 2048)
            put("response_format", JSONObject().put("type", "json_object"))
        }

        // Printed so the two texts can be read side by side in the test output.
        println("AUDITOR OPENAI BEFORE: $before")
        println("AUDITOR OPENAI AFTER : ${http.sentBody}")

        assertSameMeaning(before, http.sentBody)
    }

    @Test
    fun `the auditor OpenAI body still names the high performance model`() {
        val http = RecordingAimiHttp("{}")
        callPrivate(auditor(http), "callOpenAI", "key", "p", true)

        assertThat(JSONObject(http.sentBody).getString("model")).isEqualTo("gpt-4o")
    }

    @Test
    fun `the auditor Gemini body still means what it meant`() {
        val http = RecordingAimiHttp("{}")
        callPrivate(auditor(http, geminiResolver()), "executeGeminiRequest", "key", awkwardPrompt, "gemini-flash-latest")

        val before = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", awkwardPrompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("temperature", 0.3)
                put("maxOutputTokens", 8192)
                put("responseMimeType", "application/json")
            })
        }

        assertSameMeaning(before, http.sentBody)
    }

    @Test
    fun `the auditor DeepSeek body still means what it meant`() {
        val http = RecordingAimiHttp("{}")
        callPrivate(auditor(http), "callDeepSeek", "key", awkwardPrompt)

        val before = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", awkwardPrompt)
                })
            })
            put("max_tokens", 2048)
            put("temperature", 0.3)
            put("response_format", JSONObject().put("type", "json_object"))
        }

        assertSameMeaning(before, http.sentBody)
    }

    @Test
    fun `the auditor Claude body still means what it meant`() {
        val http = RecordingAimiHttp("{}")
        callPrivate(auditor(http), "callClaude", "key", awkwardPrompt, true)

        val before = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", 2048)
            put("temperature", 0.3)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", awkwardPrompt)
                })
            })
        }

        assertSameMeaning(before, http.sentBody)
    }

    // ── The physiology analyser ────────────────────────────────────────────────────────────────

    private fun analyzer(http: AimiHttp, resolver: GeminiModelResolver = mock()) = AIMILLMPhysioAnalyzerMTR(
            preferences = mock<Preferences>(),
        aapsLogger = mock<AAPSLogger>(),
        geminiResolver = resolver,
        aimiHttp = http
    )

    /**
     * The prompt the analyser builds for itself.
     *
     * It is read back out of the analyser rather than written here, because the point of these tests
     * is the JSON around the prompt, not the prompt. Everything else in the body is stated in full.
     */
    private fun physioPrompt(analyzer: AIMILLMPhysioAnalyzerMTR): String =
        callPrivate(analyzer, "buildPrompt", PhysioFeaturesMTR(), PhysioBaselineMTR(), PhysioContextMTR()) as String

    /** The system message both OpenAI-shaped physiology requests send. */
    private fun physioSystemRole(analyzer: AIMILLMPhysioAnalyzerMTR): String {
        val field = analyzer.javaClass.getDeclaredField("SYSTEM_ROLE_NARRATIVE")
        field.isAccessible = true
        return field.get(null) as String
    }

    @Test
    fun `the physiology GPT body still means what it meant`() {
        val http = RecordingAimiHttp("{}")
        val analyzer = analyzer(http)
        val prompt = physioPrompt(analyzer)

        callPrivate(analyzer, "analyzeWithGPT", PhysioFeaturesMTR(), PhysioBaselineMTR(), PhysioContextMTR(), "key")

        val before = JSONObject().apply {
            put("model", "gpt-4")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", physioSystemRole(analyzer))
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_completion_tokens", 150)
            put("temperature", 0.3)
        }

        assertSameMeaning(before, http.sentBody)
    }

    @Test
    fun `the physiology Gemini body still means what it meant`() {
        val http = RecordingAimiHttp("{}")
        val analyzer = analyzer(http, geminiResolver())
        val prompt = physioPrompt(analyzer)

        callPrivate(analyzer, "executeGeminiRequest", "key", prompt, "gemini-flash-latest")

        val before = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", prompt) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 150)
                put("temperature", 0.3)
            })
        }

        assertSameMeaning(before, http.sentBody)
    }

    @Test
    fun `the physiology Claude body still means what it meant`() {
        val http = RecordingAimiHttp("{}")
        val analyzer = analyzer(http)
        val prompt = physioPrompt(analyzer)

        callPrivate(analyzer, "analyzeWithClaude", PhysioFeaturesMTR(), PhysioBaselineMTR(), PhysioContextMTR(), "key")

        val before = JSONObject().apply {
            put("model", "claude-3-5-sonnet-20241022")
            put("max_tokens", 150)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
        }

        assertSameMeaning(before, http.sentBody)
    }

    @Test
    fun `the physiology DeepSeek body still means what it meant`() {
        val http = RecordingAimiHttp("{}")
        val analyzer = analyzer(http)
        val prompt = physioPrompt(analyzer)

        callPrivate(analyzer, "analyzeWithDeepSeek", PhysioFeaturesMTR(), PhysioBaselineMTR(), PhysioContextMTR(), "key")

        val before = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", physioSystemRole(analyzer))
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("max_tokens", 150)
            put("temperature", 0.3)
        }

        assertSameMeaning(before, http.sentBody)
    }

    // ── Reading the reply back ─────────────────────────────────────────────────────────────────

    @Test
    fun `the auditor still pulls the model text out of each of the four provider shapes`() {
        val service = auditor(RecordingAimiHttp("{}"))
        val text = "carbs are fine"

        val openAi = """{"choices":[{"message":{"content":${JSONObject.quote(text)}}}]}"""
        val gemini = """{"candidates":[{"content":{"parts":[{"text":${JSONObject.quote(text)}}]}}]}"""
        val claude = """{"content":[{"text":${JSONObject.quote(text)}}]}"""

        assertThat(callPrivate(service, "extractContentText", openAi, AuditorAIService.Provider.OPENAI)).isEqualTo(text)
        assertThat(callPrivate(service, "extractContentText", openAi, AuditorAIService.Provider.DEEPSEEK)).isEqualTo(text)
        assertThat(callPrivate(service, "extractContentText", gemini, AuditorAIService.Provider.GEMINI)).isEqualTo(text)
        assertThat(callPrivate(service, "extractContentText", claude, AuditorAIService.Provider.CLAUDE)).isEqualTo(text)
    }

    @Test
    fun `the auditor still strips a code fence around the model text`() {
        val service = auditor(RecordingAimiHttp("{}"))
        val fenced = "```json\n{\"verdict\":\"CONFIRM\"}\n```"
        val body = """{"choices":[{"message":{"content":${JSONObject.quote(fenced)}}}]}"""

        assertThat(callPrivate(service, "extractContentText", body, AuditorAIService.Provider.OPENAI))
            .isEqualTo("{\"verdict\":\"CONFIRM\"}")
    }

    @Test
    fun `a reply that is missing the text still raises, so the caller can call it a parse failure`() {
        val service = auditor(RecordingAimiHttp("{}"))

        val thrown = runCatching {
            callPrivate(service, "extractContentText", """{"choices":[]}""", AuditorAIService.Provider.OPENAI)
        }.exceptionOrNull()

        assertThat(thrown).isNotNull()
    }
}
