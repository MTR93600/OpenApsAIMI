package app.aaps.aimi_viewer

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream

/** Read-only SAF bridge. Large cumulative exports are filtered while streaming. */
class MainActivity : FlutterActivity() {

    private companion object {
        const val CHANNEL = "app.aaps.aimiviewer/storage"
        const val PICK_DIRECTORY_REQUEST = 4107
        const val PREFS = "aimi_viewer_storage"
        const val PREF_TREE_URI = "tree_uri"
        const val PREF_HORMONE = "hormone_tracking_preference"
        const val TAG = "AimiViewerStorage"

        const val DECISIONS_24H = "AIMI_Decisions_Last24h.jsonl"
        const val DECISIONS_FULL = "AIMI_Decisions.jsonl"
        const val ARCHIVE_DIR = "archive"

        // Matches the janitor's own naming exactly (AimiArchive.evict's pattern), so this never
        // drifts looser than the writer side. A prefix/suffix check alone would also match
        // AIMI_Decisions_Last24h (no .gz, but startsWith/endsWith checks done separately can be
        // fooled by future names); the capture groups double as the month key for C1's filter.
        val ARCHIVE_MEMBER_PATTERN = Regex("""^AIMI_Decisions_(\d{4})-(\d{2})\.jsonl\.gz$""")
        const val PKPD = "oapsaimi_pkpd_records.csv"
        const val EVENTS = "AIMI_HORMONITOR_event_stream_v1.jsonl"
        const val DAILY = "AIMI_HORMONITOR_daily_outcomes_v1.jsonl"
        const val QA = "AIMI_HORMONITOR_dataset_qa_v1.jsonl"
        const val SHADOW = "AIMI_HORMONITOR_shadow_contributions_v1.jsonl"
        const val BLACKBOX = "AIMI_HORMONITOR_loop_blackbox_v1.jsonl"

        val RECOGNIZED = setOf(
            DECISIONS_24H,
            DECISIONS_FULL,
            PKPD,
            EVENTS,
            DAILY,
            QA,
            SHADOW,
            BLACKBOX,
        )
        val METADATA_ONLY = setOf(QA, SHADOW, BLACKBOX)
        val HORMONE_VALUES = setOf("unspecified", "notApplicable", "enabledInAaps")
        const val MAX_WINDOW_MS = 31L * 24L * 60L * 60L * 1000L
    }

    private data class DocumentEntry(
        val name: String,
        val documentId: String,
        val uri: Uri,
        val size: Long,
        val lastModifiedMs: Long,
    )

    private data class ExtractionStats(
        var minTimestampMs: Long? = null,
        var maxTimestampMs: Long? = null,
        var recordsWritten: Int = 0,
        var oversizedLines: Int = 0,
        // A source (an archive member, or the archive directory listing itself) that could not be
        // read counts as damage too: the caller must not report "complete coverage" over a hole
        // it silently skipped.
        var failedSources: Int = 0,
    ) {
        fun observe(timestamp: Long?) {
            if (timestamp == null) return
            minTimestampMs = minTimestampMs?.let { minOf(it, timestamp) } ?: timestamp
            maxTimestampMs = maxTimestampMs?.let { maxOf(it, timestamp) } ?: timestamp
        }
    }

