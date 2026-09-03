package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

/**
 * Fetches Oura daily readiness temperature deviation (not exported to Health Connect).
 *
 * Requires a personal access token from https://cloud.ouraring.com/personal-access-tokens
 */
@SingleIn(AppScope::class)
class OuraApiThermalClient @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchSamples(daysBack: Int): List<ThermalSampleMTR> = withContext(Dispatchers.IO) {
        val token = preferences.get(AimiStringKey.OuraPersonalAccessToken).trim()
        if (token.isEmpty()) return@withContext emptyList()

        val endDate = LocalDate.now(ZoneId.systemDefault())
        val startDate = endDate.minusDays(daysBack.coerceAtLeast(1).toLong())
        val url =
            "https://api.ouraring.com/v2/usercollection/daily_readiness" +
                "?start_date=$startDate&end_date=$endDate"
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .get()
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    aapsLogger.warn(LTag.APS, "[OuraThermal] HTTP ${response.code}: ${response.message}")
                    return@withContext emptyList()
                }
                val body = response.body?.string().orEmpty()
                parseReadiness(body)
            }
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[OuraThermal] Fetch failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseReadiness(body: String): List<ThermalSampleMTR> {
        if (body.isBlank()) return emptyList()
        val root = JSONObject(body)
        if (!root.has("data")) return emptyList()
        val data = root.getJSONArray("data")
        val zone = ZoneId.systemDefault()
        val samples = mutableListOf<ThermalSampleMTR>()

        for (index in 0 until data.length()) {
            val item = data.getJSONObject(index)
            if (item.isNull("temperature_deviation")) continue
            val deviation = item.getDouble("temperature_deviation")
            val day = item.getString("day")
            val timestampMs = LocalDate.parse(day)
                .atTime(12, 0)
                .atZone(zone)
                .toInstant()
                .toEpochMilli()
            samples += ThermalSampleMTR(
                timestampMs = timestampMs,
                deltaCelsius = deviation,
                measurementLocation = "FINGER",
                dataOrigin = ThermalDataOrigins.OURA_API,
            )
            if (!item.isNull("temperature_trend_deviation")) {
                val trend = item.getDouble("temperature_trend_deviation")
                samples += ThermalSampleMTR(
                    timestampMs = timestampMs + 3_600_000L,
                    deltaCelsius = trend,
                    measurementLocation = "FINGER",
                    dataOrigin = ThermalDataOrigins.OURA_API,
                )
            }
        }

        if (samples.isNotEmpty()) {
            aapsLogger.info(LTag.APS, "[OuraThermal] ✅ ${samples.size} readiness temperature samples")
        }
        return samples.sortedBy { it.timestampMs }
    }
}
