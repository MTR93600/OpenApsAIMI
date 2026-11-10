package app.aaps.plugins.aps.openAPSAIMI.tpo

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

enum class TpoEpisodeType {
    HYPO,
    HYPER,
    REBOUND_RISE,
    HYPER_CRASH,
}

data class TpoEpisode(
    val type: TpoEpisodeType,
    val startedAtMs: Long,
    val peakAtMs: Long,
    val bgExtremeMgdl: Double,
    val sequenceIndex: Int,
) {
    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("type", type.name)
            put("started_at_ms", startedAtMs)
            put("peak_at_ms", peakAtMs)
            put("bg_extreme_mgdl", bgExtremeMgdl)
            put("sequence_index", sequenceIndex)
        }

    companion object {
        fun fromJsonObject(json: JSONObject): TpoEpisode? {
            val type = runCatching { TpoEpisodeType.valueOf(json.optString("type", "")) }.getOrNull()
                ?: return null
            return TpoEpisode(
                type = type,
                startedAtMs = json.optLong("started_at_ms", 0L),
                peakAtMs = json.optLong("peak_at_ms", 0L),
                bgExtremeMgdl = json.optDouble("bg_extreme_mgdl", 0.0),
                sequenceIndex = json.optInt("sequence_index", 0),
            )
        }
    }
}

data class TpoEpisodeLedger(
    val episodes: List<TpoEpisode> = emptyList(),
    val hypoCount24h: Int = 0,
    val updatedAtMs: Long = 0L,
) {
    fun hasHyperCrashWithin(windowMs: Long, nowMs: Long): Boolean {
        val cutoff = nowMs - windowMs
        return episodes.any { episode ->
            episode.type == TpoEpisodeType.HYPER_CRASH && episode.peakAtMs >= cutoff
        }
    }

    fun hasHypoEpisodeWithin(windowMs: Long, nowMs: Long): Boolean {
        val cutoff = nowMs - windowMs
        return episodes.any { episode ->
            episode.type == TpoEpisodeType.HYPO && episode.peakAtMs >= cutoff
        }
    }

    fun recentTimeline(maxItems: Int = 8): List<TpoEpisode> =
        episodes.sortedByDescending { it.peakAtMs }.take(maxItems)

    fun toJsonObject(): JSONObject =
        JSONObject().apply {
            put("updated_at_ms", updatedAtMs)
            put("hypo_count_24h", hypoCount24h)
            put("episodes", JSONArray().apply { episodes.forEach { put(it.toJsonObject()) } })
        }

    companion object {
        private const val RETENTION_MS = 48L * 60L * 60L * 1000L
        private const val MAX_EPISODES = 24

        fun fromJsonObject(json: JSONObject?): TpoEpisodeLedger {
            if (json == null) return TpoEpisodeLedger()
            val episodes = buildList {
                val array = json.optJSONArray("episodes") ?: JSONArray()
                for (index in 0 until array.length()) {
                    TpoEpisode.fromJsonObject(array.getJSONObject(index))?.let { add(it) }
                }
            }
            return TpoEpisodeLedger(
                episodes = episodes,
                hypoCount24h = json.optInt("hypo_count_24h", 0),
                updatedAtMs = json.optLong("updated_at_ms", 0L),
            )
        }

        fun update(
            ledger: TpoEpisodeLedger,
            input: TpoTickInput,
        ): TpoEpisodeLedger {
            val nowMs = input.nowMs
            val retained = ledger.episodes.filter { episode ->
                episode.peakAtMs >= nowMs - RETENTION_MS
            }.toMutableList()
            var hypoCount24h = retained.count {
                it.type == TpoEpisodeType.HYPO && it.peakAtMs >= nowMs - 24L * 60L * 60L * 1000L
            }

            fun appendEpisode(type: TpoEpisodeType, bgExtreme: Double) {
                val lastSame = retained.lastOrNull { it.type == type }
                if (lastSame != null && nowMs - lastSame.peakAtMs < 20L * 60L * 1000L) {
                    return
                }
                val sequenceIndex = when (type) {
                    TpoEpisodeType.HYPO -> hypoCount24h + 1
                    else -> retained.count { it.type == type } + 1
                }
                retained += TpoEpisode(
                    type = type,
                    startedAtMs = nowMs,
                    peakAtMs = nowMs,
                    bgExtremeMgdl = bgExtreme,
                    sequenceIndex = sequenceIndex,
                )
                if (type == TpoEpisodeType.HYPO) {
                    hypoCount24h += 1
                }
            }

            if (input.minBgLookback75m < 70.0 || input.bgMgdl < 70.0) {
                appendEpisode(TpoEpisodeType.HYPO, min(input.bgMgdl, input.minBgLookback75m))
            }
            if (input.bgMgdl >= 180.0) {
                appendEpisode(TpoEpisodeType.HYPER, input.bgMgdl)
            }
            if (input.reboundGuardActive && input.deltaMgdl5m >= 4.0 && input.bgMgdl in 95.0..170.0) {
                appendEpisode(TpoEpisodeType.REBOUND_RISE, input.bgMgdl)
            }
            val hyperPeak = maxOf(input.bgMgdl, input.eventMemory.recentHyperLoad * 180.0 + 120.0)
            val hypoFloor = min(input.bgMgdl, input.minBgLookback75m)
            if (hyperPeak >= 180.0 && hypoFloor < 85.0 &&
                input.eventMemory.postHyperExhaustionScore >= 0.55
            ) {
                appendEpisode(TpoEpisodeType.HYPER_CRASH, hypoFloor)
            }

            val trimmed = retained
                .sortedByDescending { it.peakAtMs }
                .take(MAX_EPISODES)
                .sortedBy { it.peakAtMs }
            return TpoEpisodeLedger(
                episodes = trimmed,
                hypoCount24h = hypoCount24h,
                updatedAtMs = nowMs,
            )
        }
    }
}
