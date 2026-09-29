package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.R
import app.aaps.plugins.aps.openAPSAIMI.advisor.AiCoachingService
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorAIService
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusNotifier
import app.aaps.plugins.aps.openAPSAIMI.llm.gemini.GeminiModelResolver
import app.aaps.plugins.aps.openAPSAIMI.physio.AIMILLMPhysioAnalyzerMTR
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.mockito.stubbing.Answer
import java.lang.reflect.InvocationTargetException

/**
 * The words a user reads when an API refuses an AIMI request.
 *
 * Every one of these strings existed before the HTTP seam and has to come out of the seam unchanged.
 * They are the only thing that tells someone why their coaching request, their auditor verdict or
 * their physiology summary did not arrive, so a seam that dropped the status code or the error body
 * would be a regression even though everything still compiled.
 *
 * The four clients do not word their failures the same way, which is exactly why the seam hands a
 * refusal back as data instead of throwing one shape of its own.
 */
class AimiHttpClientMessageTest {

    /** Answers one fixed reply to every request, so a test can state a server refusal in one line. */
    private class FixedAimiHttp(private val response: AimiHttpResponse) : AimiHttp {

        override fun execute(request: AimiHttpRequest): AimiHttpResponse = response

        /** Names failures the way the real Android seam does, so a client under test reads them the same. */
        override fun classify(error: Throwable): AimiHttpFailure = AndroidAimiHttp().classify(error)
    }

    private fun refusal(code: Int, reason: String?, body: String?) =
        FixedAimiHttp(AimiHttpResponse(code = code, reason = reason, body = body))

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

    // ── The physiology analyser: status and reason phrase ──────────────────────────────────────

    @Test
    fun `the physiology analyser still says HTTP code then reason phrase`() {
        val analyzer = AIMILLMPhysioAnalyzerMTR(
            preferences = mock<Preferences>(),
            aapsLogger = mock<AAPSLogger>(),
            geminiResolver = mock<GeminiModelResolver>(),
            aimiHttp = refusal(code = 500, reason = "Internal Server Error", body = "ignored by this client")
        )

        val thrown = assertThrows<Exception> {
            callPrivate(analyzer, "makeAPICall", "https://example.invalid/v1", "{}", emptyMap<String, String>())
        }

        assertThat(thrown).hasMessageThat().isEqualTo("HTTP 500: Internal Server Error")
    }

    @Test
    fun `the physiology analyser still prints a missing reason phrase as null`() {
        // It interpolated a nullable reason phrase before the port and has to keep doing so.
        val analyzer = AIMILLMPhysioAnalyzerMTR(
            preferences = mock<Preferences>(),
            aapsLogger = mock<AAPSLogger>(),
            geminiResolver = mock<GeminiModelResolver>(),
            aimiHttp = refusal(code = 503, reason = null, body = null)
        )

        val thrown = assertThrows<Exception> {
            callPrivate(analyzer, "makeAPICall", "https://example.invalid/v1", "{}", emptyMap<String, String>())
        }

        assertThat(thrown).hasMessageThat().isEqualTo("HTTP 503: null")
    }

    // ── The auditor: status alone, or status plus the error body ───────────────────────────────

    private fun auditor(http: AimiHttp, resolver: GeminiModelResolver = mock()) = AuditorAIService(
        preferences = mock<Preferences>(),
        aapsLogger = mock<AAPSLogger>(),
        geminiResolver = resolver,
        auditorStatusNotifier = mock<AuditorStatusNotifier>(),
        aimiHttp = http
    )

    @Test
    fun `the auditor OpenAI call still says HTTP and the code alone`() {
        val service = auditor(refusal(code = 401, reason = "Unauthorized", body = """{"error":"bad key"}"""))

        val thrown = assertThrows<Exception> {
            callPrivate(service, "callOpenAI", "key", "prompt", false)
        }

        assertThat(thrown).hasMessageThat().isEqualTo("HTTP 401")
    }

