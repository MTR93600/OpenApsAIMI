package app.aaps.plugins.aps.openAPSAIMI.llm.gemini

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * reliable resolver for Gemini Model IDs.
 * Handles dynamic listing, fallback logic, and caching.
 */
@Singleton
class GeminiModelResolver @Inject constructor(
    private val context: Context
) {

    companion object {
        private const val TAG = "AIMI_GEMINI"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        
        // PREFS
        private const val PREFS_NAME = "aimi_gemini_cache"
        private const val KEY_CACHE_TIMESTAMP = "cache_ts"
        private const val KEY_AVAILABLE_MODELS = "available_models_json"
        
        // TTL: 24h
        private val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(24)

        // Fallback fallback priority list
        private val FALLBACK_PRIORITY = listOf(
            "gemini-3-pro-preview",
            "gemini-3-flash-preview",
            "gemini-2.5-pro",
            "gemini-2.5-flash",
            "gemini-1.5-pro",
            "gemini-1.5-flash"
        )
    }

    private val memoryCache = ConcurrentHashMap<String, Boolean>() // ModelID -> True if exists
    private var lastCacheUpdate: Long = 0

    /**
     * Resolves a valid model ID for generateContent calls.
     * 
     * @param apiKey The API Key to use for listing models
     * @param preferredModel The user's preferred model (e.g. "gemini-3-pro-preview")
     * @return A valid model ID (e.g. "gemini-3-pro-preview") ready for use in URL
     */
    fun resolveGenerateContentModel(apiKey: String, preferredModel: String?): String {
        val availableModels = getOrFetchModels(apiKey)
        
        // 1. Check preferred
        if (!preferredModel.isNullOrBlank()) {
            val sanitized = preferredModel.trim().removePrefix("models/")
            if (availableModels.contains(sanitized)) {
                Log.d(TAG, "Using preferred model: $sanitized")
                return sanitized
            } else {
                 Log.w(TAG, "Preferred model '$sanitized' not found in available list.")
            }
        }

        // 2. Iterate Priority List
        for (candidate in FALLBACK_PRIORITY) {
            if (availableModels.contains(candidate)) {
                Log.i(TAG, "Fallback to high-priority model: $candidate")
                return candidate
            }
        }

        // 3. Last resort - Find anything that looks like "gemini" and "pro" or "flash"
        val fallback = availableModels.firstOrNull { it.contains("gemini") && (it.contains("pro") || it.contains("flash")) }
            ?: "gemini-2.5-flash" // Hard fallback if everything fails (network down + no cache)

        Log.w(TAG, "Using last resort fallback: $fallback")
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
        if (memoryCache.isNotEmpty() && !isCacheExpired()) {
            return memoryCache.keys
        }

        // 2. Check Disk Cache Validity
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val diskTs = prefs.getLong(KEY_CACHE_TIMESTAMP, 0)
        
        if (System.currentTimeMillis() - diskTs < CACHE_TTL_MS) {
            val jsonStr = prefs.getString(KEY_AVAILABLE_MODELS, null)
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
            prefs.edit()
                .putLong(KEY_CACHE_TIMESTAMP, System.currentTimeMillis())
                .putString(KEY_AVAILABLE_MODELS, freshModels.joinToString(","))
                .apply()
            
            updateMemoryCache(freshModels)
            freshModels
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch models: ${e.message}. Using cache/fallback.")
            // If we have STALE memory cache, use it
            if (memoryCache.isNotEmpty()) return memoryCache.keys
            
            // If we have STALE disk cache, use it
            val jsonStr = prefs.getString(KEY_AVAILABLE_MODELS, null)
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

    private fun updateMemoryCache(models: Set<String>) {
        memoryCache.clear()
        models.forEach { memoryCache[it] = true }
        lastCacheUpdate = System.currentTimeMillis()
    }

    private fun isCacheExpired(): Boolean {
        return (System.currentTimeMillis() - lastCacheUpdate) > CACHE_TTL_MS
    }

    private fun parseModelsSet(csv: String): Set<String> {
        return csv.split(",").filter { it.isNotBlank() }.toSet()
    }

    private fun fetchModelsFromApi(apiKey: String): Set<String> {
        val start = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL?key=$apiKey")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val status = connection.responseCode
            if (status != 200) {
                 val errorStream = connection.errorStream
                 val errorMsg = errorStream?.bufferedReader()?.use { it.readText() } ?: "Unknown Error"
                 Log.e(TAG, "ListModels failed: $status - ${errorMsg.take(300)}")
                 return emptySet()
            }

            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val latency = System.currentTimeMillis() - start
            Log.d(TAG, "ListModels success ($latency ms). Response size: ${response.length}")
            
            val json = JSONObject(response)
            if (!json.has("models")) return emptySet()
            
            val modelsArray = json.getJSONArray("models")
            val resultSet = mutableSetOf<String>()
            
            for (i in 0 until modelsArray.length()) {
                val m = modelsArray.getJSONObject(i)
                val name = m.getString("name") // e.g. "models/gemini-pro"
                val supportedMethods = m.optJSONArray("supportedGenerationMethods")
                
                var supportsGenerateContent = false
                if (supportedMethods != null) {
                    for (j in 0 until supportedMethods.length()) {
                        if (supportedMethods.getString(j) == "generateContent") {
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
            
            Log.d(TAG, "Found ${resultSet.size} models supporting generateContent: $resultSet")
            return resultSet

        } catch (e: Exception) {
            Log.e(TAG, "Fetch Error: ${e.message}")
            return emptySet()
        } finally {
            connection?.disconnect()
        }
    }
}
