package app.aaps.plugins.aps.openAPSAIMI.llm.gemini

import app.aaps.core.data.json.OrgJsonCompat.hasCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonArrayCompat
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpRequest
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiKeyValueCache
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * reliable resolver for Gemini Model IDs.
 * Handles dynamic listing, fallback logic, and caching.
 */
@SingleIn(AppScope::class)
class GeminiModelResolver @Inject constructor(
    private val cache: AimiKeyValueCache,
    private val aapsLogger: AAPSLogger,
    private val aimiHttp: AimiHttp
) {

    companion object {
        private const val TAG = "AIMI_GEMINI"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

        // PREFS
        private const val PREFS_NAME = "aimi_gemini_cache"
        private const val KEY_CACHE_TIMESTAMP = "cache_ts"
        private const val KEY_AVAILABLE_MODELS = "available_models_json"

        // TTL: 24h
        private const val CACHE_TTL_MS = 24L * 60L * 60L * 1000L

        // Fallback priority list. Durable aliases first (Google keeps *-latest pointing at the
        // current GA release), then confirmed GA concrete IDs. No preview/shut-down models here
        // (gemini-2.0-flash, gemini-1.5-* and *-preview variants were retired).
        private val FALLBACK_PRIORITY = listOf(
            "gemini-flash-latest",
            "gemini-pro-latest",
            "gemini-3.5-flash",
            "gemini-2.5-flash",
            "gemini-2.5-pro"
        )
    }

    /**
     * Guards [memoryCache] and [lastCacheUpdate], which are read and written together.
     *
     * This replaces a `ConcurrentHashMap`, and the replacement is not a plain map. The old map's
     * `keys` view was handed straight back to the caller, and the caller then iterated it - so a
     * plain map would raise a concurrent modification error the moment a second caller refilled the
     * cache mid-iteration, where the concurrent map only ever answered weakly consistent data.
     *
     * Two callers really can arrive at once: the auditor and the physiology analyser run on the loop
     * tick, and the meal advisor screen resolves a model from the user's own tap at any moment.
     *
     * So the cache is an **immutable set** behind a lock instead. A reader takes the reference under
     * the lock and is then free to iterate it forever, because nothing can change the set it holds.
     * That is stronger than the old behaviour rather than weaker: `updateMemoryCache` used to clear
     * the map before refilling it, so a reader holding the old `keys` view could see the cache empty
     * underneath it and fall back to a hard coded model name for no reason.
     */
    private val cacheLock = AapsLock()
    private var memoryCache: Set<String> = emptySet()
    private var lastCacheUpdate: Long = 0

    /**
     * Resolves a valid model ID for generateContent calls.
     *
     * @param apiKey The API Key to use for listing models
     * @param preferredModel The user's preferred model (e.g. "gemini-flash-latest")
     * @return A valid model ID (e.g. "gemini-flash-latest") ready for use in URL
     */
    fun resolveGenerateContentModel(apiKey: String, preferredModel: String?): String {
        val availableModels = getOrFetchModels(apiKey)

        // 1. Check preferred
        if (!preferredModel.isNullOrBlank()) {
            val sanitized = preferredModel.trim().removePrefix("models/")
            if (availableModels.contains(sanitized)) {
                aapsLogger.debug(LTag.AIMI, "[$TAG] Using preferred model: $sanitized")
                return sanitized
            } else {
                aapsLogger.warn(LTag.AIMI, "[$TAG] Preferred model '$sanitized' not found in available list.")
            }
        }

        // 2. Iterate Priority List
        for (candidate in FALLBACK_PRIORITY) {
            if (availableModels.contains(candidate)) {
                aapsLogger.info(LTag.AIMI, "[$TAG] Fallback to high-priority model: $candidate")
                return candidate
            }
        }

        // 3. Last resort - Find anything that looks like "gemini" and "pro" or "flash"
        val fallback = availableModels.firstOrNull { it.contains("gemini") && (it.contains("pro") || it.contains("flash")) }
            ?: "gemini-flash-latest" // Hard fallback if everything fails (network down + no cache)

        aapsLogger.warn(LTag.AIMI, "[$TAG] Using last resort fallback: $fallback")
        return fallback
    }

    /**
     * Helper to construct the full URL for a resolved model
     */
    fun getGenerateContentUrl(modelId: String, apiKey: String): String {
        return "$BASE_URL/$modelId:generateContent?key=$apiKey"
    }

    private fun getOrFetchModels(apiKey: String): Set<String> {
        // 1. Check Memory Cache Validity
        readFreshMemoryCache()?.let { return it }

        // 2. Check Disk Cache Validity
        if (cache.isFresh(PREFS_NAME, KEY_CACHE_TIMESTAMP, CACHE_TTL_MS)) {
            val jsonStr = cache.getString(PREFS_NAME, KEY_AVAILABLE_MODELS)
            if (jsonStr != null) {
                val set = parseModelsSet(jsonStr)
                if (set.isNotEmpty()) {
                    updateMemoryCache(set)
                    return set
                }
            }
        }

        // 3. Fetch Network
        return try {
            val freshModels = fetchModelsFromApi(apiKey)
            if (freshModels.isEmpty()) throw Exception("Empty model list returned")

            // Save to Disk
            cache.putLong(PREFS_NAME, KEY_CACHE_TIMESTAMP, aimiWallClockMs())
            cache.putString(PREFS_NAME, KEY_AVAILABLE_MODELS, freshModels.joinToString(","))

            updateMemoryCache(freshModels)
            freshModels
        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "[$TAG] Failed to fetch models: ${e.message}. Using cache/fallback.")
            // If we have STALE memory cache, use it
            readStaleMemoryCache()?.let { return it }

            // If we have STALE disk cache, use it
            val jsonStr = cache.getString(PREFS_NAME, KEY_AVAILABLE_MODELS)
            if (jsonStr != null) {
                val set = parseModelsSet(jsonStr)
                if (set.isNotEmpty()) {
                    updateMemoryCache(set)
                    return set
                }
            }

            // Absolute failure -> Return Priority List as "assumed available" to attempt
            FALLBACK_PRIORITY.toSet()
        }
    }

    /** The in-memory model list while it is still inside its 24 hour life, or `null`. */
    private fun readFreshMemoryCache(): Set<String>? = cacheLock.withLock {
        val models = memoryCache
        if (models.isNotEmpty() && !isCacheExpired()) models else null
    }

    /** The in-memory model list whatever its age, or `null` when nothing was ever cached. */
    private fun readStaleMemoryCache(): Set<String>? = cacheLock.withLock {
        val models = memoryCache
        if (models.isNotEmpty()) models else null
    }

    private fun updateMemoryCache(models: Set<String>) {
        cacheLock.withLock {
            memoryCache = models
            lastCacheUpdate = aimiWallClockMs()
        }
    }

    /** Only called with [cacheLock] held, because it reads [lastCacheUpdate]. */
    private fun isCacheExpired(): Boolean {
        return (aimiWallClockMs() - lastCacheUpdate) > CACHE_TTL_MS
    }

    private fun parseModelsSet(csv: String): Set<String> {
        return csv.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun fetchModelsFromApi(apiKey: String): Set<String> {
        val start = aimiWallClockMs()
        try {
            val httpResponse = aimiHttp.execute(
                AimiHttpRequest(
                    url = "$BASE_URL?key=$apiKey",
                    method = "GET",
                    connectTimeoutMs = 10000,
                    readTimeoutMs = 10000
                )
            )

            val status = httpResponse.code
            if (status != 200) {
                val errorMsg = httpResponse.body ?: "Unknown Error"
                aapsLogger.error(LTag.AIMI, "[$TAG] ListModels failed: $status - ${errorMsg.take(300)}")
                return emptySet()
            }

            val response = httpResponse.body ?: ""
            val latency = aimiWallClockMs() - start
            aapsLogger.debug(LTag.AIMI, "[$TAG] ListModels success ($latency ms). Response size: ${response.length}")

            val json = Json.parseToJsonElement(response).jsonObject
            if (!json.hasCompat("models")) return emptySet()

            // Same as the old getJSONArray: a "models" that is not an array raises, and the catch
            // below turns that into an empty answer.
            val modelsArray = json.optJsonArrayCompat("models") ?: throw Exception("models is not an array")
            val resultSet = mutableSetOf<String>()

            for (i in 0 until modelsArray.size) {
                val m = modelsArray[i].jsonObject
                val name = m.getValue("name").jsonPrimitive.content // e.g. "models/gemini-pro"
                val supportedMethods = m.optJsonArrayCompat("supportedGenerationMethods")

                var supportsGenerateContent = false
                if (supportedMethods != null) {
                    for (j in 0 until supportedMethods.size) {
                        if (supportedMethods[j].jsonPrimitive.content == "generateContent") {
                            supportsGenerateContent = true
                            break
                        }
                    }
                }

                if (supportsGenerateContent) {
                    // Extract ID: "models/gemini-pro" -> "gemini-pro"
                    val id = name.removePrefix("models/")
                    resultSet.add(id)
                }
            }

            aapsLogger.debug(LTag.AIMI, "[$TAG] Found ${resultSet.size} models supporting generateContent: $resultSet")
            return resultSet
        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "[$TAG] Fetch Error: ${e.message}")
            return emptySet()
        }
    }
}