    @Test
    fun `the auditor Gemini call still carries the whole error body after the code`() {
        val errorBody = """{"error":{"code":429,"message":"Resource has been exhausted","status":"RESOURCE_EXHAUSTED"}}"""
        val resolver = mock<GeminiModelResolver>()
        whenever(resolver.getGenerateContentUrl(any(), any())).thenReturn("https://example.invalid/gen")
        val service = auditor(refusal(code = 429, reason = "Too Many Requests", body = errorBody), resolver)

        val thrown = assertThrows<Exception> {
            callPrivate(service, "executeGeminiRequest", "key", "prompt", "gemini-flash-latest")
        }

        assertThat(thrown).hasMessageThat().isEqualTo("HTTP 429: $errorBody")
    }

    @Test
    fun `the auditor Gemini call still says Unknown error when the refusal carried no body`() {
        val resolver = mock<GeminiModelResolver>()
        whenever(resolver.getGenerateContentUrl(any(), any())).thenReturn("https://example.invalid/gen")
        val service = auditor(refusal(code = 404, reason = "Not Found", body = null), resolver)

        val thrown = assertThrows<Exception> {
            callPrivate(service, "executeGeminiRequest", "key", "prompt", "gemini-flash-latest")
        }

        assertThat(thrown).hasMessageThat().isEqualTo("HTTP 404: Unknown error")
    }

    // ── The coaching service: a translated string, built from the code and the raw body ────────

    /** Records every `gs` call so a test can see the code and the body that reached the template. */
    private class RecordedResources {

        val calls = mutableListOf<List<Any?>>()

        val helper: ResourceHelper = mock(
            defaultAnswer = Answer { invocation ->
                calls += invocation.arguments.flatMap { argument ->
                    if (argument is Array<*>) argument.toList() else listOf(argument)
                }
                "rendered"
            }
        )
    }

    @Test
    fun `the coaching service still hands the OpenAI template the code and the raw error body`() {
        val errorBody = """{"error":{"message":"Incorrect API key provided","type":"invalid_request_error"}}"""
        val resources = RecordedResources()
        val service = AiCoachingService(
            rh = resources.helper,
            aapsLogger = mock<AAPSLogger>(),
            geminiModelResolver = mock<GeminiModelResolver>(),
            aimiHttp = refusal(code = 401, reason = "Unauthorized", body = errorBody)
        )

        runBlocking { service.fetchText("prompt", "key", AiCoachingService.Provider.OPENAI) }

        assertThat(resources.calls.last())
            .containsExactly(R.string.aimi_coach_svc_error_openai, 401, errorBody)
            .inOrder()
    }

    @Test
    fun `the coaching service still drops the line breaks inside an error body`() {
        // Its readers built the text with a readLine loop and appended each line without its ending,
        // so a pretty printed error body arrived as one unbroken line. The user reads that string.
        val prettyBody = "{\n  \"error\": {\n    \"message\": \"Invalid key\"\n  }\n}"
        val flattened = "{  \"error\": {    \"message\": \"Invalid key\"  }}"
        val resources = RecordedResources()
        val service = AiCoachingService(
            rh = resources.helper,
            aapsLogger = mock<AAPSLogger>(),
            geminiModelResolver = mock<GeminiModelResolver>(),
            aimiHttp = refusal(code = 400, reason = "Bad Request", body = prettyBody)
        )

        runBlocking { service.fetchText("prompt", "key", AiCoachingService.Provider.OPENAI) }

        assertThat(resources.calls.last())
            .containsExactly(R.string.aimi_coach_svc_error_openai, 400, flattened)
            .inOrder()
    }

    @Test
    fun `the coaching service Gemini failure still reads Gemini Error then the code and the body`() {
        val errorBody = """{"error":{"message":"API key not valid"}}"""
        val resolver = mock<GeminiModelResolver>()
        whenever(resolver.resolveGenerateContentModel(any(), any())).thenReturn("gemini-flash-latest")
        whenever(resolver.getGenerateContentUrl(any(), any())).thenReturn("https://example.invalid/gen")
        val resources = RecordedResources()
        val service = AiCoachingService(
            rh = resources.helper,
            aapsLogger = mock<AAPSLogger>(),
            geminiModelResolver = resolver,
            aimiHttp = refusal(code = 400, reason = "Bad Request", body = errorBody)
        )

        runBlocking { service.fetchText("prompt", "key", AiCoachingService.Provider.GEMINI) }

        // fetchText catches it and feeds the message into the generic template.
        assertThat(resources.calls.last())
            .containsExactly(R.string.aimi_coach_svc_generic_error, "Gemini Error (400): $errorBody")
            .inOrder()
    }
}
