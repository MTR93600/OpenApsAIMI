package app.aaps.plugins.aps.openAPSAIMI.retention

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException

/** State of one archive member file before it was appended to, so a failed pass can be undone. */
private data class ArchiveRollbackRecord(val target: File, val existedBefore: Boolean, val previousLength: Long)

/**
 * Runs one retention pass over one file.
 *
 * The expensive work - reading the file, compressing the old part, copying the tail - happens
 * without the file lock. Only the catch-up copy and the final move are held under [AimiFileLock],
 * and by then the catch-up is at most the lines written while the copy ran. Holding the lock only
 * guarantees mutual exclusion between lock holders (two retention passes, or a future writer that
 * also takes the lock); it does NOT, by itself, stop a line from being lost. A writer that never
 * takes this lock can still append in the microseconds between the catch-up read and the move, and
 * that line is then dropped when the move replaces the file. This is accepted: at most one
 * telemetry line, at most once per pass.
 *
 * The live file is never deleted and never truncated in place. It is replaced only by an atomic
 * move ([moveIntoPlace]), so a failure anywhere before that move leaves it exactly as it was. Right
 * before the catch-up, under the lock, the pass also checks that the file is still the one the plan
 * was built for (same file key, and at least as long as it was when the plan started). If some other
 * component replaced or rotated the file in the meantime, the pass aborts instead of overwriting the
 * wrong file, and rolls back any archive member it already wrote.
 *
 * Open rather than final so tests can pin free space, fail the archive step, force the move to
 * throw, and observe the moment the tail is copied.
 */
