package app.aaps.plugins.aps.openAPSAIMI.llm.gemini

import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpRequest
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpResponse
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * How AIMI picks the Gemini model it will talk to.
 *
 * Three things here are behaviour a user would notice if the port changed them: the list endpoint
 * and its timeouts, the order the resolver prefers models in, and how long a model list is trusted
 * before it is fetched again. The last one used to be written as `TimeUnit.HOURS.toMillis(24)`, and
 * a plain division is exactly the kind of swap that can quietly turn 24 hours into something else,
 * so the number the cache is asked for is read back rather than assumed.
 */
class GeminiModelResolverTest {

    private companion object {

        const val STORE = "aimi_gemini_cache"
        const val KEY_TIMESTAMP = "cache_ts"
        const val KEY_MODELS = "available_models_json"
        const val ONE_DAY_MS = 86_400_000L

        /** A "models" reply in the shape the Gemini list endpoint really answers. */
        fun listReply(vararg names: String): String {
            val entries = names.joinToString(",") { name ->
                """{"name":"models/$name","supportedGenerationMethods":["generateContent","countTokens"]}"""
            }
            return """{"models":[$entries]}"""
        }
    }

    /** An in-memory stand-in for the platform key/value store, with its own controllable freshness. */
    private class FakeCache : AimiKeyValueCache {

        val strings = mutableMapOf<String, String>()
        val longs = mutableMapOf<String, Long>()

        /** Every freshness question the resolver asked, as store/key/ttl. */
        val freshnessQuestions = mutableListOf<Triple<String?, String, Long>>()

        /** What [isFresh] answers. The real one compares a stored stamp against the wall clock. */
        var fresh = false

        private fun id(store: String?, key: String) = "$store/$key"

        override fun getString(store: String?, key: String): String? = strings[id(store, key)]

        override fun putString(store: String?, key: String, value: String): Boolean {
            strings[id(store, key)] = value
            return true
        }

        override fun getLong(store: String?, key: String, default: Long): Long = longs[id(store, key)] ?: default

        override fun putLong(store: String?, key: String, value: Long): Boolean {
            longs[id(store, key)] = value
            return true
        }

        override fun isFresh(store: String?, key: String, ttlMs: Long): Boolean {
            freshnessQuestions += Triple(store, key, ttlMs)
            return fresh
        }
    }

    /** Answers a scripted reply, or raises, and keeps every request it was given. */
    private class FakeHttp(
        private val reply: AimiHttpResponse? = null,
        private val failure: Throwable? = null
    ) : AimiHttp {

        val requests = mutableListOf<AimiHttpRequest>()

        override fun execute(request: AimiHttpRequest): AimiHttpResponse {
            requests += request
            failure?.let { throw it }
            return reply ?: AimiHttpResponse(code = 200, reason = "OK", body = "{}")
        }
    }

    private fun resolver(cache: FakeCache, http: FakeHttp) =
        GeminiModelResolver(cache = cache, aapsLogger = NoOpAapsLogger, aimiHttp = http)

    private fun okReply(body: String) = AimiHttpResponse(code = 200, reason = "OK", body = body)

    // ── The list endpoint ──────────────────────────────────────────────────────────────────────

    @Test
    fun `the model list is fetched from the v1beta models endpoint with both ten second timeouts`() {
        val http = FakeHttp(okReply(listReply("gemini-flash-latest")))

        resolver(FakeCache(), http).resolveGenerateContentModel("THEKEY", null)

        val sent = http.requests.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models?key=THEKEY", sent.url)
        assertEquals("GET", sent.method)
        assertEquals(10000, sent.connectTimeoutMs)
        assertEquals(10000, sent.readTimeoutMs)
        assertEquals(null, sent.body)
    }

    @Test
    fun `the generateContent url still carries the model and the key`() {
        val resolver = resolver(FakeCache(), FakeHttp())

        assertEquals(
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-pro:generateContent?key=THEKEY",
            resolver.getGenerateContentUrl("gemini-2.5-pro", "THEKEY")
        )
    }

    // ── Which model wins ───────────────────────────────────────────────────────────────────────

    @Test
    fun `the preferred model wins when the API offers it`() {
        val http = FakeHttp(okReply(listReply("gemini-2.5-pro", "gemini-flash-latest")))

        val model = resolver(FakeCache(), http).resolveGenerateContentModel("k", "gemini-2.5-pro")

        assertEquals("gemini-2.5-pro", model)
    }

    @Test
    fun `a preferred model written as a full resource name is still matched`() {
        val http = FakeHttp(okReply(listReply("gemini-2.5-flash")))

        val model = resolver(FakeCache(), http).resolveGenerateContentModel("k", "  models/gemini-2.5-flash  ")

        assertEquals("gemini-2.5-flash", model)
    }

