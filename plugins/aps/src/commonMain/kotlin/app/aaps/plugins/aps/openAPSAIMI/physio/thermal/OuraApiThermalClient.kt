package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import app.aaps.core.data.json.OrgJsonCompat.hasCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonArrayCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttp
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiHttpRequest
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.withContext
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toInstant
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Clock

/**
 * Fetches Oura daily readiness temperature deviation (not exported to Health Connect).
 *
 * Requires a personal access token from https://cloud.ouraring.com/personal-access-tokens
 */
@SingleIn(AppScope::class)
class OuraApiThermalClient @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val aimiHttp: AimiHttp,
) {

    private companion object {

        /** The two waits this client has always used, kept per call rather than as a client default. */
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }

    suspend fun fetchSamples(daysBack: Int): List<ThermalSampleMTR> = withContext(aapsIoDispatcher) {
        val token = preferences.get(AimiStringKey.OuraPersonalAccessToken).trim()
        if (token.isEmpty()) return@withContext emptyList()

        val zone = TimeZone.currentSystemDefault()
        val endDate = Clock.System.todayIn(zone)
        val startDate = endDate.minus(DatePeriod(days = daysBack.coerceAtLeast(1)))
        val url =
            "https://api.ouraring.com/v2/usercollection/daily_readiness" +
                "?start_date=$startDate&end_date=$endDate"

        try {
            val response = aimiHttp.execute(
                AimiHttpRequest(
                    url = url,
                    method = "GET",
                    connectTimeoutMs = CONNECT_TIMEOUT_MS,
                    readTimeoutMs = READ_TIMEOUT_MS,
                    headers = mapOf("Authorization" to "Bearer $token")
                )
            )
            if (!response.isSuccessful) {
                aapsLogger.warn(LTag.APS, "[OuraThermal] HTTP ${response.code}: ${response.reason.orEmpty()}")
                return@withContext emptyList()
            }
            parseReadiness(response.body.orEmpty())
        } catch (e: Exception) {
            aapsLogger.warn(LTag.APS, "[OuraThermal] Fetch failed: ${e.message}")
            emptyList()
        }
    }

    private fun parseReadiness(body: String): List<ThermalSampleMTR> {
        if (body.isBlank()) return emptyList()
        val root = Json.parseToJsonElement(body).jsonObject
        if (!root.hasCompat("data")) return emptyList()
        val data = root.optJsonArrayCompat("data") ?: return emptyList()
        val zone = TimeZone.currentSystemDefault()
        val samples = mutableListOf<ThermalSampleMTR>()

        for (index in data.indices) {
            val item = data[index].jsonObject
            val deviation = readDeviation(item, "temperature_deviation") ?: continue
            val day = item.optStringCompat("day")
            val timestampMs = LocalDateTime(LocalDate.parse(day), LocalTime(12, 0))
                .toInstant(zone)
                .toEpochMilliseconds()
            samples += ThermalSampleMTR(
                timestampMs = timestampMs,
                deltaCelsius = deviation,
                measurementLocation = "FINGER",
                dataOrigin = ThermalDataOrigins.OURA_API,
            )
            val trend = readDeviation(item, "temperature_trend_deviation")
            if (trend != null) {
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

    /**
     * The number at [key], or `null` when the field is missing or holds a JSON null.
     *
     * This is the `isNull` test the parser used before the port: `org.json` answered true both for a
     * field that is not there and for one written as `null`, and Oura sends the second on a day it
     * did not measure. Both are skipped, so a day without a reading adds no sample.
     */
    private fun readDeviation(item: JsonObject, key: String): Double? {
        val element = item[key] ?: return null
        if (element is JsonNull) return null
        return (element as? JsonPrimitive)?.doubleOrNull
    }
}
