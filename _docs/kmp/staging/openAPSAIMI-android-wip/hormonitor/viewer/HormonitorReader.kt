package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Read-only, off-main-thread reader for the Hormonitor study files.
 *
 * [candidateDirs] are searched in order for each file name (the exporter writes to a shared dir with an
 * app-scoped fallback), so the viewer finds the data wherever it landed. Nothing is ever written.
 */
class HormonitorReader(
    private val candidateDirs: List<File>,
) {

    private companion object {
        const val DAILY_FILE = "AIMI_HORMONITOR_daily_outcomes_v1.jsonl"
        const val EVENT_FILE = "AIMI_HORMONITOR_event_stream_v1.jsonl"
        const val MAX_NARRATIVE_SAMPLES = 6
    }

    private fun dayKeyFormatter(): SimpleDateFormat =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = TimeZone.getDefault() }

    private fun firstExisting(fileName: String): File? =
        runCatching { candidateDirs.map { File(it, fileName) }.firstOrNull { it.isFile && it.length() > 0 } }
            .getOrNull()

    /** True when at least one Hormonitor file is present. */
    suspend fun hasData(): Boolean = withContext(Dispatchers.IO) {
        firstExisting(DAILY_FILE) != null || firstExisting(EVENT_FILE) != null
    }

    /**
     * Day list from the compact daily_outcomes file. The file appends a cumulative record every ~30 min, so
     * we keep the LATEST record per `day_local` (highest generated_at). Sorted most-recent-day first.
     * Never throws — any I/O or permission failure yields an empty list (the UI shows an empty state).
     */
    suspend fun readDays(): List<HormonitorDaySummary> = withContext(Dispatchers.IO) {
        runCatching { readDaysInternal() }.getOrDefault(emptyList())
    }

    private fun readDaysInternal(): List<HormonitorDaySummary> {
        val file = firstExisting(DAILY_FILE) ?: return emptyList()
        val latestByDay = HashMap<String, Pair<String, HormonitorDaySummary>>() // day -> (generatedAt, summary)
        file.bufferedReader().useLines { lines ->
            lines.forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                val o = runCatching { JSONObject(line) }.getOrNull() ?: return@forEach
                val day = o.optString("day_local").takeIf { it.isNotBlank() } ?: return@forEach
                val generatedAt = o.optString("generated_at")
                val summary = HormonitorDaySummary(
                    dayLocal = day,
                    schemaVersion = o.optStringOrNull("schema_version"),
                    tirLowPct = o.optDoubleOrNull("tir_low_pct"),
                    tirInRangePct = o.optDoubleOrNull("tir_in_range_pct"),
                    tirAbovePct = o.optDoubleOrNull("tir_above_pct"),
                    tdd24hU = o.optDoubleOrNull("tdd_24h_total_u"),
                    decisionTotal = o.optInt("decision_count_total", 0),
                    decisionSmb = o.optInt("decision_count_smb", 0),
                    decisionSuspend = o.optInt("decision_count_suspend", 0),
                    decisionTbrUp = o.optInt("decision_count_tbr_up", 0),
                    decisionTbrDown = o.optInt("decision_count_tbr_down", 0),
                    decisionNone = o.optInt("decision_count_none", 0),
                    decisionVeto = o.optInt("decision_count_physio_veto", 0),
                    sourceReliabilityScore = o.optDoubleOrNull("source_reliability_score"),
                    sourceStale = if (o.isNull("source_stale_flag")) null else o.optBoolean("source_stale_flag"),
                    sourceOrigin = o.optStringOrNull("source_snapshot_origin"),
                )
                val prev = latestByDay[day]
                if (prev == null || generatedAt >= prev.first) latestByDay[day] = generatedAt to summary
            }
        }
        return latestByDay.values.map { it.second }.sortedByDescending { it.dayLocal }
    }

    /**
     * Rich aggregation for one day from the event stream. Streams the file line by line (constant memory),
     * keeps only events whose local day matches [dayLocal], and folds them into a [HormonitorDayDetail].
     * Never throws — any I/O or permission failure yields null (the UI shows a no-events state).
     */
    suspend fun readDayDetail(dayLocal: String): HormonitorDayDetail? = withContext(Dispatchers.IO) {
        runCatching { readDayDetailInternal(dayLocal) }.getOrNull()
    }

    private fun readDayDetailInternal(dayLocal: String): HormonitorDayDetail? {
        val file = firstExisting(EVENT_FILE) ?: return null
        val bounds = ensureIndex(file).dayRanges[dayLocal] ?: return null // day not in index → absent
        val fmt = dayKeyFormatter()
        val acc = DayAccumulator()
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(bounds.first)
            val bytes = ByteArray((bounds.second - bounds.first).toInt())
            raf.readFully(bytes)
            // Decode the day's slice as UTF-8 (narratives may be multi-byte) and fold, re-checking the day.
            String(bytes, Charsets.UTF_8).lineSequence().forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty()) return@forEach
                val o = runCatching { JSONObject(line) }.getOrNull()
                if (o == null) { acc.malformed++; return@forEach }
                val ts = o.optLong("timestamp", 0L)
                if (ts <= 0L) return@forEach
                if (fmt.format(Date(ts)) != dayLocal) return@forEach
                acc.fold(o, ts)
            }
        }
        return if (acc.eventCount == 0 && acc.malformed == 0) null else acc.build(dayLocal)
    }

    // --- Per-day byte-offset index over the event stream. Built once (single fast scan that only parses each
    //     line's timestamp), cached, and rebuilt when the file grows/changes. Lets a day's detail seek straight
    //     to its slice instead of re-scanning the whole (~16 MB) file on every day switch. ---
    private data class EventIndex(
        val path: String,
        val length: Long,
        val lastModified: Long,
        val dayRanges: Map<String, Pair<Long, Long>>, // day_local -> [startByte, endByteExclusive)
    )

    @Volatile
    private var cachedIndex: EventIndex? = null
    private val tsRegex = Regex("\"timestamp\"\\s*:\\s*(\\d+)")

    @Synchronized
    private fun ensureIndex(file: File): EventIndex {
        val cur = cachedIndex
        if (cur != null && cur.path == file.path && cur.length == file.length() && cur.lastModified == file.lastModified()) {
            return cur
        }
        val built = buildIndex(file)
        cachedIndex = built
        return built
    }

    private fun buildIndex(file: File): EventIndex {
        val fmt = dayKeyFormatter()
        val ranges = LinkedHashMap<String, Pair<Long, Long>>()
        RandomAccessFile(file, "r").use { raf ->
            var lineStart = raf.filePointer
            while (true) {
                val line = raf.readLine() ?: break
                val lineEnd = raf.filePointer
                val ts = tsRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull()
                if (ts != null && ts > 0L) {
                    val day = fmt.format(Date(ts))
                    val existing = ranges[day]
                    ranges[day] = if (existing == null) lineStart to lineEnd else existing.first to lineEnd
                }
                lineStart = lineEnd
            }
        }
        return EventIndex(file.path, file.length(), file.lastModified(), ranges)
    }

    // --- JSONObject helpers (treat JSON null / "null" / blank as absent) ---
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (isNull(key)) return null
        val s = optString(key)
        return s.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        if (isNull(key)) return null
        val d = optDouble(key, Double.NaN)
        return d.takeIf { it.isFinite() }
    }

    /** Mutable fold target; converted to the immutable [HormonitorDayDetail] at the end. */
    private inner class DayAccumulator {
        var eventCount = 0
        var malformed = 0
        var vetoCount = 0
        var predictiveHypoSuppressed = 0
        var patientStoryPresent = 0
        var physioSnapshotValid = 0
        var firstTs: Long? = null
        var lastTs: Long? = null

        val cyclePhase = HashMap<String, Int>()
        val cycleTrackingMode = HashMap<String, Int>()
        val contraceptive = HashMap<String, Int>()
        val thyroid = HashMap<String, Int>()
        val inflammation = HashMap<String, Int>()
        val physioState = HashMap<String, Int>()
        val activityState = HashMap<String, Int>()
        val patientMode = HashMap<String, Int>()
        val strategyHint = HashMap<String, Int>()
        val finalDecision = HashMap<String, Int>()
        val safetyGate = HashMap<String, Int>()
        val safetyPhase = HashMap<String, Int>()
        val vetoReason = HashMap<String, Int>()
        val reasonCode = HashMap<String, Int>()
        val schemaVersions = LinkedHashSet<String>()
        val narratives = LinkedHashSet<String>()

        val cycleDay = ArrayList<Double>()
        val wcycleBasal = ArrayList<Double>()
        val wcycleSmb = ArrayList<Double>()
        val wcycleIsf = ArrayList<Double>()
        val confidence = ArrayList<Double>()
        val dataQuality = ArrayList<Double>()
        val isfFactor = ArrayList<Double>()
        val basalFactor = ArrayList<Double>()
        val smbFactor = ArrayList<Double>()
        val reactivityFactor = ArrayList<Double>()
        val steps15 = ArrayList<Double>()
        val hrNow = ArrayList<Double>()
        val sleepEff = ArrayList<Double>()
        val compositeMin = ArrayList<Double>()
        val modeConfidence = ArrayList<Double>()

        fun fold(o: JSONObject, ts: Long) {
            eventCount++
            firstTs = minOf(firstTs ?: ts, ts)
            lastTs = maxOf(lastTs ?: ts, ts)
            o.optStringOrNull("schema_version")?.let { schemaVersions.add(it) }

            o.optStringOrNull("cycle_phase")?.let { cyclePhase.bump(it) }
            o.optStringOrNull("cycle_tracking_mode")?.let { cycleTrackingMode.bump(it) }
            o.optStringOrNull("contraceptive_type")?.let { contraceptive.bump(it) }
            o.optStringOrNull("thyroid_status")?.let { thyroid.bump(it) }
            o.optStringOrNull("inflammation_status")?.let { inflammation.bump(it) }
            o.optStringOrNull("physio_state")?.let { physioState.bump(it) }
            o.optStringOrNull("activity_state")?.let { activityState.bump(it) }
            o.optStringOrNull("safety_gate")?.let { safetyGate.bump(it) }
            o.optStringOrNull("safety_phase")?.let { safetyPhase.bump(it) }
            o.optStringOrNull("final_loop_decision_type")?.let { finalDecision.bump(it) }

            o.optDoubleOrNull("cycle_day")?.let { cycleDay.add(it) }
            o.optDoubleOrNull("wcycle_basal_mult")?.let { wcycleBasal.add(it) }
            o.optDoubleOrNull("wcycle_smb_mult")?.let { wcycleSmb.add(it) }
            o.optDoubleOrNull("wcycle_isf_mult")?.let { wcycleIsf.add(it) }
            o.optDoubleOrNull("physio_confidence")?.let { confidence.add(it) }
            o.optDoubleOrNull("physio_data_quality")?.let { dataQuality.add(it) }
            o.optDoubleOrNull("isf_factor")?.let { isfFactor.add(it) }
            o.optDoubleOrNull("basal_factor")?.let { basalFactor.add(it) }
            o.optDoubleOrNull("smb_factor")?.let { smbFactor.add(it) }
            o.optDoubleOrNull("reactivity_factor")?.let { reactivityFactor.add(it) }
            o.optDoubleOrNull("steps_15m")?.let { steps15.add(it) }
            o.optDoubleOrNull("hr_now_bpm")?.let { hrNow.add(it) }
            o.optDoubleOrNull("sleep_efficiency")?.let { sleepEff.add(it) }
            o.optDoubleOrNull("safety_composite_min_mgdl")?.let { compositeMin.add(it) }

            if (o.optStringOrNull("physio_veto_reason") != null) {
                vetoCount++
                vetoReason.bump(o.optStringOrNull("physio_veto_reason")!!)
            }
            if (!o.isNull("predictive_hypo_suppressed") && o.optBoolean("predictive_hypo_suppressed")) {
                predictiveHypoSuppressed++
            }
            if (!o.isNull("physio_snapshot_valid_flag") && o.optBoolean("physio_snapshot_valid_flag")) {
                physioSnapshotValid++
            }

            val story = o.optJSONObject("patient_story")
            if (story != null) {
                patientStoryPresent++
                story.optStringOrNull("patient_mode")?.let { patientMode.bump(it) }
                story.optStringOrNull("patient_strategy_hint")?.let { strategyHint.bump(it) }
                story.optDoubleOrNull("patient_mode_confidence")?.let { modeConfidence.add(it) }
                story.optStringOrNull("patient_narrative")?.let {
                    if (narratives.size < MAX_NARRATIVE_SAMPLES) narratives.add(it)
                }
                val codes = story.optJSONArray("patient_reason_codes")
                if (codes != null) {
                    for (i in 0 until codes.length()) {
                        val c = codes.optString(i).takeIf { it.isNotBlank() } ?: continue
                        reasonCode.bump(c)
                    }
                }
            }
        }

        fun build(dayLocal: String): HormonitorDayDetail {
            val n = eventCount
            return HormonitorDayDetail(
                dayLocal = dayLocal,
                eventCount = n,
                hormonal = HormonitorHormonalAgg(
                    cyclePhases = cyclePhase.toLabelCounts(n),
                    cycleDayRange = StatRange.of(cycleDay),
                    cycleTrackingModes = cycleTrackingMode.toLabelCounts(n),
                    contraceptiveTypes = contraceptive.toLabelCounts(n),
                    thyroidStatuses = thyroid.toLabelCounts(n),
                    inflammationStatuses = inflammation.toLabelCounts(n),
                    wcycleBasalMult = StatRange.of(wcycleBasal),
                    wcycleSmbMult = StatRange.of(wcycleSmb),
                    wcycleIsfMult = StatRange.of(wcycleIsf),
                ),
                physio = HormonitorPhysioAgg(
                    physioStates = physioState.toLabelCounts(n),
                    activityStates = activityState.toLabelCounts(n),
                    meanConfidence = confidence.meanOrNull(),
                    meanDataQuality = dataQuality.meanOrNull(),
                    isfFactor = StatRange.of(isfFactor),
                    basalFactor = StatRange.of(basalFactor),
                    smbFactor = StatRange.of(smbFactor),
                    reactivityFactor = StatRange.of(reactivityFactor),
                    steps15m = StatRange.of(steps15),
                    hrNowBpm = StatRange.of(hrNow),
                    sleepEfficiency = StatRange.of(sleepEff),
                ),
                treeHarmonia = HormonitorTreeHarmoniaAgg(
                    patientModes = patientMode.toLabelCounts(n),
                    strategyHints = strategyHint.toLabelCounts(n),
                    reasonCodes = reasonCode.toLabelCounts(n),
                    finalDecisions = finalDecision.toLabelCounts(n),
                    meanModeConfidence = modeConfidence.meanOrNull(),
                    vetoCount = vetoCount,
                    vetoReasons = vetoReason.toLabelCounts(n),
                    narrativeSamples = narratives.toList(),
                ),
                safety = HormonitorSafetyAgg(
                    safetyGates = safetyGate.toLabelCounts(n),
                    safetyPhases = safetyPhase.toLabelCounts(n),
                    predictiveHypoSuppressedCount = predictiveHypoSuppressed,
                    compositeMinMgdl = StatRange.of(compositeMin),
                ),
                integrity = HormonitorIntegrityAgg(
                    recordCount = n,
                    malformedLineCount = malformed,
                    schemaVersions = schemaVersions.toList(),
                    firstTimestamp = firstTs,
                    lastTimestamp = lastTs,
                    patientStoryCoverage = if (n > 0) patientStoryPresent.toDouble() / n else 0.0,
                    physioSnapshotCoverage = if (n > 0) physioSnapshotValid.toDouble() / n else 0.0,
                ),
            )
        }
    }
}

private fun MutableMap<String, Int>.bump(key: String) {
    this[key] = (this[key] ?: 0) + 1
}

private fun List<Double>.meanOrNull(): Double? = filter { it.isFinite() }.let { if (it.isEmpty()) null else it.average() }