    /** A `archive/AIMI_Decisions_<YYYY-MM>.jsonl.gz` member with its calendar-month bounds. */
    private data class ArchiveMember(
        val entry: DocumentEntry,
        val monthStartMs: Long,
        val monthEndExclusiveMs: Long,
    )

    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var pendingPickerResult: MethodChannel.Result? = null

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "getDirectory" -> handleGetDirectory(result)
                    "chooseDirectory" -> handleChooseDirectory(result)
                    "stageFiles" -> handleStageFiles(call, result)
                    "getHormonePreference" -> result.success(
                        preferences().getString(PREF_HORMONE, "unspecified") ?: "unspecified",
                    )
                    "setHormonePreference" -> handleSetHormonePreference(call, result)
                    else -> result.notImplemented()
                }
            }
    }

    private fun handleGetDirectory(result: MethodChannel.Result) {
        val uri = persistedTreeUri()
        if (uri == null) {
            result.success(null)
            return
        }
        if (!hasPersistedReadPermission(uri)) {
            preferences().edit().remove(PREF_TREE_URI).apply()
            result.error("PERMISSION_LOST", "The persisted folder permission is no longer valid.", null)
            return
        }
        result.success(directoryMap(uri))
    }

    private fun handleChooseDirectory(result: MethodChannel.Result) {
        if (pendingPickerResult != null) {
            result.error("PICKER_ACTIVE", "A folder picker is already open.", null)
            return
        }
        pendingPickerResult = result
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri(
                        "com.android.externalstorage.documents",
                        "primary:Documents/AAPS",
                    ),
                )
            }
        }
        startActivityForResult(intent, PICK_DIRECTORY_REQUEST)
    }

    @Deprecated("Kept for compatibility with FlutterActivity activity result dispatch.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK_DIRECTORY_REQUEST) return
        val result = pendingPickerResult ?: return
        pendingPickerResult = null
        val uri = data?.data
        if (resultCode != Activity.RESULT_OK || uri == null) {
            result.success(null)
            return
        }
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val previous = persistedTreeUri()
            preferences().edit().putString(PREF_TREE_URI, uri.toString()).apply()
            if (previous != null && previous != uri) {
                runCatching {
                    contentResolver.releasePersistableUriPermission(
                        previous,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
            }
            result.success(directoryMap(uri))
        } catch (error: SecurityException) {
            result.error("PERMISSION_LOST", error.message, null)
        }
    }

    private fun handleSetHormonePreference(call: MethodCall, result: MethodChannel.Result) {
        val value = call.argument<String>("value")
        if (value == null || value !in HORMONE_VALUES) {
            result.error("INVALID_PREFERENCE", "Unknown hormone tracking preference.", null)
            return
        }
        preferences().edit().putString(PREF_HORMONE, value).apply()
        result.success(null)
    }

    private fun handleStageFiles(call: MethodCall, result: MethodChannel.Result) {
        val startMs = call.argument<Number>("startMs")?.toLong()
        val endMs = call.argument<Number>("endMs")?.toLong()
        if (startMs == null || endMs == null || startMs >= endMs || endMs - startMs > MAX_WINDOW_MS) {
            result.error("INVALID_PERIOD", "The requested period is invalid or too large.", null)
            return
        }
        val treeUri = persistedTreeUri()
        if (treeUri == null || !hasPersistedReadPermission(treeUri)) {
            result.error("PERMISSION_LOST", "Select Documents/AAPS again.", null)
            return
        }
        ioExecutor.execute {
            try {
                val staged = stageRecognizedFiles(treeUri, startMs, endMs)
                runOnUiThread { result.success(staged) }
            } catch (error: SecurityException) {
                runOnUiThread { result.error("PERMISSION_LOST", error.message, null) }
            } catch (error: Exception) {
                Log.e(TAG, "Unable to stage AIMI exports", error)
                runOnUiThread { result.error("STAGE_FAILED", error.message, null) }
            }
        }
    }

    private fun stageRecognizedFiles(
        treeUri: Uri,
        startMs: Long,
        endMs: Long,
    ): List<Map<String, Any>> {
        val children = listDirectChildren(treeUri).associateBy { it.name }
        val decision = children[DECISIONS_FULL] ?: children[DECISIONS_24H]
        val stageDirectory = File(cacheDir, "aimi_import").apply {
            if (!exists() && !mkdirs()) error("Unable to create private staging directory.")
        }
        stageDirectory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }

        val result = ArrayList<Map<String, Any>>()
        if (decision != null) {
            val target = File(stageDirectory, DECISIONS_FULL)
            result += if (decision.name == DECISIONS_FULL) {
                stageIndexedDecisions(treeUri, decision, target, startMs, endMs)
            } else {
                stageStreamedFile(
                    entry = decision,
                    logicalName = DECISIONS_FULL,
                    target = target,
                    startMs = startMs,
                    endMs = endMs,
                    projector = ::compactDecision,
                    timestampOf = { parseTimestamp(it.opt("timestamp")) },
                    coverageComplete = false,
                    mode = "last24h_fallback",
                )
            }
        }
        for (name in listOf(PKPD, EVENTS, DAILY, QA, SHADOW, BLACKBOX)) {
            val entry = children[name] ?: continue
            val target = File(stageDirectory, name)
            result += when {
                name in METADATA_ONLY -> stageMetadataOnly(entry, target)
                name == PKPD -> stageCsv(entry, target, startMs, endMs)
                name == EVENTS -> stageStreamedFile(
                    entry,
                    name,
                    target,
                    startMs,
                    endMs,
                    ::compactHormonitorEvent,
                    { parseTimestamp(it.opt("timestamp")) },
                    coverageComplete = true,
                    mode = "stream_filter",
                )
                else -> stageStreamedFile(
                    entry,
                    name,
                    target,
                    startMs,
                    endMs,
                    ::compactDailyOutcome,
                    ::dailyTimestamp,
                    coverageComplete = true,
                    mode = "day_local_filter",
                )
            }
        }
        return result
    }

    private fun stageIndexedDecisions(
        treeUri: Uri,
        entry: DocumentEntry,
        target: File,
        startMs: Long,
        endMs: Long,
    ): Map<String, Any> {
        val zoneId = ZoneId.systemDefault().id
        val identity = DecisionIndexIdentity(
            uri = treeUri.toString(),
            documentId = entry.documentId,
            zoneId = zoneId,
            sourceSize = entry.size,
            lastModifiedMs = entry.lastModifiedMs,
        )
        val store = DecisionIndexStore(File(filesDir, "decision_index/index_v1.json"))
        val cached = store.load()
        val index = if (cached?.isExact(identity) == true) {
            cached
        } else {
            val previous = cached?.takeIf { it.canAppend(identity) }
            runCatching {
                val descriptor = contentResolver.openFileDescriptor(entry.uri, "r")
                    ?: error("Unable to open ${entry.name}")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    val offset = previous?.checkpointOffset ?: 0L
                    input.channel.position(offset)
                    DecisionIndexBuilder.build(identity, input, previous)
                }
            }.getOrElse { error ->
                Log.w(TAG, "Decision source is not seekable; using bounded streaming", error)
                return stageStreamedFile(
                    entry,
                    DECISIONS_FULL,
                    target,
                    startMs,
                    endMs,
                    ::compactDecision,
                    { parseTimestamp(it.opt("timestamp")) },
                    coverageComplete = true,
                    mode = "stream_fallback",
                )
            }.also(store::save)
        }

        // Archives are read forward and (for the months that can contain the request) scanned in
        // full, so their own stats give the true archive coverage for those months, the same way
        // `index` gives the true coverage of the live file. Keep them separate from `stats`, which
        // only sees the requested-window slice of the live file, so a request outside that slice
        // cannot be misreported as "archives have no data" just because the live scan alone did
        // not see it.
        val stats = ExtractionStats()
        val archiveStats = ExtractionStats()
        // The live file's own index already tells us whether it alone covers the request (its
        // segments span every day the index has ever seen, not just the requested window). When
        // it does, opening any archive would only decompress and JSON-parse months of data that
        // cannot change the staged result - at ~200 MB compressed / ~2 GB of JSONL for a full
        // 12-month retention window, that cost is not acceptable to pay on every stage call for
        // what is the common case (a recent window fully inside the 7-day live file).
        val liveStart = index.coverageStartMs
        val liveCoversRequest = liveStart != null && liveStart <= startMs
        BufferedOutputStream(FileOutputStream(target, false)).use { output ->
            if (!liveCoversRequest) {
                stageArchivedDecisionLines(treeUri, output, startMs, endMs, zoneId, archiveStats)
            }
            val range = index.byteRange(startMs, endMs)
            if (range != null) {
                val descriptor = contentResolver.openFileDescriptor(entry.uri, "r")
                    ?: error("Unable to reopen ${entry.name}")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    input.channel.position(range.first)
                    val scan = BoundedUtf8LineScanner.scan(
                        input = input,
                        startOffset = range.first,
                        endExclusive = range.last + 1L,
                        emitUnterminatedTail = true,
                    ) { line ->
                        writeJsonLineIfSelected(
                            line.text,
                            output,
                            startMs,
                            endMs,
                            stats,
                            ::compactDecision,
                            { parseTimestamp(it.opt("timestamp")) },
                        )
                    }
                    stats.oversizedLines += scan.skippedOversizedLines
                }
            }
        }
        val coverageStartMs = listOfNotNull(archiveStats.minTimestampMs, index.coverageStartMs).minOrNull()
        val coverageEndMs = listOfNotNull(archiveStats.maxTimestampMs?.plus(1L), index.coverageEndMs).maxOrNull()
        // A skipped or unreadable archive member is invisible to the rest of this function, so
        // without failedSources here the coverage union above could span a hole that was never
        // actually read (e.g. the current month's archive member truncated by a power cut) and
        // still be reported as complete.
        val damaged = stats.oversizedLines > 0 || archiveStats.oversizedLines > 0 || archiveStats.failedSources > 0
        return stagedMap(
            logicalName = DECISIONS_FULL,
            entry = entry,
            target = target,
            coverageStartMs = coverageStartMs,
            coverageEndMs = coverageEndMs,
            coverageComplete = requestedWindowCovered(
                requestedStartMs = startMs,
                requestedEndMs = endMs,
                sourceStartMs = coverageStartMs,
                sourceEndMs = coverageEndMs,
                damaged = damaged,
            ),
            extractionMode = "indexed_seek",
            truncated = damaged,
        )
    }

    /**
     * Writes the `archive/AIMI_Decisions_<YYYY-MM>.jsonl.gz` members that sit beside the live
     * decisions file, oldest first, into [output]. Each member is streamed forward (a gzip member
     * cannot be seeked into), so the byte-offset index only ever applies to the live file that is
     * written after this call.
     *
     * A member whose calendar month cannot intersect `[startMs, endMs)` is not opened at all - at
     * up to ~200 MB compressed for a year of retention, decompressing and JSON-parsing every month
     * on every call would turn a sub-second stage into minutes. A skipped member still contributes
     * its month's calendar bounds to [stats] (a safe, if approximate, proxy for its true content
     * range) so coverage does not silently narrow just because the member was never opened.
     *
     * A missing `archive` folder is the common case today and is treated as "no archives". A
     * corrupt or truncated member, or a member that cannot be listed, is skipped and counted in
     * [ExtractionStats.failedSources] so it cannot hide the members read before it, cannot stop the
     * live file from being staged, and cannot be silently reported as complete coverage.
     */
    private fun stageArchivedDecisionLines(
        treeUri: Uri,
        output: BufferedOutputStream,
        startMs: Long,
        endMs: Long,
        zoneId: String,
        stats: ExtractionStats,
    ) {
        runCatching {
            val rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri)
            val archiveDocumentId = findChildDirectory(treeUri, rootDocumentId, ARCHIVE_DIR) ?: return
            val zone = ZoneId.of(zoneId)
            val members = listArchiveDecisionFiles(treeUri, archiveDocumentId, zone)
            for (member in members) {
                val intersectsRequest = member.monthStartMs < endMs && member.monthEndExclusiveMs > startMs
                if (!intersectsRequest) {
                    stats.observe(member.monthStartMs)
                    stats.observe(member.monthEndExclusiveMs - 1L)
                    continue
                }
                runCatching {
                    val input = contentResolver.openInputStream(member.entry.uri)
                        ?: error("Unable to open ${member.entry.name}")
                    // `.use` on `input` itself, wrapping the fallible `GZIPInputStream(...)`
                    // construction inside it, so a bad gzip header (thrown from the constructor,
                    // before any `.use` on the GZIPInputStream can attach) still closes `input`.
                    input.use {
                        GZIPInputStream(it).use { gzip ->
                            val scan = BoundedUtf8LineScanner.scan(gzip, emitUnterminatedTail = true) { line ->
                                writeJsonLineIfSelected(
                                    line.text,
                                    output,
                                    startMs,
                                    endMs,
                                    stats,
                                    ::compactDecision,
                                    { parseTimestamp(it.opt("timestamp")) },
                                )
                            }
                            stats.oversizedLines += scan.skippedOversizedLines
                        }
                    }
                }.onFailure { error ->
                    stats.failedSources++
                    Log.w(TAG, "Skipping unreadable decision archive ${member.entry.name}", error)
                }
            }
        }.onFailure { error ->
            stats.failedSources++
            Log.w(TAG, "Unable to list decision archives", error)
        }
    }

    /** Returns the document id of the directory named [name] directly under [parentDocumentId], or null. */
    private fun findChildDirectory(treeUri: Uri, parentDocumentId: String, name: String): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeIndex) != DocumentsContract.Document.MIME_TYPE_DIR) continue
                if (cursor.getString(nameIndex) == name) return cursor.getString(idIndex)
            }
        }
        return null
    }

    /**
     * Lists `AIMI_Decisions_<YYYY-MM>.jsonl.gz` members under the archive directory, oldest month
     * first, with each member's calendar-month bounds parsed from its own filename (the same
     * pattern the janitor writes it with).
     */
    private fun listArchiveDecisionFiles(treeUri: Uri, archiveDocumentId: String, zone: ZoneId): List<ArchiveMember> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, archiveDocumentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<ArchiveMember>()
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(nameIndex) ?: continue
                val match = ARCHIVE_MEMBER_PATTERN.find(name) ?: continue
                val year = match.groupValues[1].toInt()
                val month = match.groupValues[2].toInt()
                val yearMonth = runCatching { YearMonth.of(year, month) }.getOrNull() ?: continue
                val monthStartMs = yearMonth.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val monthEndExclusiveMs = yearMonth.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val childDocumentId = cursor.getString(idIndex)
                result += ArchiveMember(
                    entry = DocumentEntry(
                        name = name,
                        documentId = childDocumentId,
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId),
                        size = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex),
                        lastModifiedMs = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
                    ),
                    monthStartMs = monthStartMs,
                    monthEndExclusiveMs = monthEndExclusiveMs,
                )
            }
        }
        return result.sortedBy { it.entry.name }
    }

    private fun stageStreamedFile(
        entry: DocumentEntry,
        logicalName: String,
        target: File,
        startMs: Long,
        endMs: Long,
        projector: (JSONObject) -> JSONObject?,
        timestampOf: (JSONObject) -> Long?,
        coverageComplete: Boolean,
        mode: String,
    ): Map<String, Any> {
        val stats = ExtractionStats()
        val input = contentResolver.openInputStream(entry.uri) ?: error("Unable to open ${entry.name}")
        input.use {
            BufferedOutputStream(FileOutputStream(target, false)).use { output ->
                val scan = BoundedUtf8LineScanner.scan(
                    input = it,
                    emitUnterminatedTail = true,
                ) { line ->
                    writeJsonLineIfSelected(
                        line.text,
                        output,
                        startMs,
                        endMs,
                        stats,
                        projector,
                        timestampOf,
                    )
                }
                stats.oversizedLines += scan.skippedOversizedLines
            }
        }
        val fallbackCovered = when {
            !coverageComplete -> false
            mode == "day_local_filter" ->
                stats.recordsWritten > 0 && stats.oversizedLines == 0
            else -> requestedWindowCovered(
                requestedStartMs = startMs,
                requestedEndMs = endMs,
                sourceStartMs = stats.minTimestampMs,
                sourceEndMs = stats.maxTimestampMs?.plus(1L),
                damaged = stats.oversizedLines > 0,
            )
        }
        return stagedMap(
            logicalName,
            entry,
            target,
            stats.minTimestampMs,
            stats.maxTimestampMs?.plus(1L),
            fallbackCovered,
            mode,
            truncated = stats.oversizedLines > 0,
        )
    }

    private fun writeJsonLineIfSelected(
        text: String,
        output: BufferedOutputStream,
        startMs: Long,
        endMs: Long,
        stats: ExtractionStats,
        projector: (JSONObject) -> JSONObject?,
        timestampOf: (JSONObject) -> Long?,
    ) {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val timestamp = timestampOf(root)
        stats.observe(timestamp)
        if (timestamp == null || timestamp < startMs || timestamp >= endMs) return
        val compact = projector(root) ?: return
        output.write(compact.toString().toByteArray(Charsets.UTF_8))
        output.write('\n'.code)
        stats.recordsWritten++
    }

    private fun stageCsv(
        entry: DocumentEntry,
        target: File,
        startMs: Long,
        endMs: Long,
    ): Map<String, Any> {
        val stats = ExtractionStats()
        val input = contentResolver.openInputStream(entry.uri) ?: error("Unable to open ${entry.name}")
        input.use {
            BufferedOutputStream(FileOutputStream(target, false)).use { output ->
                val scan = BoundedUtf8LineScanner.scan(it, emitUnterminatedTail = true) { line ->
                    val columns = line.text.split(',', limit = 3)
                    val epochMin = columns.getOrNull(1)?.trim()?.toLongOrNull()
                    val timestamp = epochMin?.times(60_000L)
                    stats.observe(timestamp)
                    if (timestamp != null && timestamp >= startMs && timestamp < endMs) {
                        output.write(line.text.toByteArray(Charsets.UTF_8))
                        output.write('\n'.code)
                        stats.recordsWritten++
                    }
                }
                stats.oversizedLines += scan.skippedOversizedLines
            }
        }
        return stagedMap(
            PKPD,
            entry,
            target,
            stats.minTimestampMs,
            stats.maxTimestampMs?.plus(1L),
            requestedWindowCovered(
                requestedStartMs = startMs,
                requestedEndMs = endMs,
                sourceStartMs = stats.minTimestampMs,
                sourceEndMs = stats.maxTimestampMs?.plus(1L),
                damaged = stats.oversizedLines > 0,
            ),
            "stream_filter",
            truncated = stats.oversizedLines > 0,
        )
    }

    private fun stageMetadataOnly(entry: DocumentEntry, target: File): Map<String, Any> {
        FileOutputStream(target, false).use { }
        return stagedMap(
            entry.name,
            entry,
            target,
            null,
            null,
            true,
            "metadata_only",
            truncated = false,
        )
    }

    /**
     * Treat small gaps around civil boundaries as normal loop cadence, but do
     * not claim that a historical interval is complete when the source starts
     * or stops well inside it. The current partial period tolerates a recent
     * last tick because AAPS does not necessarily export at the exact refresh
     * millisecond.
     */
    private fun requestedWindowCovered(
        requestedStartMs: Long,
        requestedEndMs: Long,
        sourceStartMs: Long?,
        sourceEndMs: Long?,
        damaged: Boolean,
    ): Boolean {
        if (damaged || sourceStartMs == null || sourceEndMs == null) return false
        val cadenceToleranceMs = 15L * 60L * 1000L
        val observableEndMs = minOf(requestedEndMs, System.currentTimeMillis())
        val startsInTime = sourceStartMs <= requestedStartMs + cadenceToleranceMs
        val endsInTime = sourceEndMs >= observableEndMs - cadenceToleranceMs
        return startsInTime && endsInTime
    }

    private fun stagedMap(
        logicalName: String,
        entry: DocumentEntry,
        target: File,
        coverageStartMs: Long?,
        coverageEndMs: Long?,
        coverageComplete: Boolean,
        extractionMode: String,
        truncated: Boolean,
    ): Map<String, Any> = buildMap {
        put("name", logicalName)
        put("sourceName", entry.name)
        put("path", target.absolutePath)
        put("sourceSize", entry.size)
        put("stagedSize", target.length())
        put("lastModifiedMs", entry.lastModifiedMs)
        put("truncated", truncated)
        put("coverageComplete", coverageComplete)
        put("extractionMode", extractionMode)
        coverageStartMs?.let { put("coverageStartMs", it) }
        coverageEndMs?.let { put("coverageEndMs", it) }
    }

    private fun compactDecision(root: JSONObject): JSONObject = JSONObject().apply {
        copy(root, this, "record_type", "parent_event_id", "event_id", "timestamp")
        if (root.optString("record_type") == "auditor_followup") return@apply
        root.optJSONObject("baseline_state")?.let { baseline ->
            put("baseline_state", JSONObject().also {
                copy(baseline, it, "current_bg_mgdl", "iob_u", "cob_g")
            })
        }
        root.optJSONObject("adjustments")?.let { adjustments ->
            put("adjustments", JSONObject().apply {
                adjustments.optJSONObject("patient_mode")?.let { mode ->
                    put("patient_mode", JSONObject().also { copy(mode, it, "mode") })
                }
                adjustments.optJSONObject("safety_risk")?.let { safety ->
                    put("safety_risk", JSONObject().also { copy(safety, it, "safety_gate") })
                }
            })
        }
        root.optJSONObject("outcome")?.let { outcome ->
            put("outcome", JSONObject().also {
                copy(outcome, it, "decision", "clinical_decision", "amount", "dosage_u")
            })
        }
    }

    private fun compactHormonitorEvent(root: JSONObject): JSONObject = JSONObject().apply {
        copy(
            root,
            this,
            "event_id",
            "timestamp",
            "current_bg_mgdl",
            "iob_u",
            "cob_g",
            "physio_state",
            "safety_gate",
            "cycle_phase",
            "cycle_tracking_mode",
            "final_loop_decision_type",
        )
        root.optJSONObject("patient_story")?.let { story ->
            put("patient_story", JSONObject().also {
                copy(story, it, "patient_mode", "patient_mode_confidence")
            })
        }
    }

    private fun compactDailyOutcome(root: JSONObject): JSONObject = JSONObject().also {
        copy(root, it, "generated_at", "day_local", "tdd_24h_total_u")
    }

    private fun dailyTimestamp(root: JSONObject): Long? {
        val day = root.optString("day_local").takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
            ?: return null
        return runCatching {
            LocalDate.parse(day).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()
    }

    private fun copy(source: JSONObject, target: JSONObject, vararg keys: String) {
        keys.forEach { key -> if (source.has(key)) target.put(key, source.opt(key)) }
    }

    private fun listDirectChildren(treeUri: Uri): List<DocumentEntry> {
        val documentId = DocumentsContract.getTreeDocumentId(treeUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val result = ArrayList<DocumentEntry>()
        contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (cursor.moveToNext()) {
                if (cursor.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR) continue
                val name = cursor.getString(nameIndex) ?: continue
                if (name !in RECOGNIZED) continue
                val childDocumentId = cursor.getString(idIndex)
                result += DocumentEntry(
                    name = name,
                    documentId = childDocumentId,
                    uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childDocumentId),
                    size = if (cursor.isNull(sizeIndex)) 0L else cursor.getLong(sizeIndex),
                    lastModifiedMs = if (cursor.isNull(modifiedIndex)) 0L else cursor.getLong(modifiedIndex),
                )
            }
        }
        return result
    }

    private fun directoryMap(uri: Uri): Map<String, String> = mapOf(
        "uri" to uri.toString(),
        "name" to displayName(uri),
    )

    private fun displayName(uri: Uri): String {
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        val rawName = runCatching {
            contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return if (rawName.equals("AAPS", ignoreCase = true)) "Documents/AAPS"
        else rawName?.takeIf { it.isNotBlank() } ?: "Documents/AAPS"
    }

    private fun hasPersistedReadPermission(uri: Uri): Boolean =
        contentResolver.persistedUriPermissions.any { permission ->
            permission.uri == uri && permission.isReadPermission
        }

    private fun persistedTreeUri(): Uri? =
        preferences().getString(PREF_TREE_URI, null)?.let(Uri::parse)

    private fun preferences() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onDestroy() {
        ioExecutor.shutdown()
        super.onDestroy()
    }
}
