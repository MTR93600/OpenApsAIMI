package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.data.json.OrgJsonCompat.optIntCompat
import app.aaps.core.data.json.OrgJsonCompat.optJsonObjectCompat
import app.aaps.core.data.json.OrgJsonCompat.optLongCompat
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.retention.AimiAppendGuard
import app.aaps.plugins.aps.openAPSAIMI.retention.AimiAppendOutcome
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlin.concurrent.Volatile
import kotlin.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Writes the Hormonitor study files: the decision event stream, the shadow contributions, the
 * daily outcomes and QA report, and the loop black box.
 *
 * Shared by Android and iOS (moved from androidMain on 2026-11-16, so an iPhone writes the same
 * files as an Android phone; before, iOS had no exporter at all). Only [HormonitorStudyPlatform] is
 * per platform: the clock since boot, the app version, the dataset id seed, SHA-256 and the two
 * folders. The lines, file names and folders on Android are the same as before the move.
 *
 * Telemetry only: nothing here feeds a dose. A write that fails on the primary place goes to the
 * fallback place, and a write that fails there too is logged and dropped, never thrown.
 */
class AimiHormonitorStudyExporterMTR(
    private val platform: HormonitorStudyPlatform,
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    /** All file reads and writes, and the append guard. */
    private val storage: AimiStorage,
) : HormonitorStudyExporter {
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

    private val writeScope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)
    /** Guards [droppedWrites], [appendGuardLoggedPaths] and the day counters (was `@Synchronized`). */
    private val lock = AapsLock()
    private var droppedWrites = 0L

    /** Paths already warned about for [AimiAppendOutcome.ROTATED] / [AimiAppendOutcome.OVERFLOW_ALREADY_PRESENT],
     *  so a stuck overflow file cannot flood the log: at most one warning per file per process. */
    private val appendGuardLoggedPaths: MutableSet<String> = mutableSetOf()
    private val writeQueue = Channel<WriteTask>(
        capacity = WRITE_QUEUE_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private val isoUtcFormat = LocalDateTime.Format {
        year(); char('-'); monthNumber(); char('-'); day(); char('T')
        hour(); char(':'); minute(); char(':'); second(); char('.'); secondFraction(3)
    }
    private val dayKeyFormat = LocalDateTime.Format { year(); char('-'); monthNumber(); char('-'); day() }

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
        val target = platform.primaryFile(BLACKBOX_FILE_NAME)
        val fallback = platform.fallbackFile(BLACKBOX_FILE_NAME)
        appendJsonlSafely(target, fallback, line)
    }

    /**
     * Called at the start of each AIMI determine_basal pass (wall clock).
     * Writes a JSONL pulse and feeds the stall watchdog (post-mortem blackbox).
     */
    override fun recordLoopPulse(wallClockMs: Long, tickId: Long) {
        lastLoopPulseWallMs = wallClockMs
        if (tickId > 0L) {
            pendingTickEndId = tickId
            pendingTickPulseWallMs = wallClockMs
        }
        val line = buildJsonObject {
            put("type", "loop_pulse")
            put("wall_ms", wallClockMs)
            if (tickId > 0L) put("tick_id", tickId)
            put("uptime_ms", platform.uptimeMs())
        }.toString()
        appendLoopBlackboxLine(line)
    }

    /** Phase transition inside the current tick (observe-only). */
    override fun recordLoopPhase(
        tickId: Long,
        phaseName: String,
        wallClockMs: Long,
        msSinceTickStart: Long?,
        msSincePrevPhase: Long?
    ) {
        lastReportedPhaseName = phaseName
        val line = buildJsonObject {
            put("type", "loop_phase")
            put("tick_id", tickId)
            put("phase", phaseName)
            put("wall_ms", wallClockMs)
            msSinceTickStart?.let { put("ms_since_tick_start", it) }
            msSincePrevPhase?.let { put("ms_since_prev_phase", it) }
            put("uptime_ms", platform.uptimeMs())
        }.toString()
        appendLoopBlackboxLine(line)
    }

    /**
     * Uncaught exception escaped the tick body; clears pending tick watchdog state.
     * Study pipelines should ignore unknown `type` or filter on this type for QA.
     */
    override fun recordLoopTickAborted(
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
            LTag.AIMI,
            "[$TAG] loop_tick_aborted tickId=$tickId phase=${lastPhaseName ?: "?"} $errorClass: $safeMessage"
        )
        val line = buildJsonObject {
            put("type", "loop_tick_aborted")
            put("tick_id", tickId)
            put("started_wall_ms", startedWallMs)
            put("ended_wall_ms", endedWallMs)
            put("duration_ms", (endedWallMs - startedWallMs).coerceAtLeast(0L))
            put("error_class", errorClass)
            put("error_message", safeMessage)
            if (!lastPhaseName.isNullOrEmpty()) put("last_phase", lastPhaseName)
            put("uptime_ms", platform.uptimeMs())
        }.toString()
        appendLoopBlackboxLine(line)
    }

    /** Emitted when a determine_basal pass completes; pairs with [recordLoopPulse]. */
    override fun recordLoopTickEnd(
        tickId: Long,
        startedWallMs: Long,
        endedWallMs: Long,
        lastPhaseName: String?
    ) {
        if (tickId > 0L && tickId == pendingTickEndId) {
            pendingTickEndId = 0L
            pendingTickPulseWallMs = 0L
        }
        val line = buildJsonObject {
            put("type", "loop_tick_end")
            put("tick_id", tickId)
            put("started_wall_ms", startedWallMs)
            put("ended_wall_ms", endedWallMs)
            put("duration_ms", (endedWallMs - startedWallMs).coerceAtLeast(0L))
            if (!lastPhaseName.isNullOrEmpty()) put("last_phase", lastPhaseName)
            put("uptime_ms", platform.uptimeMs())
        }.toString()
        appendLoopBlackboxLine(line)
    }

    override fun export(event: HormonitorDecisionEventMTR) {
        val generatedAt = isoUtcNow()
        val payload = event
            .toJSON(
                datasetId = stableDatasetId(),
                generatedAtIsoUtc = generatedAt,
                appVersion = appVersion(),
                schemaVersion = SCHEMA_VERSION
            )
            .toString()

        val target = platform.primaryFile(FILE_NAME)
        val fallback = platform.fallbackFile(FILE_NAME)
        appendJsonlSafely(target, fallback, payload)
    }

    override fun exportShadowContributions(event: HormonitorDecisionEventMTR) {
        val trace = event.physioTrace
        val generatedAt = isoUtcNow()

        val payload = buildJsonObject {
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
            put("shadow_contributions", mapToJsonObject(trace.shadowContributions))
            put("shadow_notes", stringJsonArray(trace.shadowNotes))
            put("inflammation_latent_index", trace.inflammationLatentIndex)
            put("inflammation_confidence", trace.inflammationConfidence)
            put("inflammation_timescale", trace.inflammationTimescale)
            put("inflammation_drivers", stringJsonArray(trace.inflammationDrivers))
            putOrNull("final_loop_decision_type", trace.finalLoopDecisionType)
            putOrNull("smb_action_type", trace.smbActionType)
            putOrNull("basal_action_type", trace.basalActionType)
            put("decision_conflict_flags", stringJsonArray(trace.decisionConflictFlags))
            put("source", trace.source)
        }.toString()

        val target = platform.primaryFile(SHADOW_FILE_NAME)
        val fallback = platform.fallbackFile(SHADOW_FILE_NAME)
        appendJsonlSafely(target, fallback, payload)
    }

    override fun exportDailyOutcomes(
        event: HormonitorDecisionEventMTR,
        tirLowPct: Double?,
        tirInRangePct: Double?,
        tirAbovePct: Double?,
        tdd24hTotalU: Double?,
        snapshotSource: String?,
        snapshotAgeSeconds: Long?,
        snapshotConfidence: Double?
    ) = lock.withLock {
        exportDailyOutcomesLocked(event, tirLowPct, tirInRangePct, tirAbovePct, tdd24hTotalU, snapshotSource, snapshotAgeSeconds, snapshotConfidence)
    }

    private fun exportDailyOutcomesLocked(
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

        val now = aimiWallClockMs()
        if (now - lastDailyEmitMs < DAILY_EMIT_INTERVAL_MS) return
        lastDailyEmitMs = now

        val generatedAt = isoUtcNow()
        val payload = buildJsonObject {
            put("dataset_id", stableDatasetId())
            put("generated_at", generatedAt)
            put("app_version", appVersion())
            put("schema_version", SCHEMA_VERSION)
            put("day_local", dayKey)
            putOrNull("tdd_24h_total_u", tdd24hTotalU)
            putOrNull("tir_low_pct", tirLowPct)
            putOrNull("tir_in_range_pct", tirInRangePct)
            putOrNull("tir_above_pct", tirAbovePct)
            putOrNull("hypo_proxy_pct", tirLowPct)
            put("source_reliability_score", computeSourceReliabilityScore(snapshotAgeSeconds, snapshotConfidence))
            putOrNull("source_sync_age_seconds", snapshotAgeSeconds)
            putOrNull("source_snapshot_confidence", snapshotConfidence)
            putOrNull("source_snapshot_origin", snapshotSource)
            putOrNull(
                "source_stale_flag",
                snapshotAgeSeconds?.let { it > SNAPSHOT_STALE_THRESHOLD_SECONDS }
            )
            put("decision_count_total", counters.totalLoops)
            put("decision_count_smb", counters.smbCount)
            put("decision_count_suspend", counters.suspendCount)
            put("decision_count_tbr_up", counters.tbrUpCount)
            put("decision_count_tbr_down", counters.tbrDownCount)
            put("decision_count_none", counters.noneCount)
            put("decision_count_physio_veto", counters.vetoCount)
        }.toString()

        val target = platform.primaryFile(DAILY_FILE_NAME)
        val fallback = platform.fallbackFile(DAILY_FILE_NAME)
        appendJsonlSafely(target, fallback, payload)
        maybeEmitQaReport(dayKey, generatedAt, qa, snapshotAgeSeconds)
    }

    private fun appendJsonlSafely(primary: AimiPath, fallback: AimiPath, line: String) {
        enqueueWrite(
            WriteTask(
                primary = primary,
                fallback = fallback,
                line = line,
                mode = WriteMode.APPEND_LINE
            )
        )
    }

    private fun appVersion(): String = platform.appVersion()

    private fun stableDatasetId(): String =
        platform.sha256Hex("${platform.datasetIdSeed()}|hormonitor_v1").take(24)

    /** `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` in UTC, as `SimpleDateFormat` wrote it before the move. */
    private fun isoUtcNow(): String =
        Instant.fromEpochMilliseconds(aimiWallClockMs()).toLocalDateTime(TimeZone.UTC).format(isoUtcFormat) + "Z"

    /** `yyyy-MM-dd` in the device time zone, the `day_local` key the viewer reads. */
    private fun localDayKey(timestamp: Long): String =
        Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault()).format(dayKeyFormat)

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
        val now = aimiWallClockMs()
        if (now - lastStatePersistMs < STATE_PERSIST_INTERVAL_MS) return
        lastStatePersistMs = now

        val stateFile = platform.fallbackFile(STATE_FILE_NAME)
        try {
            val countersJson = buildJsonObject {
                dailyCounters.forEach { (day, counters) ->
                    put(day, buildJsonObject {
                        put("totalLoops", counters.totalLoops)
                        put("smbCount", counters.smbCount)
                        put("suspendCount", counters.suspendCount)
                        put("tbrUpCount", counters.tbrUpCount)
                        put("tbrDownCount", counters.tbrDownCount)
                        put("noneCount", counters.noneCount)
                        put("vetoCount", counters.vetoCount)
                    })
                }
            }
            val root = buildJsonObject {
                put("schema_version", SCHEMA_VERSION)
                put("last_daily_emit_ms", lastDailyEmitMs)
                put("last_qa_emit_ms", lastQaEmitMs)
                put("daily_counters", countersJson)
                put("daily_qa_counters", buildJsonObject {
                    qaCounters.forEach { (day, counters) ->
                        put(day, buildJsonObject {
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
                val now = aimiWallClockMs()
                val pendingId = pendingTickEndId
                val pendingPulseAt = pendingTickPulseWallMs
                if (pendingId > 0L && pendingPulseAt > 0L) {
                    val intraThreshold = intratickStallThresholdMs()
                    val intratickGap = now - pendingPulseAt
                    if (intratickGap >= intraThreshold &&
                        now - lastIntratickStallWarningWallMs >= intraThreshold
                    ) {
                        lastIntratickStallWarningWallMs = now
                        val line = buildJsonObject {
                            put("type", "watchdog_intratick_stall")
                            put("detected_wall_ms", now)
                            put("tick_id", pendingId)
                            put("pulse_wall_ms", pendingPulseAt)
                            put("gap_ms", intratickGap)
                            put("threshold_ms", intraThreshold)
                            put("last_phase", lastReportedPhaseName)
                            put("uptime_ms", platform.uptimeMs())
                        }.toString()
                        appendLoopBlackboxLine(line)
                        aapsLogger.warn(
                            LTag.AIMI,
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
                val line = buildJsonObject {
                    put("type", "watchdog_loop_stall")
                    put("detected_wall_ms", now)
                    put("last_loop_pulse_wall_ms", last)
                    put("gap_ms", gap)
                    put("threshold_ms", LOOP_STALL_THRESHOLD_MS)
                    put("uptime_ms", platform.uptimeMs())
                }.toString()
                appendLoopBlackboxLine(line)
                aapsLogger.warn(
                    LTag.AIMI,
                    "[$TAG] Blackbox: no loop pulse for ${gap}ms (threshold=${LOOP_STALL_THRESHOLD_MS}ms). See $BLACKBOX_FILE_NAME"
                )
            }
        }
    }

    private fun enqueueWrite(task: WriteTask) {
        val accepted = writeQueue.trySend(task).isSuccess
        if (!accepted) {
            val dropped = lock.withLock { ++droppedWrites }
            if (dropped == 1L || dropped % 100L == 0L) {
                aapsLogger.warn(
                    LTag.AIMI,
                    "[$TAG] Export queue saturated. Dropped writes=$dropped"
                )
            }
        }
    }

    private fun tryWrite(task: WriteTask) {
        if (writeToFile(task.primary, task.line, task.mode)) return
        if (!sharedStorageDeniedLogged && task.primary != task.fallback) {
            sharedStorageDeniedLogged = true
            aapsLogger.warn(
                LTag.AIMI,
                "[$TAG] Study export denied on shared storage (${task.primary.value}). " +
                    "Switching to app-scoped fallback (${task.fallback.value})."
            )
        }
        if (task.primary != task.fallback && writeToFile(task.fallback, task.line, task.mode)) return
        aapsLogger.error(LTag.AIMI, "[$TAG] Study export failed on both primary and fallback paths (${task.fallback.value}).")
    }

    /** True when the line or text was written. Never throws: storage answers false on failure. */
    private fun writeToFile(path: AimiPath, payload: String, mode: WriteMode): Boolean {
        if (!storage.exists(path)) {
            storage.createParentDirectories(path)
            if (!storage.createFile(path)) return false
        }
        return when (mode) {
            WriteMode.APPEND_LINE -> {
                // Without this the study files grow without limit between retention passes.
                val outcome = AimiAppendGuard.beforeAppend(storage, path, fileNameOf(path), payload.length + 1)
                logAppendGuardOutcomeOnce(path, outcome)
                storage.appendText(path, "$payload\n")
            }

            WriteMode.OVERWRITE   -> storage.writeText(path, payload)
        }
    }

    private fun fileNameOf(path: AimiPath): String = path.value.substringAfterLast('/')

    /** Logs a ROTATED or OVERFLOW_ALREADY_PRESENT outcome once per file per process. A file stuck
     *  at OVERFLOW_ALREADY_PRESENT would otherwise warn on every write forever. */
    private fun logAppendGuardOutcomeOnce(path: AimiPath, outcome: AimiAppendOutcome) {
        if (outcome != AimiAppendOutcome.ROTATED && outcome != AimiAppendOutcome.OVERFLOW_ALREADY_PRESENT) return
        if (!lock.withLock { appendGuardLoggedPaths.add(path.value) }) return
        aapsLogger.warn(LTag.AIMI, "[$TAG] Append guard: $outcome for ${path.value}")
    }

    private data class WriteTask(
        val primary: AimiPath,
        val fallback: AimiPath,
        val line: String,
        val mode: WriteMode,
    )

    private enum class WriteMode {
        APPEND_LINE,
        OVERWRITE,
    }

    private fun restoreDailyState() {
        val stateFile = platform.fallbackFile(STATE_FILE_NAME)
        if (!storage.exists(stateFile)) return
        try {
            val text = storage.readText(stateFile) ?: return
            val root = Json.parseToJsonElement(text).jsonObject
            lastDailyEmitMs = root.optLongCompat("last_daily_emit_ms", 0L)
            lastQaEmitMs = root.optLongCompat("last_qa_emit_ms", 0L)
            val counters = root.optJsonObjectCompat("daily_counters") ?: return
            counters.keys.forEach { day ->
                val item = counters.optJsonObjectCompat(day) ?: return@forEach
                dailyCounters[day] = DailyDecisionCounters(
                    totalLoops = item.optIntCompat("totalLoops", 0),
                    smbCount = item.optIntCompat("smbCount", 0),
                    suspendCount = item.optIntCompat("suspendCount", 0),
                    tbrUpCount = item.optIntCompat("tbrUpCount", 0),
                    tbrDownCount = item.optIntCompat("tbrDownCount", 0),
                    noneCount = item.optIntCompat("noneCount", 0),
                    vetoCount = item.optIntCompat("vetoCount", 0)
                )
            }
            val qa = root.optJsonObjectCompat("daily_qa_counters")
            qa?.keys?.forEach { day ->
                val item = qa.optJsonObjectCompat(day) ?: return@forEach
                qaCounters[day] = DailyQaCounters(
                    totalEvents = item.optIntCompat("totalEvents", 0),
                    criticalFieldMissingCount = item.optIntCompat("criticalFieldMissingCount", 0),
                    invalidTimestampCount = item.optIntCompat("invalidTimestampCount", 0),
                    decisionPendingCount = item.optIntCompat("decisionPendingCount", 0),
                    staleSnapshotCount = item.optIntCompat("staleSnapshotCount", 0)
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
        if (event.eventTimestamp <= 0L || event.eventTimestamp > aimiWallClockMs() + 5 * 60_000L) {
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
        val now = aimiWallClockMs()
        if (now - lastQaEmitMs < DAILY_EMIT_INTERVAL_MS) return
        lastQaEmitMs = now

        val completeness = if (qa.totalEvents == 0) 1.0
        else ((qa.totalEvents - qa.criticalFieldMissingCount).toDouble() / qa.totalEvents.toDouble()).coerceIn(0.0, 1.0)
        val temporalCoherence = if (qa.totalEvents == 0) 1.0
        else ((qa.totalEvents - qa.invalidTimestampCount).toDouble() / qa.totalEvents.toDouble()).coerceIn(0.0, 1.0)
        val pendingDecisionRate = if (qa.totalEvents == 0) 0.0 else qa.decisionPendingCount.toDouble() / qa.totalEvents.toDouble()

        val payload = buildJsonObject {
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

        val target = platform.primaryFile(QA_FILE_NAME)
        val fallback = platform.fallbackFile(QA_FILE_NAME)
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
            aapsLogger.info(LTag.AIMI, message)
        } else {
            aapsLogger.warn(LTag.AIMI, message)
        }
    }

    private fun formatPct(value: Double): String = "${aimiFmt2(value * 100.0)}%"

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

private fun mapToJsonObject(map: Map<String, Double>): JsonObject =
    buildJsonObject { map.forEach { (key, value) -> put(key, value) } }
