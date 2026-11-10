package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import java.io.File
import java.io.RandomAccessFile
import org.json.JSONObject

/** One step of the SMB chain: how much insulin the engine wanted before the step and what was left after it. */
data class LiveStage(
    val name: String,
    val kind: String,
    val beforeU: Double,
    val afterU: Double,
) {
    val cutU: Double get() = (beforeU - afterU).coerceAtLeast(0.0)
}

/** One loop cycle read from the decision log. */
data class LiveCycle(
    val timeMs: Long,
    val bgMgdl: Double,
    val iobU: Double,
    val decision: String,
    val amountU: Double,
    val patientMode: String?,
    val postHypoTag: String?,
    val maxSmbU: Double?,
    val finalOwner: String?,
    val deltaMgdl5m: Double?,
    val stages: List<LiveStage>,
    /** True when the log text of this cycle says the sport safety guard set the SMB to 0. */
    val sportSafety: Boolean = false,
) {
    /** True when the post-hypo guard was active in this cycle. */
    val postHypoActive: Boolean get() = postHypoTag != null && !postHypoTag.startsWith("inactive")
    val cutByName: Map<String, Double> get() = stages.groupBy { it.name }.mapValues { e -> e.value.sumOf { it.cutU } }
}

/**
 * Reads the end of the AIMI decision log. Only the tail of the file is read (a few MB), so it is cheap to call
 * every minute. It never writes and never changes dosing.
 */
object AssistantLive {

    private const val TAIL_BYTES = 4L * 1024 * 1024

    /** Last [count] cycles, oldest first. A cut first line (the tail starts in the middle of a line) is dropped. */
    fun readTail(file: File, count: Int): List<LiveCycle> {
        if (!file.exists() || !file.canRead()) return emptyList()
        val lines = ArrayList<String>()
        RandomAccessFile(file, "r").use { raf ->
            val start = (raf.length() - TAIL_BYTES).coerceAtLeast(0L)
            raf.seek(start)
            val bytes = ByteArray((raf.length() - start).toInt())
            raf.readFully(bytes)
            val text = String(bytes, Charsets.UTF_8)
            val all = text.split('\n')
            // When we did not start at byte 0, the first piece may be a partial line.
            lines.addAll(if (start > 0) all.drop(1) else all)
        }
        return lines.asReversed().asSequence()
            .filter { it.startsWith("{") }
            .mapNotNull { parse(it) }
            .take(count)
            .toList()
            .asReversed()
    }

    /**
     * Reads the cycles of the last [hours] hours by streaming the file: one line at a time, so memory stays small
     * even when a line is 40 KB. Oldest first. Never writes.
     */
    fun readHours(file: File, nowMs: Long, hours: Int): List<LiveCycle> {
        if (!file.exists() || !file.canRead()) return emptyList()
        val fromMs = nowMs - hours * 3600_000L
        val out = ArrayList<LiveCycle>()
        RandomAccessFile(file, "r").use { raf ->
            // About 40 KB per cycle and one cycle per minute: start a bit before the window and skip what is older.
            val bytesBack = (hours * 60L * 48_000L).coerceAtMost(raf.length())
            raf.seek(raf.length() - bytesBack)
            val reader = java.io.BufferedReader(java.io.InputStreamReader(java.nio.channels.Channels.newInputStream(raf.channel), Charsets.UTF_8), 1 shl 16)
            var first = bytesBack < raf.length()
            while (true) {
                val line = reader.readLine() ?: break
                if (first) { first = false; continue } // may be a cut line
                if (!line.startsWith("{")) continue
                val cycle = parse(line) ?: continue
                if (cycle.timeMs >= fromMs) out.add(cycle)
            }
        }
        out.sortBy { it.timeMs }
        return out
    }

    internal fun parse(line: String): LiveCycle? = try {
        val json = JSONObject(line)
        val base = json.optJSONObject("baseline_state")
        val adj = json.optJSONObject("adjustments")
        val out = json.optJSONObject("outcome")
        val trace = adj?.optJSONObject("smb_binding_trace")
        val stagesJson = trace?.optJSONArray("stages")
        val stages = ArrayList<LiveStage>()
        if (stagesJson != null) {
            for (i in 0 until stagesJson.length()) {
                val s = stagesJson.optJSONObject(i) ?: continue
                stages.add(
                    LiveStage(
                        name = s.optString("name"),
                        kind = s.optString("kind"),
                        beforeU = s.optDouble("before_u", 0.0),
                        afterU = s.optDouble("after_u", 0.0),
                    )
                )
            }
        }
        LiveCycle(
            timeMs = json.optLong("timestamp"),
            bgMgdl = base?.optDouble("current_bg_mgdl", Double.NaN) ?: Double.NaN,
            iobU = base?.optDouble("iob_u", 0.0) ?: 0.0,
            decision = out?.optString("decision").orEmpty(),
            amountU = out?.optDouble("amount", 0.0) ?: 0.0,
            patientMode = adj?.optJSONObject("patient_mode")?.optString("mode")?.takeIf { it.isNotEmpty() },
            postHypoTag = adj?.optJSONObject("post_hypo_delivery")?.optString("reason_tag")?.takeIf { it.isNotEmpty() },
            maxSmbU = trace?.let { if (it.has("max_smb_u") && !it.isNull("max_smb_u")) it.optDouble("max_smb_u") else null },
            finalOwner = trace?.optString("final_owner")?.takeIf { it.isNotEmpty() },
            deltaMgdl5m = trace?.let { if (it.has("short_avg_delta_mgdl_5m") && !it.isNull("short_avg_delta_mgdl_5m")) it.optDouble("short_avg_delta_mgdl_5m") else null },
            stages = stages,
            sportSafety = out?.optString("narrative").orEmpty().contains("Safety sport"),
        )
    } catch (_: Exception) {
        null
    }

    /** How much insulin each step cut over the given cycles, biggest first. A step that never cut is listed with 0. */
    fun cutsByStage(cycles: List<LiveCycle>): List<Pair<String, Double>> {
        val sums = LinkedHashMap<String, Double>()
        for (c in cycles) for (s in c.stages) sums[s.name] = (sums[s.name] ?: 0.0) + s.cutU
        return sums.entries.map { it.key to it.value }.sortedByDescending { it.second }
    }
}
