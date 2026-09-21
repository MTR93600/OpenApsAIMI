package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.util.zip.GZIPOutputStream

/**
 * Writes and prunes the compressed history.
 *
 * Archives are gzip files holding one member per janitor pass. Concatenated gzip members are valid,
 * and `GZIPInputStream` reads them back as a single stream, so a pass never has to read or
 * re-compress what is already there. On a 2 GB history that is the difference between a few seconds
 * and several minutes per day.
 *
 * `.gz` is deliberately not one of the extensions `AimiStorageHelper.listBackupCandidates()`
 * collects, so archives stay out of the cloud backup upload.
 */
internal object AimiArchive {

    private const val DIR_NAME = "archive"

    /**
     * Suffix of the sidecar marker written before an append and removed after it completes. Its
     * content is the target's length before this append started (or `0` when the target did not
     * exist yet), so a surviving marker after a crash tells [AimiRetentionManager.recoverInterruptedArchives]
     * exactly how to undo the interrupted append: `setLength` the target back to that length, or
     * delete it outright when the recorded length is `0`.
     */
    private const val PARTIAL_SUFFIX = ".partial"

    fun directory(aimiDir: File): File = File(aimiDir, DIR_NAME)

    /** `AIMI_Decisions.jsonl` + `2026-09` becomes `archive/AIMI_Decisions_2026-09.jsonl.gz`. */
    fun memberFile(aimiDir: File, fileName: String, month: String): File {
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        val name = if (ext.isEmpty()) "${base}_$month.gz" else "${base}_$month.$ext.gz"
        return File(directory(aimiDir), name)
    }

    /** The sidecar marker file for [target]. Never has content of its own once [target] does. */
    private fun partialMarkerFile(target: File): File = File(target.parentFile, target.name + PARTIAL_SUFFIX)

    /** One archive member whose `.partial` marker survived, with the length recorded before it started. */
    internal data class PartialAppendRecord(val target: File, val marker: File, val previousLength: Long)

    /**
     * Every archive member under [aimiDir] with a surviving `.partial` marker: an append that was
     * cut short (process kill, WorkManager stopping the worker) before it could remove its own
     * marker. A malformed marker (unreadable or non-numeric) is skipped rather than guessed at; it is
     * left on disk for the next pass rather than acted on with a made-up length.
     */
    fun findInterruptedAppends(aimiDir: File): List<PartialAppendRecord> {
        val dir = directory(aimiDir)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { candidate -> candidate.name.endsWith(PARTIAL_SUFFIX) }.orEmpty().mapNotNull { marker ->
            val previousLength = runCatching { marker.readText().trim().toLong() }.getOrNull() ?: return@mapNotNull null
            val target = File(dir, marker.name.removeSuffix(PARTIAL_SUFFIX))
            PartialAppendRecord(target, marker, previousLength)
        }
    }

