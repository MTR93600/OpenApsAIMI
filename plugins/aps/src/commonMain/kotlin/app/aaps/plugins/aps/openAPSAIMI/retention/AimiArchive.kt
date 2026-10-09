package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Writes and prunes the compressed history.
 *
 * Archives are gzip files holding one member per janitor pass. Concatenated gzip members are valid,
 * and a gzip reader reads them back as a single stream, so a pass never has to read or re-compress
 * what is already there. On a 2 GB history that is the difference between a few seconds and several
 * minutes per day.
 *
 * `.gz` is deliberately not one of the extensions `AimiStorageHelper.listBackupCandidates()`
 * collects, so archives stay out of the cloud backup upload.
 *
 * File names go through [AimiStorage] (`resolve`/`sibling`), text-level marker I/O through
 * [AimiStorage] too, and every byte-level operation - the gzip append itself, fsyncs, directory
 * listing - through the [AimiRetentionFs] seams. The crash-safety protocol below (the `.partial`
 * sidecar and its fsyncs) stays in shared code so both platforms keep the same guarantees.
 */
internal object AimiArchive {

    private const val DIR_NAME = "archive"

    /**
     * Suffix of the sidecar marker written before an append and removed after it completes. Its
     * content is the target's length before this append started (or `0` when the target did not
     * exist yet), so a surviving marker after a crash tells [AimiRetentionManager.recoverInterruptedArchives]
     * exactly how to undo the interrupted append: truncate the target back to that length, or
     * delete it outright when the recorded length is `0`.
     */
    private const val PARTIAL_SUFFIX = ".partial"

    fun directory(storage: AimiStorage, aimiDir: AimiPath): AimiPath = storage.resolve(aimiDir, DIR_NAME)

    /** `AIMI_Decisions.jsonl` + `2026-09` becomes `archive/AIMI_Decisions_2026-09.jsonl.gz`. */
    fun memberFile(storage: AimiStorage, aimiDir: AimiPath, fileName: String, month: String): AimiPath {
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        val name = if (ext.isEmpty()) "${base}_$month.gz" else "${base}_$month.$ext.gz"
        return storage.resolve(directory(storage, aimiDir), name)
    }

    /** The sidecar marker file for [target]. Never has content of its own once [target] does. */
    private fun partialMarkerFile(storage: AimiStorage, target: AimiPath): AimiPath =
        storage.sibling(target, PARTIAL_SUFFIX)

    /** One archive member whose `.partial` marker survived, with the length recorded before it started. */
    internal data class PartialAppendRecord(val target: AimiPath, val marker: AimiPath, val previousLength: Long)

    /**
     * Every archive member under [aimiDir] with a surviving `.partial` marker: an append that was
     * cut short (process kill, WorkManager stopping the worker) before it could remove its own
     * marker. A malformed marker (unreadable or non-numeric) is skipped rather than guessed at; it is
     * left on disk for the next pass rather than acted on with a made-up length.
     */
    fun findInterruptedAppends(storage: AimiStorage, aimiDir: AimiPath): List<PartialAppendRecord> {
        val dir = directory(storage, aimiDir)
        if (!storage.exists(dir)) return emptyList()
        return aimiFsListFiles(dir).mapNotNull { entry ->
            if (!entry.name.endsWith(PARTIAL_SUFFIX)) return@mapNotNull null
            val previousLength = storage.readText(entry.path)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            val target = storage.resolve(dir, entry.name.removeSuffix(PARTIAL_SUFFIX))
            PartialAppendRecord(target, entry.path, previousLength)
        }
    }