internal open class AimiRetentionManager(
    protected val aimiDir: File,
    protected val logger: (String) -> Unit,
    protected val now: () -> Long,
    // Defaults to `logger` so every existing (test) call site that only passes three positional
    // arguments keeps compiling and keeps its previous behaviour: everything routes to `logger`.
    // Real production wiring (AimiRetentionWorker) passes a distinct `warn` that reaches the AAPS
    // log at WARN, not INFO - see I2: this device's APS log is about 91% AUTOSENS and holds only a
    // few hours, so a daily janitor failure logged at INFO is never actually seen.
    protected val warn: (String) -> Unit = logger,
) {

    /** Free bytes usable for the temporary copy. Overridden in tests. */
    protected open fun freeSpaceOf(file: File): Long = file.usableSpace

    /** Called after the tail has been copied and before the lock is taken. Test seam only. */
    protected open fun onTailCopied(target: File) = Unit

    /**
     * Moves [tmp] into [file]'s place with one atomic filesystem move.
     *
     * Either [file] ends up fully replaced by [tmp], or, if this throws, [file] is left exactly as
     * it was: there is no in-between state where [file] is truncated or partly written, unlike a
     * plain copy-then-delete. Test seam so a failed move can be simulated.
     */
    protected open fun moveIntoPlace(tmp: File, file: File) {
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Appends one archive member. Test seam so a failing append can be simulated. */
    protected open fun appendArchiveMember(source: File, start: Long, endExclusive: Long, target: File, header: ByteArray?) {
        AimiArchive.appendMember(source, start, endExclusive, target, header)
    }

    /** Deletes [file]. Test seam so a failing delete can be simulated without filesystem games. */
    protected open fun deleteSource(file: File): Boolean = file.delete()

    /**
     * Archives everything outside [rule]'s window and rewrites [file] with the rest.
     *
     * @return true when the live file was rewritten.
     */
    fun trim(file: File, rule: AimiRetentionRule): Boolean {
        if (!file.isFile || file.length() == 0L) return false

        val plan = AimiCutPlanner.plan(file, rule, now())
        if (plan.cutOffset <= plan.headerBytes) {
            // The common case (nothing is out of window yet) is silent by design - it happens on
            // every managed file, every pass, until its hot window is actually exceeded. The one
            // case worth surfacing is a TRIM_TIME rule whose key never matched a single line: that
            // means the policy names the wrong key for this file, and the file is untrimmed forever,
            // silently, until someone happens to notice its size.
            if (!plan.timestampKeyMatched) {
                warn(
                    "retention: ${file.name} declares timestamp key \"${rule.timestampKey}\" but no line " +
                        "matched it, left untouched"
                )
            }
            return false
        }
        if (plan.headerBytes > MAX_HEADER_BYTES) {
            // .toInt() below would otherwise try to allocate the whole header in one array; an
            // OutOfMemoryError is an Error, not an Exception, so it would escape runOnce()'s catch.
            warn("retention: ${file.name} has a header line longer than $MAX_HEADER_BYTES bytes, trim skipped")
            return false
        }

        val markedEof = file.length()
        val fileKey = fileKeyOf(file)
        val tailBytes = markedEof - plan.cutOffset
        val archivedBytes = plan.cutOffset - plan.headerBytes
        // Gzip on this JSON/CSV data runs at roughly 10:1 (measured on the device), so /4 was about
        // 2.5x over. /8 still leaves a margin, but does not need ~650 MB free to trim a 2.10 GB file
        // on a nearly-full phone on the very first run, when there are no archives yet to evict.
        val neededBytes = tailBytes + archivedBytes / 8 + FREE_SPACE_MARGIN_BYTES
        if (freeSpaceOf(file) < neededBytes) {
            AimiArchive.evict(aimiDir, rule.fileName, rule.archiveMonths, now())
            if (freeSpaceOf(file) < neededBytes) {
                warn(
                    "retention: not enough free space to trim ${file.name}, needed ~$neededBytes bytes, " +
                        "had ${freeSpaceOf(file)}, left untouched"
                )
                return false
            }
        }

        // Keyed by absolute path, not a list: the planner can emit two ranges for the same month
        // when the month is not contiguous in the file, and both then resolve to the same archive
        // target. Each target must be rolled back exactly once, to the length it had before this
        // pass started - never to a length recorded after an earlier append in the same pass. A
        // put-if-absent on the target path keeps only the first (pre-pass) record for that target.
        val archiveRollback = LinkedHashMap<String, ArchiveRollbackRecord>()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        try {
            val header = if (rule.hasHeader && plan.headerBytes > 0L) readRange(file, 0L, plan.headerBytes) else null
            plan.ranges.forEach { range ->
                val target = AimiArchive.memberFile(aimiDir, rule.fileName, range.month)
                archiveRollback.putIfAbsent(target.absolutePath, recordArchiveState(target))
                appendArchiveMember(
                    source = file,
                    start = range.start,
                    endExclusive = range.endExclusive,
                    target = target,
                    header = header,
                )
            }

            FileOutputStream(tmp, false).use { out ->
                header?.let { out.write(it) }
                copyRange(file, plan.cutOffset, markedEof, out)
            }
            onTailCopied(tmp)

            val moved = AimiFileLock.withFile(file) {
                if (identityChanged(file, fileKey, markedEof)) {
                    warn("retention: ${file.name} changed during trim, skipped")
                    false
                } else {
                    FileOutputStream(tmp, true).use { out ->
                        copyRange(file, markedEof, file.length(), out)
                        out.fd.sync()
                    }
                    moveIntoPlace(tmp, file)
                    true
                }
            }
            if (!moved) {
                tmp.delete()
                rollbackArchive(archiveRollback.values)
                return false
            }
        } catch (e: Exception) {
            tmp.delete()
            rollbackArchive(archiveRollback.values)
            warn("retention: trim of ${file.name} failed, live file untouched: ${e.message}")
            return false
        }

        AimiArchive.evict(aimiDir, rule.fileName, rule.archiveMonths, now())
        return true
    }

    /** Called once per managed file before it is processed. Test seam only. */
    protected open fun onFileVisited(file: File) = Unit

    /** True when nothing has written [file] for [AimiRetentionPolicy.STALE_DAYS]. */
    fun isStale(file: File): Boolean =
        file.isFile && now() - file.lastModified() > AimiRetentionPolicy.STALE_DAYS * 24L * 60L * 60L * 1000L

    /**
     * Archives the whole of [file] and removes it. For files nothing writes any more.
     *
     * Gives the destructive step the same safety envelope [trim] already has around its own:
     * - a free-space guard, with a pre-evict retry, before the (possibly multi-minute) gzip pass
     *   starts. Without it, archiving a large file (the 2.10 GB decisions file, if it is ever stale)
     *   could drive free space to zero on a device that is also writing its own database.
     * - only `[0, markedEof)` is archived, and the source is deleted only after re-checking, under
     *   the lock, that the file is still the one this pass archived: the same [identityChanged]
     *   check [trim] uses (a different file at the same path, or one that shrank), plus a separate
     *   check that it did not simply grow past `markedEof` in the meantime. If either is true, the
     *   archive append is rolled back and nothing is deleted - a line appended, or a file replaced,
     *   while the archive was running must never be silently lost.
     * - the same rollback mechanism as [trim]: the archive target's pre-pass length is recorded
     *   before the append, and restored (or the target deleted, if it did not exist before) on any
     *   failure - a failed append, a failed identity check, or a failed delete. Without this, a
     *   failure partway through the append would leave a truncated gzip member, which makes every
     *   later member of that archive file unreadable.
     *
     * The source file is only ever removed after the archive append has fully succeeded and the
     * identity re-check has passed.
     *
     * When [rule] declares a `timestampKey` (the TRIM_TIME files; `retire` reaches these through
     * [consumeOverflow], and a `.overflow` file can hold up to its hard cap - up to 1 GB for the
     * decision file - so it can span many months), the content is split into one archive member per
     * calendar month, using the same scan [trim] uses for its own archived part. Naming the whole
     * thing after the run month, as a single-member retire always used to, would land months of
     * content in one file named for whichever month the janitor happened to run in; the external
     * viewer derives each member's coverage from its filename, so a request for an earlier month
     * would see nothing even though the data is sitting right there under the wrong name. A rule
     * with no usable key (TRIM_LINES files, and the dead RETIRE files, neither of which the viewer
     * reads) keeps the original single-member behaviour.
     */
    fun retire(file: File, rule: AimiRetentionRule): Boolean {
        if (!file.isFile || file.length() == 0L) return file.isFile && deleteSource(file)

        val markedEof = file.length()
        // Gzip on this data runs at roughly 10:1; /4 was about 2.5x over. See the matching comment
        // in trim() (I3).
        val estimatedArchiveBytes = markedEof / 8
        if (freeSpaceOf(file) < estimatedArchiveBytes + FREE_SPACE_MARGIN_BYTES) {
            AimiArchive.evict(aimiDir, rule.fileName, rule.archiveMonths, now())
            if (freeSpaceOf(file) < estimatedArchiveBytes + FREE_SPACE_MARGIN_BYTES) {
                warn(
                    "retention: not enough free space to retire ${file.name}, needed " +
                        "~${estimatedArchiveBytes + FREE_SPACE_MARGIN_BYTES} bytes, had ${freeSpaceOf(file)}, left untouched"
                )
                return false
            }
        }

        val fileKey = fileKeyOf(file)
        // Keyed by absolute path, not a list, for the same reason trim() keys its rollback map this
        // way: a month-split retire can in principle emit two ranges for the same month (out-of-order
        // timestamps), and both then resolve to the same archive target, which must be rolled back
        // exactly once, to its state before this pass.
        val archiveRollback = LinkedHashMap<String, ArchiveRollbackRecord>()
        return try {
            // Inside the try: retireMembers() can throw (see its own guard against a rule that
            // combines hasHeader with a timestampKey), and that must be handled the same way any
            // other failure in this pass is - warned and left in place, not an uncaught exception.
            val members = retireMembers(file, rule, markedEof)
            members.forEach { archiveRollback.putIfAbsent(it.target.absolutePath, recordArchiveState(it.target)) }
            members.forEach { member ->
                appendArchiveMember(
                    source = file,
                    start = member.start,
                    endExclusive = member.endExclusive,
                    target = member.target,
                    header = null,
                )
            }
            val deleted = AimiFileLock.withFile(file) {
                when {
                    identityChanged(file, fileKey, markedEof) -> {
                        warn("retention: ${file.name} changed during retire, archive rolled back, left in place")
                        false
                    }

                    file.length() > markedEof                 -> {
                        warn("retention: ${file.name} is being written again, archive rolled back, left for the next pass")
                        false
                    }

                    else                                       -> {
                        val removed = deleteSource(file)
                        if (!removed) warn("retention: retire of ${file.name} could not remove the source, archive rolled back")
                        removed
                    }
                }
            }
            if (deleted) {
                AimiArchive.evict(aimiDir, rule.fileName, rule.archiveMonths, now())
            } else {
                rollbackArchive(archiveRollback.values)
            }
            deleted
        } catch (e: Exception) {
            rollbackArchive(archiveRollback.values)
            warn("retention: retire of ${file.name} failed: ${e.message}")
            false
        }
    }

    /** One archive member a [retire] pass will write: which target file, and which byte range of [file]. */
    private data class RetireMember(val target: File, val start: Long, val endExclusive: Long)

    /**
     * Splits [file] into one [RetireMember] per calendar month when [rule] has a usable timestamp
     * key, falling back to a single member named for the current month otherwise - either because
     * the rule has no key at all (TRIM_LINES, dead RETIRE files), or because the key never matched a
     * single line despite being declared (the plan comes back with no ranges; the same defensive
     * fallback keeps the data from being silently skipped instead of archived).
     *
     * @throws IllegalStateException when [rule] combines `hasHeader` with a `timestampKey`. The
     * month-split path below reuses [AimiCutPlanner], which - like [trim] - starts every range
     * strictly after the header line when `hasHeader` is set, but this function always passes
     * `header = null` to `appendArchiveMember` (the single-member fallback does not need that
     * separate header: its one range covers the whole file from byte 0, header line included). Put
     * together, a rule with both flags set would silently lose its header row: the bytes never land
     * in any range, and nothing re-adds them. No rule in [AimiRetentionPolicy] combines them today,
     * so this is unreachable in production; the check exists so a future rule that does gets a loud,
     * caught-and-warned failure (see [retire]'s own `catch`) instead of a silently corrupted archive.
     */
    private fun retireMembers(file: File, rule: AimiRetentionRule, markedEof: Long): List<RetireMember> {
        check(!(rule.hasHeader && rule.timestampKey != null)) {
            "retention: ${rule.fileName} combines hasHeader with a timestampKey; retire()'s month-split " +
                "path always passes header = null and starts each range after the header line, so the " +
                "header row would be silently lost - add real support for this combination before using it"
        }
        if (rule.timestampKey != null) {
            // A "now" far enough in the future pushes the TRIM_TIME cutoff past any real timestamp
            // in the file, so every line that has a readable timestamp falls on the "archive" side
            // of the planner's cut and lands in `ranges`, split by month - exactly what a whole-file
            // archive needs, reusing the same scan `trim` already uses instead of a second
            // implementation. This used to be Long.MAX_VALUE, which AimiCutPlanner also uses to seed
            // the label for any line whose timestamp it could NOT extract (a blank or truncated
            // first line, or one whose key sits past AimiLineScanner.PREFIX_LIMIT) - and
            // Long.MAX_VALUE formats as "+292278994-08", a month label matching neither
            // AimiArchive.evict's regex (never reclaimed) nor the viewer's pattern (invisible). A
            // realistic far-future "now" avoids that for the common case; the regex check below is
            // the actual guarantee, covering whatever else might someday produce a bad label.
            val fakeNow = now() + FAR_FUTURE_MARGIN_MS
            val planningRule = rule.copy(op = AimiRetentionOp.TRIM_TIME)
            val plan = AimiCutPlanner.plan(file, planningRule, fakeNow)
            if (plan.ranges.isNotEmpty()) {
                // AimiLineScanner deliberately never reports a trailing line with no closing newline
                // (a write caught mid-line), so the planner's last range can end short of markedEof
                // by exactly that many bytes. Unlike trim(), which leaves such a line on the live
                // side of the cut, retire() has no live file left afterwards for it to survive in -
                // its own KDoc promises the whole file is archived - so the last range is extended
                // to markedEof here to cover it too.
                val ranges = plan.ranges.toMutableList()
                ranges[ranges.lastIndex] = ranges.last().copy(endExclusive = markedEof)
                val fallbackLabel = monthOfNow()
                return ranges.map { range ->
                    // Reject labels dated after the run month: the far-future seed in retireMembers
                    // can produce them, but they are never evicted and never visible to the viewer.
                    val label = if (MONTH_LABEL.matches(range.month) && range.month <= fallbackLabel) range.month else fallbackLabel
                    RetireMember(AimiArchive.memberFile(aimiDir, rule.fileName, label), range.start, range.endExclusive)
                }
            }
        }
        return listOf(RetireMember(AimiArchive.memberFile(aimiDir, rule.fileName, monthOfNow()), 0L, markedEof))
    }

    /** Deletes generated backup copies older than [AimiRetentionPolicy.DROP_AFTER_DAYS]. */
    fun dropOrphans(): Int {
        val cutoff = now() - AimiRetentionPolicy.DROP_AFTER_DAYS * 24L * 60L * 60L * 1000L
        var dropped = 0
        aimiDir.listFiles()?.forEach { candidate ->
            if (!candidate.isFile) return@forEach
            if (AimiRetentionPolicy.DROP_GLOBS.none { it.matches(candidate.name) }) return@forEach
            if (candidate.lastModified() > cutoff) return@forEach
            val size = candidate.length()
            if (candidate.delete()) {
                dropped++
                logger("retention: dropped orphan ${candidate.name} ($size bytes)")
            }
        }
        return dropped
    }

    /** Archives and removes any `<name>.overflow` left behind by the append guard. */
    fun consumeOverflow(rule: AimiRetentionRule): Int {
        val overflow = File(aimiDir, rule.fileName + AimiRetentionPolicy.OVERFLOW_SUFFIX)
        if (!overflow.isFile) return 0
        return if (retire(overflow, rule)) 1 else 0
    }

    /**
     * One full pass over the policy. Returns how many files were changed.
     *
     * Starts with [recoverInterruptedArchives], before anything else touches the archive directory:
     * a truncated gzip member left by a process kill mid-append must be undone before a new append
     * could be layered on top of it, or evict() could delete a member around it, or anything else in
     * this pass reads the directory and sees the truncated file.
     *
     * Catches [Exception] per rule and for the orphan sweep, so one broken file cannot stop the
     * rest of the pass. [CancellationException] is rethrown rather than swallowed: this runs inside
     * a coroutine worker, and swallowing a cancellation would let the sweep keep deleting files after
     * the worker was told to stop. [Error] is never caught.
     */
    fun runOnce(): Int {
        var changed = 0
        try {
            recoverInterruptedArchives()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("retention: recovery of interrupted archives failed: ${e.message}")
        }
        val ordered = AimiRetentionPolicy.RULES.sortedByDescending { File(aimiDir, it.fileName).length() }
        ordered.forEach { rule ->
            val file = File(aimiDir, rule.fileName)
            try {
                onFileVisited(file)
                changed += consumeOverflow(rule)
                when {
                    rule.op == AimiRetentionOp.RETIRE -> if (retire(file, rule)) changed++
                    isStale(file)                     -> if (retire(file, rule)) changed++
                    else                              -> if (trim(file, rule)) changed++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                warn("retention: ${rule.fileName} failed: ${e.message}")
            }
        }
        try {
            changed += dropOrphans()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warn("retention: orphan sweep failed: ${e.message}")
        }
        return changed
    }

    /**
     * Undoes any archive append that was interrupted mid-write, using the `.partial` markers
     * [AimiArchive.appendMember] leaves behind - see its kdoc for why a truncated gzip member is
     * otherwise unrecoverable (it makes every later member of the same archive file unreadable).
     *
     * Reuses [rollbackArchive], the same code an in-process failure already uses to undo a partial
     * append: from the archive's point of view, a process kill mid-append and an in-process exception
     * mid-append need the exact same fix - restore the target to the length it had before this
     * append started, or delete it if it did not exist yet.
     *
     * Returns how many interrupted appends were found and undone.
     */
    fun recoverInterruptedArchives(): Int {
        val interrupted = AimiArchive.findInterruptedAppends(aimiDir)
        if (interrupted.isEmpty()) return 0
        val records = interrupted.map { ArchiveRollbackRecord(it.target, it.previousLength > 0L, it.previousLength) }
        rollbackArchive(records)
        interrupted.forEach { it.marker.delete() }
        return interrupted.size
    }

    private fun monthOfNow(): String =
        DateTimeFormatter.ofPattern("yyyy-MM").format(Instant.ofEpochMilli(now()).atZone(ZoneId.systemDefault()))

    /** A filesystem identity for [file], or null when the filesystem does not provide one. */
    private fun fileKeyOf(file: File): Any? =
        try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
        } catch (e: IOException) {
            null
        }

    /**
     * True when [file] is no longer the file the plan was built for: shorter than [markedEof] (it
     * was replaced by something smaller, or truncated), or carrying a different file key (it was
     * replaced by a new file at the same path). When either key is null, only the length is trusted.
     */
    private fun identityChanged(file: File, originalKey: Any?, markedEof: Long): Boolean {
        if (file.length() < markedEof) return true
        val currentKey = fileKeyOf(file)
        return originalKey != null && currentKey != null && originalKey != currentKey
    }

    private fun recordArchiveState(target: File): ArchiveRollbackRecord {
        val existed = target.exists()
        return ArchiveRollbackRecord(target, existed, if (existed) target.length() else 0L)
    }

    /**
     * Undoes every archive append made during a pass that did not complete.
     *
     * Two defensive checks beyond the obvious "restore [ArchiveRollbackRecord.previousLength]":
     * - [ArchiveRollbackRecord.target] is re-checked for existence right here, not trusted from
     *   [ArchiveRollbackRecord.existedBefore] alone: a target this record believes existed can have
     *   been deleted out of band since (or, for [recoverInterruptedArchives], the record only
     *   approximates "existed before" from the marker's recorded length in the first place). Without
     *   this, `RandomAccessFile(target, "rw")` would happily *create* a missing target and then
     *   zero-pad it out to [ArchiveRollbackRecord.previousLength] - a fabricated archive member that
     *   is worse than no member at all, since `GZIPInputStream` rejects it outright instead of the
     *   caller simply finding nothing there.
     * - the restored length is clamped to never exceed the target's *current* length. A record's
     *   [ArchiveRollbackRecord.previousLength] is only valid against the target as it stood right
     *   after this record was taken; if some other rollback in the same pass (or an earlier pass)
     *   already shrank the same target further, blindly calling `setLength(previousLength)` would
     *   EXTEND it back out, padding the gap with zero bytes - `RandomAccessFile.setLength` grows a
     *   file shorter than the requested length rather than refusing. That padding sits inside what
     *   was a valid gzip stream and makes every member after it unreadable. Clamping makes this a
     *   no-op whenever the target is already at or below where this record wants it.
     */
    private fun rollbackArchive(records: Collection<ArchiveRollbackRecord>) {
        records.forEach { record ->
            try {
                if (!record.existedBefore || !record.target.exists()) {
                    record.target.delete()
                } else {
                    RandomAccessFile(record.target, "rw").use { raf ->
                        raf.setLength(minOf(record.previousLength, raf.length()))
                    }
                }
            } catch (e: IOException) {
                warn("retention: failed to roll back archive member ${record.target.name}: ${e.message}")
            }
        }
    }

    private fun readRange(file: File, start: Long, endExclusive: Long): ByteArray {
        val size = (endExclusive - start).toInt()
        val out = ByteArray(size)
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            input.readFully(out)
        }
        return out
    }

    private fun copyRange(file: File, start: Long, endExclusive: Long, out: FileOutputStream) {
        if (endExclusive <= start) return
        RandomAccessFile(file, "r").use { input ->
            input.seek(start)
            val buffer = ByteArray(64 * 1024)
            var remaining = endExclusive - start
            while (remaining > 0) {
                val want = minOf(remaining, buffer.size.toLong()).toInt()
                val read = input.read(buffer, 0, want)
                if (read <= 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
        }
    }

    private companion object {

        const val TMP_SUFFIX = ".tmp"

        /** Head room kept free so the swap never fills the volume completely. */
        const val FREE_SPACE_MARGIN_BYTES = 32L * 1024 * 1024

        /**
         * The largest header line [readRange] will allocate whole. Above this, [trim] skips the file
         * rather than let `(endExclusive - start).toInt()` size an array to an attacker- or
         * corruption-sized line: `OutOfMemoryError` is an [Error], not an [Exception], so it would
         * escape [runOnce]'s per-rule `catch (e: Exception)` and take the whole pass down with it.
         */
        const val MAX_HEADER_BYTES = 64L * 1024

        /**
         * Stands in for "now" when [retireMembers] asks [AimiCutPlanner] to archive a whole file:
         * far enough past any real timestamp to keep every line on the "archive" side of the cut,
         * but still a realistic, formattable date - see [retireMembers] for why that matters (G5).
         */
        const val FAR_FUTURE_MARGIN_MS = 100L * 365 * 24 * 60 * 60 * 1000

        /** What a real archive member's month label looks like. See [retireMembers] (G5). */
        val MONTH_LABEL = Regex("""\d{4}-\d{2}""")
    }
}
