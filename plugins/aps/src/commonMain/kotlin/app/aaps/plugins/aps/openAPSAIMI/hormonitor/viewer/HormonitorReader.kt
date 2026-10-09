package app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer

import app.aaps.core.data.json.OrgJsonCompat.optBooleanCompat
import app.aaps.core.data.json.OrgJsonCompat.optIntCompat
import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import app.aaps.core.data.json.OrgJsonCompat.optStringCompat
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

/**
 * Read-only, off-main-thread reader for the Hormonitor study files.
 *
 * The ported reader resolves both study files through [AimiStorage] instead of a caller-supplied
 * list of `java.io.File` directories: where the files may land (shared dir, app-scoped fallback)
 * is the storage layer's directory policy, not the reader's. Nothing is ever written.
 *
 * The per-day byte-offset index of the JVM version cannot go to commonMain (`RandomAccessFile`
 * does not exist there). It is replaced by a single streaming pass over the event file with
 * [AimiStorage.forEachLine] — constant memory like the old scan, no whole-file load — where each
 * line is first filtered by the raw `timestamp` (the same regex the old index used to bucket days)
 * and only the lines for the requested day are parsed and folded, re-checking the day from the
 * parsed value exactly as before.
 */
class HormonitorReader(
    private val storage: AimiStorage,
) {

    private companion object {
        const val DAILY_FILE = "AIMI_HORMONITOR_daily_outcomes_v1.jsonl"
        const val EVENT_FILE = "AIMI_HORMONITOR_event_stream_v1.jsonl"
        const val MAX_NARRATIVE_SAMPLES = 6
    }

    /**
     * `yyyy-MM-dd` in the device's own time zone, the key the exporter writes as `day_local`.
     *
     * Replaces `SimpleDateFormat("yyyy-MM-dd", Locale.US)` with the default time zone, which does
     * not exist outside the JVM. All fields are zero-padded digits, so the calendar pinning the
     * `Locale.US` provided is irrelevant; this formatter is calendar independent by construction.
     */
    private val dayKeyFormat = LocalDateTime.Format {
        year(); char('-'); monthNumber(); char('-'); day()
    }

    private fun dayKeyOf(epochMs: Long): String =
        Instant.fromEpochMilliseconds(epochMs)
            .toLocalDateTime(TimeZone.currentSystemDefault())
            .format(dayKeyFormat)

    private fun firstExisting(fileName: String): AimiPath? =
        runCatching { storage.file(fileName) }.getOrNull()
            ?.takeIf { storage.exists(it) && storage.sizeBytes(it) > 0L }

    /** True when at least one Hormonitor file is present. */
    suspend fun hasData(): Boolean = withContext(aapsIoDispatcher) {
        firstExisting(DAILY_FILE) != null || firstExisting(EVENT_FILE) != null
    }

    /**
     * Day list from the compact daily_outcomes file. The file appends a cumulative record every ~30 min, so
     * we keep the LATEST record per `day_local` (highest generated_at). Sorted most-recent-day first.
     * Never throws — any I/O or permission failure yields an empty list (the UI shows an empty state).
     */
    suspend fun readDays(): List<HormonitorDaySummary> = withContext(aapsIoDispatcher) {
        runCatching { readDaysInternal() }.getOrDefault(emptyList())
    }

    private fun readDaysInternal(): List<HormonitorDaySummary> {
        val path = firstExisting(DAILY_FILE) ?: return emptyList()
        val latestByDay = HashMap<String, Pair<String, HormonitorDaySummary>>() // day -> (generatedAt, summary)
        val walked = storage.forEachLine(path) { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
            val day = o.optStringCompat("day_local").takeIf { it.isNotBlank() } ?: return@forEachLine
            val generatedAt = o.optStringCompat("generated_at")
            val summary = HormonitorDaySummary(
                dayLocal = day,
                schemaVersion = o.optStringOrNull("schema_version"),
                tirLowPct = o.optDoubleOrNull("tir_low_pct"),
                tirInRangePct = o.optDoubleOrNull("tir_in_range_pct"),
                tirAbovePct = o.optDoubleOrNull("tir_above_pct"),
                tdd24hU = o.optDoubleOrNull("tdd_24h_total_u"),
                decisionTotal = o.optIntCompat("decision_count_total", 0),
                decisionSmb = o.optIntCompat("decision_count_smb", 0),
                decisionSuspend = o.optIntCompat("decision_count_suspend", 0),
                decisionTbrUp = o.optIntCompat("decision_count_tbr_up", 0),
                decisionTbrDown = o.optIntCompat("decision_count_tbr_down", 0),
                decisionNone = o.optIntCompat("decision_count_none", 0),
                decisionVeto = o.optIntCompat("decision_count_physio_veto", 0),
                sourceReliabilityScore = o.optDoubleOrNull("source_reliability_score"),
                sourceStale = run {
                    val stale = o["source_stale_flag"]
                    if (stale == null || stale is JsonNull) null else o.optBooleanCompat("source_stale_flag")
                },
                sourceOrigin = o.optStringOrNull("source_snapshot_origin"),
            )
            val prev = latestByDay[day]
            if (prev == null || generatedAt >= prev.first) latestByDay[day] = generatedAt to summary
        }
        if (!walked) return emptyList()
        return latestByDay.values.map { it.second }.sortedByDescending { it.dayLocal }
    }

    /**
     * Rich aggregation for one day from the event stream. Streams the file line by line (constant memory),
     * keeps only events whose local day matches [dayLocal], and folds them into a [HormonitorDayDetail].
     * Never throws — any I/O or permission failure yields null (the UI shows a no-events state).
     */
    suspend fun readDayDetail(dayLocal: String): HormonitorDayDetail? = withContext(aapsIoDispatcher) {
        runCatching { readDayDetailInternal(dayLocal) }.getOrNull()
    }

    private fun readDayDetailInternal(dayLocal: String): HormonitorDayDetail? {
        val path = firstExisting(EVENT_FILE) ?: return null
        val acc = DayAccumulator()
        val walked = storage.forEachLine(path) { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEachLine
            // Fast pre-filter on the raw text, mirroring the old byte-index's bucket rule: only lines
            // whose timestamp falls on the requested day are parsed at all.
            val tsHint = tsRegex.find(line)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: return@forEachLine
            if (tsHint <= 0L || dayKeyOf(tsHint) != dayLocal) return@forEachLine
            val o = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull()
            if (o == null) { acc.malformed++; return@forEachLine }
            val ts = o.optLongCompat("timestamp", 0L)
            if (ts <= 0L) return@forEachLine
            if (dayKeyOf(ts) != dayLocal) return@forEachLine
            acc.fold(o, ts)
        }
        if (!walked) return null
        return if (acc.eventCount == 0 && acc.malformed == 0) null else acc.build(dayLocal)
    }

    // --- JSON helpers (treat JSON null / "null" / blank as absent) ---
    private fun JsonObject.optStringOrNull(key: String): String? {
        val e = this[key]
        if (e == null || e is JsonNull) return null
        val s = if (e is JsonPrimitive) e.content else e.toString()
        return s.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
    }

    private fun JsonObject.optDoubleOrNull(key: String): Double? {
        val e = this[key]
        if (e == null || e is JsonNull) return null
        val d = (e as? JsonPrimitive)?.doubleOrNull ?: return null
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

        fun fold(o: JsonObject, ts: Long) {
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
            if (o.optBooleanCompat("predictive_hypo_suppressed")) {
                predictiveHypoSuppressed++
            }
            if (o.optBooleanCompat("physio_snapshot_valid_flag")) {
                physioSnapshotValid++
            }

            val story = o["patient_story"] as? JsonObject
            if (story != null) {
                patientStoryPresent++
                story.optStringOrNull("patient_mode")?.let { patientMode.bump(it) }
                story.optStringOrNull("patient_strategy_hint")?.let { strategyHint.bump(it) }
                story.optDoubleOrNull("patient_mode_confidence")?.let { modeConfidence.add(it) }
                story.optStringOrNull("patient_narrative")?.let {
                    if (narratives.size < MAX_NARRATIVE_SAMPLES) narratives.add(it)
                }
                val codes = story["patient_reason_codes"] as? JsonArray
                if (codes != null) {
                    for (i in 0 until codes.size) {
                        val element = codes[i] as? JsonPrimitive
                        val c = element?.let { if (it is JsonNull) "null" else it.content }
                            ?.takeIf { it.isNotBlank() } ?: continue
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

    private val tsRegex = Regex("\"timestamp\"\\s*:\\s*(\\d+)")
}

private fun MutableMap<String, Int>.bump(key: String) {
    this[key] = (this[key] ?: 0) + 1
}

private fun List<Double>.meanOrNull(): Double? = filter { it.isFinite() }.let { if (it.isEmpty()) null else it.average() }