    /**
     * Appends `[start, endExclusive)` of [source] to [target] as a new gzip member.
     *
     * [header] is written at the top of the member when the file has one, so each archive can be
     * opened on its own without the live file.
     *
     * The member is fsynced to disk before this returns. Without that, the gzip trailer can still be
     * sitting in the page cache when a caller unlinks its only other copy of the data (as `retire`
     * does with the source file); a power cut in that window would lose the data outright, since
     * `retire` has no atomic-replace step behind it to fall back on, unlike `trim`, which only ever
     * swaps a tmp file into place. The cost is one fsync per managed file per day, and this path never
     * runs on the dosing thread, so that cost is fine.
     *
     * A process kill (or a WorkManager-enforced stop; the first pass over a 2 GB file can run for
     * minutes) can still land in the middle of the write above, after some bytes of this member have
     * reached disk but before the gzip trailer has. Left alone, that truncated member would make
     * every later member of the same archive file unreadable: a gzip reader throws on the corrupt
     * trailer and never reaches what comes after it in the stream. To guard against that, a
     * `.partial` sidecar marker is written next to [target] before anything touches it, holding the
     * length [target] had before this call (`0` when it did not exist yet), and its own directory
     * entry is fsynced right away so its *presence* is not only in the page cache either. The marker
     * is removed only on success, after the member has been written and synced. When any exception
     * is thrown, the marker survives intentionally so the next pass's
     * [recoverInterruptedArchives][AimiRetentionManager.recoverInterruptedArchives] can detect and
     * repair the incomplete or truncated member. An in-process failure is also handled by the
     * caller's own rollback (see [AimiRetentionManager.trim] and [AimiRetentionManager.retire]),
     * which restores the length [target] had before the whole pass started, independent of this
     * marker; the surviving marker ensures that a failure outside the caller's own try/catch (a
     * process kill or WorkManager stop) can also be repaired. The marker's removal is fsynced too,
     * for the same reason its creation is.
     *
     * A surviving marker - one a genuine process kill or WorkManager stop cut short before this
     * call's own cleanup could run - is [AimiRetentionManager]'s signal, at the start of its next
     * pass, to undo the interrupted append before anything else touches the archive directory - see
     * [findInterruptedAppends] and [AimiRetentionManager.recoverInterruptedArchives], which also
     * clamps its own recovery so it can never extend a target past its current length, for the same
     * reason. All of this is durable against a process kill or a WorkManager stop; against power loss
     * it is best-effort only - an fsynced directory entry can still race the data it points at.
     */
    fun appendMember(
        storage: AimiStorage,
        source: AimiPath,
        start: Long,
        endExclusive: Long,
        target: AimiPath,
        header: ByteArray?,
    ) {
        if (endExclusive <= start) return
        storage.createParentDirectories(target)
        val isNewTarget = !storage.exists(target)
        val marker = partialMarkerFile(storage, target)
        check(storage.writeText(marker, (if (isNewTarget) 0L else storage.sizeBytes(target)).toString())) {
            "retention: could not write .partial marker for ${storage.displayPath(target)}"
        }
        aimiFsSyncParentDir(target)
        // Appends the member and fsyncs the target file itself, so the gzip trailer is on the
        // device before the marker goes away. Throws on failure, leaving the marker behind.
        aimiGzipAppendMember(source, start, endExclusive, target, header)
        // A brand-new member file's own directory entry also needs its own fsync: without it,
        // the file's *content* survives a power cut (it was just fsynced above) but its
        // *presence* in the directory might not, on a filesystem that does not implicitly
        // persist directory entries.
        if (isNewTarget) aimiFsSyncParentDir(target)
        // Marker is removed on success only - after the member has been written and fsynced.
        // If anything throws, the marker survives so the next pass's recoverInterruptedArchives()
        // repairs the member.
        storage.delete(marker)
        aimiFsSyncParentDir(target)
    }

    /** Deletes members of [fileName] older than [keepMonths]. Returns how many were removed. */
    fun evict(
        storage: AimiStorage,
        aimiDir: AimiPath,
        fileName: String,
        keepMonths: Int,
        nowMs: Long,
        zone: TimeZone = TimeZone.currentSystemDefault(),
    ): Int {
        val dir = directory(storage, aimiDir)
        if (!storage.exists(dir)) return 0
        val base = Regex.escape(fileName.substringBeforeLast('.'))
        val ext = fileName.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) """\.gz""" else """\.${Regex.escape(ext)}\.gz"""
        val pattern = Regex("""^${base}_(\d{4})-(\d{2})$suffix$""")
        // YearMonth arithmetic by hand: kotlinx.datetime has no YearMonth. Compared as absolute
        // month numbers, `isBefore(oldest)` is a strict `<`.
        val nowDate = Instant.fromEpochMilliseconds(nowMs).toLocalDateTime(zone).date
        val oldestTotal = nowDate.year * 12 + nowDate.monthNumber - keepMonths
        var removed = 0
        aimiFsListFiles(dir).sortedBy { it.name }.forEach { entry ->
            val match = pattern.find(entry.name) ?: return@forEach
            val total = match.groupValues[1].toInt() * 12 + match.groupValues[2].toInt()
            if (total < oldestTotal && storage.delete(entry.path)) removed++
        }
        return removed
    }
}
