package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysioLiveDigest
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

data class HormonitorDecisionEventMTR(
    val eventId: String,
    val eventTimestamp: Long,
    val trigger: String,
    val profileIsfMgdl: Double,
    val profileBasalUph: Double,
    val currentBgMgdl: Double,
    val cobG: Double,
    val iobU: Double,
    val cyclePhase: String? = null,
    val cycleDay: Int? = null,
    val cycleTrackingMode: String? = null,
    val contraceptiveType: String? = null,
    val wcycleBasalMult: Double? = null,
    val wcycleSmbMult: Double? = null,
    val wcycleIsfMult: Double? = null,
    val thyroidStatus: String? = null,
    val inflammationStatus: String? = null,
    val hrNowBpm: Int? = null,
    val hrAvg15mBpm: Int? = null,
    val rhrRestingBpm: Int? = null,
    val hrvRmssdMs: Double? = null,
    val steps5m: Int? = null,
    val steps15m: Int? = null,
    val steps60m: Int? = null,
    val activityState: String? = null,
    val sleepDebtMinutes: Int? = null,
    val sleepEfficiency: Double? = null,
    val physioSnapshotTimestamp: Long? = null,
    val physioSnapshotValidFlag: Boolean? = null,
    val physioTrace: PhysioDecisionTraceMTR,
    val safetyPhase: String? = null,
    val predictiveHypoSuppressed: Boolean? = null,
    val safetyGate: String? = null,
    val safetyCompositeMinMgdl: Double? = null,
    val safetyUamTerminalMgdl: Double? = null,
    val decisionCompositeMinMgdl: Double? = null,
    val safetyReconcileDeltaMgdl: Double? = null,
    val patientMode: String? = null,
    val patientModeConfidence: Double? = null,
    val patientStrategyHint: String? = null,
    val patientNarrative: String? = null,
    val patientReasonCodes: List<String>? = null,
    val patientPhysioLive: PhysioLiveDigest? = null,
    val patientThermalBelief: ThermalBeliefDigest? = null,
) {
    fun toJSON(datasetId: String, generatedAtIsoUtc: String, appVersion: String, schemaVersion: String): JSONObject =
        JSONObject().apply {
            put("dataset_id", datasetId)
            put("generated_at", generatedAtIsoUtc)
            put("app_version", appVersion)
            put("schema_version", schemaVersion)
            put("event_id", eventId)
            put("timestamp", eventTimestamp)
            put("trigger", trigger)
            put("profile_isf_mgdl", profileIsfMgdl)
            put("profile_basal_uph", profileBasalUph)
            put("current_bg_mgdl", currentBgMgdl)
            put("cob_g", cobG)
            put("iob_u", iobU)
            put("cycle_phase", cyclePhase ?: JSONObject.NULL)
            put("cycle_day", cycleDay ?: JSONObject.NULL)
            put("cycle_tracking_mode", cycleTrackingMode ?: JSONObject.NULL)
            put("contraceptive_type", contraceptiveType ?: JSONObject.NULL)
            put("wcycle_basal_mult", wcycleBasalMult ?: JSONObject.NULL)
            put("wcycle_smb_mult", wcycleSmbMult ?: JSONObject.NULL)
            put("wcycle_isf_mult", wcycleIsfMult ?: JSONObject.NULL)
            put("thyroid_status", thyroidStatus ?: JSONObject.NULL)
            put("inflammation_status", inflammationStatus ?: JSONObject.NULL)
            put("hr_now_bpm", hrNowBpm ?: JSONObject.NULL)
            put("hr_avg_15m_bpm", hrAvg15mBpm ?: JSONObject.NULL)
            put("rhr_resting_bpm", rhrRestingBpm ?: JSONObject.NULL)
            put("hrv_rmssd_ms", hrvRmssdMs ?: JSONObject.NULL)
            put("steps_5m", steps5m ?: JSONObject.NULL)
            put("steps_15m", steps15m ?: JSONObject.NULL)
            put("steps_60m", steps60m ?: JSONObject.NULL)
            put("activity_state", activityState ?: JSONObject.NULL)
            put("sleep_debt_minutes", sleepDebtMinutes ?: JSONObject.NULL)
            put("sleep_efficiency", sleepEfficiency ?: JSONObject.NULL)
            put("physio_snapshot_timestamp", physioSnapshotTimestamp ?: JSONObject.NULL)
            put("physio_snapshot_valid_flag", physioSnapshotValidFlag ?: JSONObject.NULL)
            put("physio_state", physioTrace.physioState)
            put("physio_confidence", physioTrace.physioConfidence)
            put("physio_data_quality", physioTrace.physioDataQuality)
            put("sleep_quality_score", physioTrace.sleepQualityScore ?: JSONObject.NULL)
            put("isf_factor", physioTrace.isfFactor)
            put("basal_factor", physioTrace.basalFactor)
            put("smb_factor", physioTrace.smbFactor)
            put("reactivity_factor", physioTrace.reactivityFactor)
            put("physio_veto_reason", physioTrace.vetoReason ?: JSONObject.NULL)
            put("final_loop_decision_type", physioTrace.finalLoopDecisionType ?: JSONObject.NULL)
            put("smb_action_type", physioTrace.smbActionType ?: JSONObject.NULL)
            put("basal_action_type", physioTrace.basalActionType ?: JSONObject.NULL)
            put("decision_conflict_flags", org.json.JSONArray(physioTrace.decisionConflictFlags))
            put("source", physioTrace.source)
            put("safety_phase", safetyPhase ?: JSONObject.NULL)
            put("predictive_hypo_suppressed", predictiveHypoSuppressed ?: JSONObject.NULL)
            put("safety_gate", safetyGate ?: JSONObject.NULL)
            put("safety_composite_min_mgdl", safetyCompositeMinMgdl ?: JSONObject.NULL)
            put("safety_uam_terminal_mgdl", safetyUamTerminalMgdl ?: JSONObject.NULL)
            put("decision_composite_min_mgdl", decisionCompositeMinMgdl ?: JSONObject.NULL)
            put("safety_reconcile_delta_mgdl", safetyReconcileDeltaMgdl ?: JSONObject.NULL)
            put(
                "patient_story",
                JSONObject().apply {
                    put("patient_mode", patientMode ?: JSONObject.NULL)
                    put("patient_mode_confidence", patientModeConfidence ?: JSONObject.NULL)
                    put("patient_strategy_hint", patientStrategyHint ?: JSONObject.NULL)
                    put("patient_narrative", patientNarrative ?: JSONObject.NULL)
                    put(
                        "patient_reason_codes",
                        patientReasonCodes?.let { codes ->
                            org.json.JSONArray().apply { codes.forEach { put(it) } }
                        } ?: JSONObject.NULL,
                    )
                    put(
                        "physio_live",
                        patientPhysioLive?.toJsonObject() ?: JSONObject.NULL,
                    )
                    put(
                        "thermal_belief",
                        patientThermalBelief?.toJsonObject() ?: JSONObject.NULL,
                    )
                },
            )
        }
}

