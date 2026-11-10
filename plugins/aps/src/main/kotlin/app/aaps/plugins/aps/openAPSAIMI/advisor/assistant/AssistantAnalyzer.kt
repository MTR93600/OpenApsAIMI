package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import java.io.File
import kotlin.math.roundToInt

/** Reads the AIMI decision log and finds low and late episodes. No dose numbers are used: the log holds proposals. */
object AssistantAnalyzer {

    private const val DAYS_BACK = 5
    private const val LOW_MGDL = 63.0
    private const val RISE_START_MGDL = 126.0
    private const val HIGH_MGDL = 144.0

    private val bgRegex = Regex("\"current_bg_mgdl\":\\s*(-?[0-9.]+)")
    private val iobRegex = Regex("\"iob_u\":\\s*(-?[0-9.]+)")
    private val tsRegex = Regex("\"timestamp\":\\s*([0-9]+)")
    private val smbRegex = Regex("\"decision\":\\s*\"SMB_Delivery\"")

    /** Streams the file once. Only a few numbers are kept per line. */
    fun readSamples(file: File, nowMs: Long): List<AssistantSample> {
        if (!file.exists() || !file.canRead()) return emptyList()
        val fromMs = nowMs - DAYS_BACK * 24L * 3600_000L
        val result = ArrayList<AssistantSample>()
        file.bufferedReader().useLines { lines ->
            for (line in lines) {
                val ts = tsRegex.find(line)?.groupValues?.get(1)?.toLongOrNull() ?: continue
                if (ts < fromMs) continue
                val bg = bgRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
                val iob = iobRegex.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
                result.add(AssistantSample(ts, bg, iob, smbRegex.containsMatchIn(line)))
            }
        }
        result.sortBy { it.timeMs }
        return result
    }

    fun stats(samples: List<AssistantSample>, fromMs: Long, toMs: Long): AssistantStats? {
        val part = samples.filter { it.timeMs in fromMs..toMs }
        if (part.size < 30) return null
        val tir = part.count { it.bgMgdl in 70.0..180.0 } * 100.0 / part.size
        val low = part.count { it.bgMgdl < 70.0 } * 100.0 / part.size
        val mean = part.sumOf { it.bgMgdl } / part.size / 18.0
        return AssistantStats(part.size, tir.roundToInt(), (low * 10).roundToInt() / 10.0, (mean * 10).roundToInt() / 10.0)
    }

    fun lowEpisodes(samples: List<AssistantSample>): List<LowEpisode> {
        val out = ArrayList<LowEpisode>()
        var lastLowMs = Long.MIN_VALUE
        for ((i, s) in samples.withIndex()) {
            if (s.bgMgdl >= LOW_MGDL) continue
            val isNew = s.timeMs - lastLowMs > 2 * 3600_000L
            lastLowMs = s.timeMs
            if (!isNew) continue
            val before = samples.filter { it.timeMs in (s.timeMs - 2 * 3600_000L)..s.timeMs }
            val smb = before.filter { it.isSmb }
            val gaps = smb.zipWithNext { a, b -> (b.timeMs - a.timeMs) / 60_000.0 }
            val minBg = samples.drop(i).takeWhile { it.timeMs - s.timeMs < 60 * 60_000L }.minOf { it.bgMgdl }
            out.add(
                LowEpisode(
                    timeMs = s.timeMs,
                    minMmol = (minBg / 18.0 * 10).roundToInt() / 10.0,
                    smbCount2h = smb.size,
                    smbMinutesBetween = if (gaps.isEmpty()) 0.0 else (gaps.average() * 10).roundToInt() / 10.0,
                    iobStart = before.firstOrNull()?.iob ?: s.iob,
                )
            )
        }
        return out
    }

    fun lateEpisodes(samples: List<AssistantSample>): List<LateEpisode> {
        val out = ArrayList<LateEpisode>()
        var i = 0
        while (i < samples.size) {
            val s = samples[i]
            if (s.bgMgdl >= HIGH_MGDL) {
                val back = samples.filter { it.timeMs in (s.timeMs - 60 * 60_000L)..s.timeMs }
                val startedLow = back.any { it.bgMgdl < RISE_START_MGDL }
                if (startedLow) {
                    val riseStart = back.first { it.bgMgdl >= RISE_START_MGDL || it.bgMgdl == back.minOf { b -> b.bgMgdl } }.timeMs
                    val firstSmb = samples.firstOrNull { it.isSmb && it.timeMs >= riseStart }
                    val delay = if (firstSmb == null) 999 else ((firstSmb.timeMs - riseStart) / 60_000L).toInt()
                    if (delay > 15) out.add(LateEpisode(s.timeMs, delay.coerceAtMost(180)))
                }
                // skip the rest of this high period
                while (i < samples.size && samples[i].bgMgdl >= RISE_START_MGDL) i++
            } else i++
        }
        return out
    }
}