    /**
     * Best-effort fsync of a directory's own entry, so a newly created file's presence survives a
     * power cut, not just its content. Some filesystems refuse to open a directory for reading (for
     * example FAT); that is caught and ignored rather than failing the whole pass over one missing
     * guarantee.
     */
    private fun fsyncDirectory(dir: File?) {
        if (dir == null) return
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            // Best effort only; see kdoc above.
        } catch (e: UnsupportedOperationException) {
            // Some filesystems/NIO providers do not support opening a directory this way at all.
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
     * every later member of the same archive file unreadable: `GZIPInputStream` throws on the
     * corrupt trailer and never reaches what comes after it in the stream. To guard against that, a
     * `.partial` sidecar marker is written next to [target] before anything touches it, holding the
     * length [target] had before this call (`0` when it did not exist yet), and its own directory
     * entry is fsynced right away so its *presence* is not only in the page cache either. The marker
     * is removed only on success, after the member has been written and [raw.fd.sync] has returned.
     * When any exception is thrown, the marker survives intentionally so the next pass's
     * [recoverInterruptedArchives] can detect and repair the incomplete or truncated member. An
     * in-process failure is also handled by the caller's own rollback (see [AimiRetentionManager.trim]
     * and [AimiRetentionManager.retire]), which restores the length [target] had before the whole
     * pass started, independent of this marker; the surviving marker ensures that a failure outside
     * the caller's own try/catch (a process kill or WorkManager stop) can also be repaired. The
     * marker's removal is fsynced too, for the same reason its creation is.
     *
     * A surviving marker - one a genuine process kill or WorkManager stop cut short before this
     * call's own cleanup could run - is [AimiRetentionManager]'s signal, at the start of its next
     * pass, to undo the interrupted append before anything else touches the archive directory - see
     * [findInterruptedAppends] and [AimiRetentionManager.recoverInterruptedArchives], which also
     * clamps its own recovery so it can never extend a target past its current length, for the same
     * reason. All of this is durable against a process kill or a WorkManager stop; against power loss
     * it is best-effort only - an fsynced directory entry can still race the data it points at.
     */
    fun appendMember(source: File, start: Long, endExclusive: Long, target: File, header: ByteArray?) {
        if (endExclusive <= start) return
        target.parentFile?.mkdirs()
        val isNewTarget = !target.exists()
        val marker = partialMarkerFile(target)
        marker.writeText((if (isNewTarget) 0L else target.length()).toString())
        fsyncDirectory(target.parentFile)
        FileOutputStream(target, true).use { raw ->
            // Closing the GZIPOutputStream flushes its deflate buffer and releases its native
            // resources, and must happen before the fsync below runs. But that close() also
            // cascades down to the stream it wraps, which would close `raw` before the sync can
            // happen. This shield absorbs the cascaded close so `raw` stays open until the sync
            // is done.
            val keepRawOpen = object : OutputStream() {
                override fun write(b: Int) = raw.write(b)
                override fun write(b: ByteArray, off: Int, len: Int) = raw.write(b, off, len)
                override fun flush() = raw.flush()
                override fun close() = Unit
            }
            GZIPOutputStream(BufferedOutputStream(keepRawOpen)).use { gz ->
                header?.let { gz.write(it) }
                RandomAccessFile(source, "r").use { input ->
                    input.seek(start)
                    val buffer = ByteArray(64 * 1024)
                    var remaining = endExclusive - start
                    while (remaining > 0) {
                        val want = minOf(remaining, buffer.size.toLong()).toInt()
                        val read = input.read(buffer, 0, want)
                        if (read <= 0) break
                        gz.write(buffer, 0, read)
                        remaining -= read
                    }
                }
            }
            raw.fd.sync()
        }
        // A brand-new member file's own directory entry also needs its own fsync: without it,
        // the file's *content* survives a power cut (it was just fsynced above) but its
        // *presence* in the directory might not, on a filesystem that does not implicitly
        // persist directory entries.
        if (isNewTarget) fsyncDirectory(target.parentFile)
        // Marker is removed on success only - after the member has been written and fsynced.
        // If anything throws, the marker survives so the next pass's recoverInterruptedArchives()
        // repairs the member.
        marker.delete()
        fsyncDirectory(target.parentFile)
    }

    /** Deletes members of [fileName] older than [keepMonths]. Returns how many were removed. */
    fun evict(
        aimiDir: File,
        fileName: String,
        keepMonths: Int,
        nowMs: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): Int {
        val dir = directory(aimiDir)
        if (!dir.isDirectory) return 0
        val base = Regex.escape(fileName.substringBeforeLast('.'))
        val ext = fileName.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) """\.gz""" else """\.${Regex.escape(ext)}\.gz"""
        val pattern = Regex("""^${base}_(\d{4})-(\d{2})$suffix$""")
        val oldest = YearMonth.from(Instant.ofEpochMilli(nowMs).atZone(zone)).minusMonths(keepMonths.toLong())
        var removed = 0
        dir.listFiles()?.sortedBy { it.name }?.forEach { candidate ->
            val match = pattern.find(candidate.name) ?: return@forEach
            val month = YearMonth.of(match.groupValues[1].toInt(), match.groupValues[2].toInt())
            if (month.isBefore(oldest) && candidate.delete()) removed++
        }
        return removed
    }
}