class AimiHormonitorStudyExporterMTR(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences
) {
    companion object {
        private const val SCHEMA_VERSION = "1.4.0"
        private const val FILE_NAME = "AIMI_HORMONITOR_event_stream_v1.jsonl"
        private const val DAILY_FILE_NAME = "AIMI_HORMONITOR_daily_outcomes_v1.jsonl"
        private const val QA_FILE_NAME = "AIMI_HORMONITOR_dataset_qa_v1.jsonl"
        private const val SHADOW_FILE_NAME = "AIMI_HORMONITOR_shadow_contributions_v1.jsonl"
        private const val BLACKBOX_FILE_NAME = "AIMI_HORMONITOR_loop_blackbox_v1.jsonl"
        private const val STATE_FILE_NAME = "AIMI_HORMONITOR_daily_state_v1.json"
        private const val TAG = "AimiHormonitorStudyExporterMTR"
        private const val DAILY_EMIT_INTERVAL_MS = 30L * 60L * 1000L
        private const val SNAPSHOT_STALE_THRESHOLD_SECONDS = 30L * 60L
        private const val QA_MIN_COMPLETENESS = 0.98
        private const val QA_MIN_TEMPORAL_COHERENCE = 0.99
        private const val QA_MAX_PENDING_DECISION_RATE = 0.01
        private const val QA_MAX_STALE_SNAPSHOT_RATE = 0.10
        private const val STATE_PERSIST_INTERVAL_MS = 30_000L
        private const val WRITE_QUEUE_CAPACITY = 512
        private const val WATCHDOG_INTERVAL_MS = 45_000L
        /** No loop pulse for this long → write stall warning (typical loop 5 min; avoid false positives). */
        private const val LOOP_STALL_THRESHOLD_MS = 600_000L
    }

    @Volatile
    private var sharedStorageDeniedLogged = false
    @Volatile
    private var lastDailyEmitMs: Long = 0L
    @Volatile
    private var lastQaEmitMs: Long = 0L
    @Volatile
    private var lastStatePersistMs: Long = 0L

    @Volatile
    private var lastLoopPulseWallMs: Long = 0L

    @Volatile
    private var lastStallWarningWallMs: Long = 0L

    /** Latest tick that started ([recordLoopPulse] with tick_id) but not yet completed with [recordLoopTickEnd]. */
    @Volatile
    private var pendingTickEndId: Long = 0L

    @Volatile
    private var pendingTickPulseWallMs: Long = 0L

    @Volatile
    private var lastReportedPhaseName: String = ""

    @Volatile
    private var lastIntratickStallWarningWallMs: Long = 0L

    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val droppedWrites = AtomicLong(0)
    private val writeQueue = Channel<WriteTask>(
        capacity = WRITE_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val sharedDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private val appScopedDir = File(context.getExternalFilesDir(null), "AAPS")
    private val dailyCounters = LinkedHashMap<String, DailyDecisionCounters>()
    private val qaCounters = LinkedHashMap<String, DailyQaCounters>()

    init {
        restoreDailyState()
        startWriter()
        startLoopWatchdog()
    }

    private fun isLoopBlackboxFileEnabled(): Boolean =
        preferences.get(BooleanKey.OApsAIMILoopBlackboxFileEnabled)

    private fun intratickStallThresholdMs(): Long {
        val sec = preferences.get(IntKey.OApsAIMIIntratickStallSeconds).coerceIn(60, 600)
        return sec * 1000L
    }

    private fun appendLoopBlackboxLine(line: String) {
        if (!isLoopBlackboxFileEnabled()) return
        val target = File(sharedDir, BLACKBOX_FILE_NAME)
        val fallback = File(appScopedDir, BLACKBOX_FILE_NAME)
        appendJsonlSafely(target, fallback, line)
    }

    /**
     * Called at the start of each AIMI determine_basal pass (wall clock).
     * Writes a JSONL pulse and feeds the stall watchdog (post-mortem blackbox).
     */
    fun recordLoopPulse(wallClockMs: Long, tickId: Long = 0L) {
        lastLoopPulseWallMs = wallClockMs
        if (tickId > 0L) {
            pendingTickEndId = tickId
            pendingTickPulseWallMs = wallClockMs
        }
        val line = JSONObject().apply {
            put("type", "loop_pulse")
            put("wall_ms", wallClockMs)
            if (tickId > 0L) put("tick_id", tickId)
            put("uptime_ms", SystemClock.uptimeMillis())
        }.toString()
        appendLoopBlackboxLine(line)
    }

    /** Phase transition inside the current tick (observe-only). */
    fun recordLoopPhase(
        tickId: Long,
        phaseName: String,
        wallClockMs: Long,
        msSinceTickStart: Long? = null,
        msSincePrevPhase: Long? = null
    ) {
        lastReportedPhaseName = phaseName
        val line = JSONObject().apply {
            put("type", "loop_phase")
            put("tick_id", tickId)
            put("phase", phaseName)
            put("wall_ms", wallClockMs)
            msSinceTickStart?.let { put("ms_since_tick_start", it) }
            msSincePrevPhase?.let { put("ms_since_prev_phase", it) }
            put("uptime_ms", SystemClock.uptimeMillis())
        }.toString()
        appendLoopBlackboxLine(line)
    }

    /**
     * Uncaught exception escaped the tick body; clears pending tick watchdog state.
     * Study pipelines should ignore unknown `type` or filter on this type for QA.
     */
    fun recordLoopTickAborted(
        tickId: Long,
        startedWallMs: Long,
        endedWallMs: Long,
        errorClass: String,
        errorMessage: String,
        lastPhaseName: String?
    ) {
        if (tickId > 0L && tickId == pendingTickEndId) {
            pendingTickEndId = 0L
            pendingTickPulseWallMs = 0L
        }
        val safeMessage = errorMessage.replace("\n", " ").take(500)
        aapsLogger.warn(
            LTag.APS,
            "[$TAG] loop_tick_aborted tickId=$tickId phase=${lastPhaseName ?: "?"} $errorClass: $safeMessage"
        )
        val line = JSONObject().apply {
            put("type", "loop_tick_aborted")
            put("tick_id", tickId)
            put("started_wall_ms", startedWallMs)
            put("ended_wall_ms", endedWallMs)
            put("duration_ms", (endedWallMs - startedWallMs).coerceAtLeast(0L))
            put("error_class", errorClass)
            put("error_message", safeMessage)
            if (!lastPhaseName.isNullOrEmpty()) put("last_phase", lastPhaseName)
            put("uptime_ms", SystemClock.uptimeMillis())
        }.toString()
        appendLoopBlackboxLine(line)
    }

    /** Emitted when a determine_basal pass completes; pairs with [recordLoopPulse]. */
    fun recordLoopTickEnd(
        tickId: Long,
        startedWallMs: Long,
        endedWallMs: Long,
        lastPhaseName: String? = null
    ) {
        if (tickId > 0L && tickId == pendingTickEndId) {
            pendingTickEndId = 0L
            pendingTickPulseWallMs = 0L
        }
        val line = JSONObject().apply {
            put("type", "loop_tick_end")
            put("tick_id", tickId)
            put("started_wall_ms", startedWallMs)
            put("ended_wall_ms", endedWallMs)
            put("duration_ms", (endedWallMs - startedWallMs).coerceAtLeast(0L))
            if (!lastPhaseName.isNullOrEmpty()) put("last_phase", lastPhaseName)
            put("uptime_ms", SystemClock.uptimeMillis())
        }.toString()
        appendLoopBlackboxLine(line)
    }

    fun export(event: HormonitorDecisionEventMTR) {
        val generatedAt = isoUtcNow()
        val payload = event
            .toJSON(
                datasetId = stableDatasetId(),
                generatedAtIsoUtc = generatedAt,
                appVersion = appVersion(),
                schemaVersion = SCHEMA_VERSION
            )
            .toString()

        val target = File(sharedDir, FILE_NAME)
        val fallback = File(appScopedDir, FILE_NAME)
        appendJsonlSafely(target, fallback, payload)
    }

    fun exportShadowContributions(event: HormonitorDecisionEventMTR) {
        val trace = event.physioTrace
        val generatedAt = isoUtcNow()

        val payload = JSONObject().apply {
            put("dataset_id", stableDatasetId())
            put("generated_at", generatedAt)
            put("app_version", appVersion())
            put("schema_version", SCHEMA_VERSION)
            put("event_id", event.eventId)
            put("timestamp", event.eventTimestamp)
            put("trigger", event.trigger)
            put("shadow_orchestrator_enabled", trace.shadowOrchestratorEnabled)
            put("shadow_budgeted_isf_factor", trace.shadowBudgetedIsfFactor)
            put("shadow_budgeted_basal_factor", trace.shadowBudgetedBasalFactor)
            put("shadow_budgeted_smb_factor", trace.shadowBudgetedSmbFactor)
            put("shadow_overlap_penalty", trace.shadowOverlapPenalty)
            put("shadow_contributions", JSONObject(trace.shadowContributions))
            put("shadow_notes", org.json.JSONArray(trace.shadowNotes))
            put("inflammation_latent_index", trace.inflammationLatentIndex)
            put("inflammation_confidence", trace.inflammationConfidence)
            put("inflammation_timescale", trace.inflammationTimescale)
            put("inflammation_drivers", org.json.JSONArray(trace.inflammationDrivers))
            put("final_loop_decision_type", trace.finalLoopDecisionType ?: JSONObject.NULL)
            put("smb_action_type", trace.smbActionType ?: JSONObject.NULL)
            put("basal_action_type", trace.basalActionType ?: JSONObject.NULL)
            put("decision_conflict_flags", org.json.JSONArray(trace.decisionConflictFlags))
            put("source", trace.source)
        }.toString()

        val target = File(sharedDir, SHADOW_FILE_NAME)
        val fallback = File(appScopedDir, SHADOW_FILE_NAME)
        appendJsonlSafely(target, fallback, payload)
    }

    @Synchronized
    fun exportDailyOutcomes(
        event: HormonitorDecisionEventMTR,
        tirLowPct: Double?,
        tirInRangePct: Double?,
        tirAbovePct: Double?,
        tdd24hTotalU: Double?,
        snapshotSource: String?,
        snapshotAgeSeconds: Long?,
        snapshotConfidence: Double?
    ) {
        val dayKey = localDayKey(event.eventTimestamp)
        val counters = dailyCounters.getOrPut(dayKey) { DailyDecisionCounters() }
        val qa = qaCounters.getOrPut(dayKey) { DailyQaCounters() }
        counters.totalLoops += 1
        qa.totalEvents += 1
        updateQaCounters(
            qa = qa,
            event = event,
            tirLowPct = tirLowPct,
            tirInRangePct = tirInRangePct,
            tirAbovePct = tirAbovePct,
            tdd24hTotalU = tdd24hTotalU
        )
        val smbAction = event.physioTrace.smbActionType
            ?: if (event.physioTrace.finalLoopDecisionType == "smb") "smb" else null
        val basalAction = event.physioTrace.basalActionType
            ?: when (event.physioTrace.finalLoopDecisionType) {
                "suspend", "tbr_up", "tbr_down", "none" -> event.physioTrace.finalLoopDecisionType
                else -> null
            }
        if (smbAction == "smb") {
            counters.smbCount += 1
        }
        when (basalAction) {
            "suspend" -> counters.suspendCount += 1
            "tbr_up" -> counters.tbrUpCount += 1
            "tbr_down" -> counters.tbrDownCount += 1
            else -> if (smbAction != "smb") counters.noneCount += 1
        }
        if (!event.physioTrace.vetoReason.isNullOrBlank()) {
            counters.vetoCount += 1
        }
        persistDailyState()

        val now = System.currentTimeMillis()
        if (now - lastDailyEmitMs < DAILY_EMIT_INTERVAL_MS) return
        lastDailyEmitMs = now

        val generatedAt = isoUtcNow()
        val payload = JSONObject().apply {
            put("dataset_id", stableDatasetId())
            put("generated_at", generatedAt)
            put("app_version", appVersion())
            put("schema_version", SCHEMA_VERSION)
            put("day_local", dayKey)
            put("tdd_24h_total_u", tdd24hTotalU ?: JSONObject.NULL)
            put("tir_low_pct", tirLowPct ?: JSONObject.NULL)
            put("tir_in_range_pct", tirInRangePct ?: JSONObject.NULL)
            put("tir_above_pct", tirAbovePct ?: JSONObject.NULL)
            put("hypo_proxy_pct", tirLowPct ?: JSONObject.NULL)
            put("source_reliability_score", computeSourceReliabilityScore(snapshotAgeSeconds, snapshotConfidence))
            put("source_sync_age_seconds", snapshotAgeSeconds ?: JSONObject.NULL)
            put("source_snapshot_confidence", snapshotConfidence ?: JSONObject.NULL)
            put("source_snapshot_origin", snapshotSource ?: JSONObject.NULL)
            put(
                "source_stale_flag",
                snapshotAgeSeconds?.let { it > SNAPSHOT_STALE_THRESHOLD_SECONDS } ?: JSONObject.NULL
            )
            put("decision_count_total", counters.totalLoops)
            put("decision_count_smb", counters.smbCount)
            put("decision_count_suspend", counters.suspendCount)
            put("decision_count_tbr_up", counters.tbrUpCount)
            put("decision_count_tbr_down", counters.tbrDownCount)
            put("decision_count_none", counters.noneCount)
            put("decision_count_physio_veto", counters.vetoCount)
        }.toString()

        val target = File(sharedDir, DAILY_FILE_NAME)
        val fallback = File(appScopedDir, DAILY_FILE_NAME)
        appendJsonlSafely(target, fallback, payload)
        maybeEmitQaReport(dayKey, generatedAt, qa, snapshotAgeSeconds)
    }

    private fun appendJsonlSafely(primary: File, fallback: File, line: String) {
        enqueueWrite(
            WriteTask(
                primary = primary,
                fallback = fallback,
                line = line,
                mode = WriteMode.APPEND_LINE
            )
        )
    }

    private fun appendLine(file: File, line: String) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        file.appendText("$line\n")
    }

    private fun appVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            packageInfo.versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private fun stableDatasetId(): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        } catch (_: Exception) {
            ""
        }
        val raw = "${context.packageName}|$androidId|hormonitor_v1"
        return sha256Hex(raw).take(24)
    }

    private fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString(separator = "") { eachByte -> "%02x".format(eachByte) }
    }

    private fun isoUtcNow(): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date())
    }

    private fun localDayKey(timestamp: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return formatter.format(Date(timestamp))
    }

    private fun computeSourceReliabilityScore(snapshotAgeSeconds: Long?, snapshotConfidence: Double?): Double {
        val confidence = (snapshotConfidence ?: 0.0).coerceIn(0.0, 1.0)
        val agePenalty = when {
            snapshotAgeSeconds == null -> 1.0
            snapshotAgeSeconds <= 600L -> 0.0
            snapshotAgeSeconds >= SNAPSHOT_STALE_THRESHOLD_SECONDS -> 0.5
            else -> ((snapshotAgeSeconds - 600L).toDouble() / (SNAPSHOT_STALE_THRESHOLD_SECONDS - 600L)) * 0.5
        }
        return (confidence - agePenalty).coerceIn(0.0, 1.0)
    }

    private fun persistDailyState() {
        val now = System.currentTimeMillis()
        if (now - lastStatePersistMs < STATE_PERSIST_INTERVAL_MS) return
        lastStatePersistMs = now

        val stateFile = File(appScopedDir, STATE_FILE_NAME)
        try {
            val countersJson = JSONObject()
            dailyCounters.forEach { (day, counters) ->
                countersJson.put(day, JSONObject().apply {
                    put("totalLoops", counters.totalLoops)
                    put("smbCount", counters.smbCount)
                    put("suspendCount", counters.suspendCount)
                    put("tbrUpCount", counters.tbrUpCount)
                    put("tbrDownCount", counters.tbrDownCount)
                    put("noneCount", counters.noneCount)
                    put("vetoCount", counters.vetoCount)
                })
            }
            val root = JSONObject().apply {
                put("schema_version", SCHEMA_VERSION)
                put("last_daily_emit_ms", lastDailyEmitMs)
                put("last_qa_emit_ms", lastQaEmitMs)
                put("daily_counters", countersJson)
                put("daily_qa_counters", JSONObject().apply {
                    qaCounters.forEach { (day, counters) ->
                        put(day, JSONObject().apply {
                            put("totalEvents", counters.totalEvents)
                            put("criticalFieldMissingCount", counters.criticalFieldMissingCount)
                            put("invalidTimestampCount", counters.invalidTimestampCount)
                            put("decisionPendingCount", counters.decisionPendingCount)
                            put("staleSnapshotCount", counters.staleSnapshotCount)
                        })
                    }
                })
            }
            enqueueWrite(
                WriteTask(
                    primary = stateFile,
                    fallback = stateFile,
                    line = root.toString(),
                    mode = WriteMode.OVERWRITE
                )
            )
        } catch (_: Exception) {
            // Never break loop/export path on state persistence failures.
        }
    }

    private fun startWriter() {
        writeScope.launch {
            for (task in writeQueue) {
                tryWrite(task)
            }
        }
    }

    private fun startLoopWatchdog() {
        writeScope.launch {
            while (isActive) {
                delay(WATCHDOG_INTERVAL_MS)
                val now = System.currentTimeMillis()
                val pendingId = pendingTickEndId
                val pendingPulseAt = pendingTickPulseWallMs
                if (pendingId > 0L && pendingPulseAt > 0L) {
                    val intraThreshold = intratickStallThresholdMs()
                    val intratickGap = now - pendingPulseAt
                    if (intratickGap >= intraThreshold &&
                        now - lastIntratickStallWarningWallMs >= intraThreshold
                    ) {
                        lastIntratickStallWarningWallMs = now
                        val line = JSONObject().apply {
                            put("type", "watchdog_intratick_stall")
                            put("detected_wall_ms", now)
                            put("tick_id", pendingId)
                            put("pulse_wall_ms", pendingPulseAt)
                            put("gap_ms", intratickGap)
                            put("threshold_ms", intraThreshold)
                            put("last_phase", lastReportedPhaseName)
                            put("uptime_ms", SystemClock.uptimeMillis())
                        }.toString()
                        appendLoopBlackboxLine(line)
                        aapsLogger.warn(
                            LTag.APS,
                            "[$TAG] Blackbox: intra-tick stall tickId=$pendingId gap=${intratickGap}ms " +
                                "(threshold=${intraThreshold}ms phase=$lastReportedPhaseName). See $BLACKBOX_FILE_NAME"
                        )
                        continue
                    }
                }
                val last = lastLoopPulseWallMs
                if (last <= 0L) continue
                val gap = now - last
                if (gap < LOOP_STALL_THRESHOLD_MS) continue
                if (now - lastStallWarningWallMs < LOOP_STALL_THRESHOLD_MS) continue
                lastStallWarningWallMs = now
                val line = JSONObject().apply {
                    put("type", "watchdog_loop_stall")
                    put("detected_wall_ms", now)
                    put("last_loop_pulse_wall_ms", last)
                    put("gap_ms", gap)
                    put("threshold_ms", LOOP_STALL_THRESHOLD_MS)
                    put("uptime_ms", SystemClock.uptimeMillis())
                }.toString()
                appendLoopBlackboxLine(line)
                aapsLogger.warn(
                    LTag.APS,
                    "[$TAG] Blackbox: no loop pulse for ${gap}ms (threshold=${LOOP_STALL_THRESHOLD_MS}ms). See $BLACKBOX_FILE_NAME"
                )
            }
        }
    }

    private fun enqueueWrite(task: WriteTask) {
        val accepted = writeQueue.trySend(task).isSuccess
        if (!accepted) {
            val dropped = droppedWrites.incrementAndGet()
            if (dropped == 1L || dropped % 100L == 0L) {
                aapsLogger.warn(
                    LTag.APS,
                    "[$TAG] Export queue saturated. Dropped writes=$dropped"
                )
            }
        }
    }

    private fun tryWrite(task: WriteTask) {
        try {
            writeToFile(task.primary, task.line, task.mode)
        } catch (primaryError: Exception) {
            if (!sharedStorageDeniedLogged && task.primary != task.fallback) {
                sharedStorageDeniedLogged = true
                aapsLogger.warn(
                    LTag.APS,
                    "[$TAG] Study export denied on shared storage (${task.primary.absolutePath}). " +
                        "Switching to app-scoped fallback (${task.fallback.absolutePath}). reason=${primaryError.message}"
                )
            }
            try {
                writeToFile(task.fallback, task.line, task.mode)
            } catch (fallbackError: Exception) {
                aapsLogger.error(
                    LTag.APS,
                    "[$TAG] Study export failed on both primary and fallback paths.",
                    fallbackError
                )
            }
        }
    }

    private fun writeToFile(file: File, payload: String, mode: WriteMode) {
        if (!file.exists()) {
            file.parentFile?.mkdirs()
            file.createNewFile()
        }
        when (mode) {
            WriteMode.APPEND_LINE -> file.appendText("$payload\n")
            WriteMode.OVERWRITE -> file.writeText(payload)
        }
    }

    private data class WriteTask(
        val primary: File,
        val fallback: File,
        val line: String,
        val mode: WriteMode,
    )

    private enum class WriteMode {
        APPEND_LINE,
        OVERWRITE,
    }

    private fun restoreDailyState() {
        val stateFile = File(appScopedDir, STATE_FILE_NAME)
        if (!stateFile.exists()) return
        try {
            val root = JSONObject(stateFile.readText())
            lastDailyEmitMs = root.optLong("last_daily_emit_ms", 0L)
            lastQaEmitMs = root.optLong("last_qa_emit_ms", 0L)
            val counters = root.optJSONObject("daily_counters") ?: return
            counters.keys().forEach { day ->
                val item = counters.optJSONObject(day) ?: return@forEach
                dailyCounters[day] = DailyDecisionCounters(
                    totalLoops = item.optInt("totalLoops", 0),
                    smbCount = item.optInt("smbCount", 0),
                    suspendCount = item.optInt("suspendCount", 0),
                    tbrUpCount = item.optInt("tbrUpCount", 0),
                    tbrDownCount = item.optInt("tbrDownCount", 0),
                    noneCount = item.optInt("noneCount", 0),
                    vetoCount = item.optInt("vetoCount", 0)
                )
            }
            val qa = root.optJSONObject("daily_qa_counters")
            qa?.keys()?.forEach { day ->
                val item = qa.optJSONObject(day) ?: return@forEach
                qaCounters[day] = DailyQaCounters(
                    totalEvents = item.optInt("totalEvents", 0),
                    criticalFieldMissingCount = item.optInt("criticalFieldMissingCount", 0),
                    invalidTimestampCount = item.optInt("invalidTimestampCount", 0),
                    decisionPendingCount = item.optInt("decisionPendingCount", 0),
                    staleSnapshotCount = item.optInt("staleSnapshotCount", 0)
                )
            }
        } catch (_: Exception) {
            // If state is corrupted, keep runtime defaults.
        }
    }

    private fun updateQaCounters(
        qa: DailyQaCounters,
        event: HormonitorDecisionEventMTR,
        tirLowPct: Double?,
        tirInRangePct: Double?,
        tirAbovePct: Double?,
        tdd24hTotalU: Double?
    ) {
        val hasDecisionType =
            !event.physioTrace.finalLoopDecisionType.isNullOrBlank() ||
                !event.physioTrace.smbActionType.isNullOrBlank() ||
                !event.physioTrace.basalActionType.isNullOrBlank()
        if (
            event.eventId.isBlank() ||
            event.trigger.isBlank() ||
            event.eventTimestamp <= 0L ||
            !hasDecisionType
        ) {
            qa.criticalFieldMissingCount += 1
        }
        if (event.eventTimestamp <= 0L || event.eventTimestamp > System.currentTimeMillis() + 5 * 60_000L) {
            qa.invalidTimestampCount += 1
        }
        if (
            event.physioTrace.finalLoopDecisionType == "pending" ||
            event.physioTrace.smbActionType == "pending" ||
            event.physioTrace.basalActionType == "pending"
        ) {
            qa.decisionPendingCount += 1
        }
        val metricsMissing = listOf(tirLowPct, tirInRangePct, tirAbovePct, tdd24hTotalU).count { it == null }
        if (metricsMissing >= 2) {
            qa.criticalFieldMissingCount += 1
        }
    }

    private fun maybeEmitQaReport(dayKey: String, generatedAt: String, qa: DailyQaCounters, snapshotAgeSeconds: Long?) {
        if (snapshotAgeSeconds != null && snapshotAgeSeconds > SNAPSHOT_STALE_THRESHOLD_SECONDS) {
            qa.staleSnapshotCount += 1
        }
        val now = System.currentTimeMillis()
        if (now - lastQaEmitMs < DAILY_EMIT_INTERVAL_MS) return
        lastQaEmitMs = now

        val completeness = if (qa.totalEvents == 0) 1.0
        else ((qa.totalEvents - qa.criticalFieldMissingCount).toDouble() / qa.totalEvents.toDouble()).coerceIn(0.0, 1.0)
        val temporalCoherence = if (qa.totalEvents == 0) 1.0
        else ((qa.totalEvents - qa.invalidTimestampCount).toDouble() / qa.totalEvents.toDouble()).coerceIn(0.0, 1.0)
        val pendingDecisionRate = if (qa.totalEvents == 0) 0.0 else qa.decisionPendingCount.toDouble() / qa.totalEvents.toDouble()

        val payload = JSONObject().apply {
            put("dataset_id", stableDatasetId())
            put("generated_at", generatedAt)
            put("app_version", appVersion())
            put("schema_version", SCHEMA_VERSION)
            put("day_local", dayKey)
            put("qa_total_events", qa.totalEvents)
            put("qa_completeness_score", completeness)
            put("qa_temporal_coherence_score", temporalCoherence)
            put("qa_pending_decision_rate", pendingDecisionRate)
            put("qa_stale_snapshot_rate", if (qa.totalEvents == 0) 0.0 else qa.staleSnapshotCount.toDouble() / qa.totalEvents.toDouble())
            put("qa_critical_field_missing_count", qa.criticalFieldMissingCount)
            put("qa_invalid_timestamp_count", qa.invalidTimestampCount)
        }.toString()

        val target = File(sharedDir, QA_FILE_NAME)
        val fallback = File(appScopedDir, QA_FILE_NAME)
        appendJsonlSafely(target, fallback, payload)
        logQaStatusLine(
            dayKey = dayKey,
            completeness = completeness,
            temporalCoherence = temporalCoherence,
            pendingDecisionRate = pendingDecisionRate,
            staleSnapshotRate = if (qa.totalEvents == 0) 0.0 else qa.staleSnapshotCount.toDouble() / qa.totalEvents.toDouble()
        )
        persistDailyState()
    }

    private fun logQaStatusLine(
        dayKey: String,
        completeness: Double,
        temporalCoherence: Double,
        pendingDecisionRate: Double,
        staleSnapshotRate: Double
    ) {
        val pass = completeness >= QA_MIN_COMPLETENESS &&
            temporalCoherence >= QA_MIN_TEMPORAL_COHERENCE &&
            pendingDecisionRate <= QA_MAX_PENDING_DECISION_RATE &&
            staleSnapshotRate <= QA_MAX_STALE_SNAPSHOT_RATE
        val levelTag = if (pass) "PASS" else "WARN"
        val message =
            "HORMONITOR_QA_STATUS[$levelTag] day=$dayKey " +
                "completeness=${formatPct(completeness)} " +
                "temporal=${formatPct(temporalCoherence)} " +
                "pending=${formatPct(pendingDecisionRate)} " +
                "stale=${formatPct(staleSnapshotRate)}"
        if (pass) {
            aapsLogger.info(LTag.APS, message)
        } else {
            aapsLogger.warn(LTag.APS, message)
        }
    }

    private fun formatPct(value: Double): String = "%.2f%%".format(value * 100.0)

    private data class DailyDecisionCounters(
        var totalLoops: Int = 0,
        var smbCount: Int = 0,
        var suspendCount: Int = 0,
        var tbrUpCount: Int = 0,
        var tbrDownCount: Int = 0,
        var noneCount: Int = 0,
        var vetoCount: Int = 0
    )

    private data class DailyQaCounters(
        var totalEvents: Int = 0,
        var criticalFieldMissingCount: Int = 0,
        var invalidTimestampCount: Int = 0,
        var decisionPendingCount: Int = 0,
        var staleSnapshotCount: Int = 0
    )
}