    @Test
    fun `an unknown preferred model falls back in the priority order and not to the first offered`() {
        // gemini-2.5-flash is offered first by the API but sits below gemini-pro-latest in the list.
        val http = FakeHttp(okReply(listReply("gemini-2.5-flash", "gemini-pro-latest")))

        val model = resolver(FakeCache(), http).resolveGenerateContentModel("k", "gemini-does-not-exist")

        assertEquals("gemini-pro-latest", model)
    }

    @Test
    fun `nothing in the priority list means any gemini pro or flash model will do`() {
        val http = FakeHttp(okReply(listReply("gemini-9.9-flash-experimental")))

        val model = resolver(FakeCache(), http).resolveGenerateContentModel("k", null)

        assertEquals("gemini-9.9-flash-experimental", model)
    }

    @Test
    fun `a model that supports nothing but counting tokens is not offered`() {
        val http = FakeHttp(okReply("""{"models":[{"name":"models/embed-1","supportedGenerationMethods":["countTokens"]}]}"""))

        // Nothing usable came back, so the resolver falls back to the head of its own priority list.
        val model = resolver(FakeCache(), http).resolveGenerateContentModel("k", null)

        assertEquals("gemini-flash-latest", model)
    }

    // ── What happens when the API cannot be reached ────────────────────────────────────────────

    @Test
    fun `a network failure with nothing cached still answers the durable flash alias`() {
        val http = FakeHttp(failure = RuntimeException("Unable to resolve host"))

        val model = resolver(FakeCache(), http).resolveGenerateContentModel("k", null)

        assertEquals("gemini-flash-latest", model)
    }

    @Test
    fun `a network failure falls back to a stale disk cache before it falls back to the priority list`() {
        val cache = FakeCache()
        cache.strings["$STORE/$KEY_MODELS"] = "gemini-2.5-pro,gemini-2.5-flash"
        cache.fresh = false // the stamp is old, so this is the stale path
        val http = FakeHttp(failure = RuntimeException("timeout"))

        val model = resolver(cache, http).resolveGenerateContentModel("k", "gemini-2.5-pro")

        assertEquals("gemini-2.5-pro", model)
    }

    @Test
    fun `a refused list request is treated as no list at all`() {
        val http = FakeHttp(AimiHttpResponse(code = 403, reason = "Forbidden", body = """{"error":"bad key"}"""))

        val model = resolver(FakeCache(), http).resolveGenerateContentModel("k", null)

        assertEquals("gemini-flash-latest", model)
    }

    // ── How long a fetched list is trusted ─────────────────────────────────────────────────────

    @Test
    fun `the disk cache is asked whether it is younger than twenty four hours`() {
        val cache = FakeCache()

        resolver(cache, FakeHttp(okReply(listReply("gemini-flash-latest")))).resolveGenerateContentModel("k", null)

        val question = cache.freshnessQuestions.single()
        assertEquals(STORE, question.first)
        assertEquals(KEY_TIMESTAMP, question.second)
        assertEquals(ONE_DAY_MS, question.third)
    }

    @Test
    fun `a fresh disk cache is used and the network is never touched`() {
        val cache = FakeCache()
        cache.fresh = true
        cache.strings["$STORE/$KEY_MODELS"] = "gemini-2.5-pro"
        val http = FakeHttp(okReply(listReply("gemini-flash-latest")))

        val model = resolver(cache, http).resolveGenerateContentModel("k", "gemini-2.5-pro")

        assertEquals("gemini-2.5-pro", model)
        assertTrue(http.requests.isEmpty())
    }

    @Test
    fun `a fetched list is written to disk with its stamp so it survives a restart`() {
        val cache = FakeCache()

        resolver(cache, FakeHttp(okReply(listReply("gemini-flash-latest", "gemini-2.5-pro"))))
            .resolveGenerateContentModel("k", null)

        val stored = cache.strings["$STORE/$KEY_MODELS"]
        assertNotNull(stored)
        assertEquals(setOf("gemini-flash-latest", "gemini-2.5-pro"), stored.split(",").toSet())

        val stamp = cache.longs["$STORE/$KEY_TIMESTAMP"]
        assertNotNull(stamp)
        // Written from the same wall clock the freshness check reads, so it is "now" give or take.
        assertTrue(aimiWallClockMs() - stamp < 60_000L, "stamp should be the time of the fetch")
    }

    @Test
    fun `a list already in memory is reused without a second request`() {
        val http = FakeHttp(okReply(listReply("gemini-flash-latest")))
        val resolver = resolver(FakeCache(), http)

        resolver.resolveGenerateContentModel("k", null)
        resolver.resolveGenerateContentModel("k", null)

        assertEquals(1, http.requests.size)
    }
}
