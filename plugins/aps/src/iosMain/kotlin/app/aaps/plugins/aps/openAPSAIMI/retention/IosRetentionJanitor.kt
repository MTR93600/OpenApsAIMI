package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiLocalFiles
import app.aaps.plugins.aps.openAPSAIMI.utils.aimiLocalFiles
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToEndOfFile
import platform.Foundation.seekToFileOffset
import platform.Foundation.synchronizeFile
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.writeData

/**
 * iOS janitor for the telemetry retention system.
 *
 * Drains the `<name>.overflow` files that [IosAmiAppendCap] rotates aside when a managed
 * file passes its hard cap: each overflow file is compressed into the `archive/` directory
 * and only then deleted. The source is never deleted unless its bytes were verifiably
 * archived first; any failure leaves the overflow file in place for the next pass.
 *
 * Archive layout matches Android's `AimiArchive` exactly (`archive/<base>_<yyyy-MM>.<ext>.gz`,
 * concatenated gzip members), so archives stay readable by the same tools. For rules with a
 * timestamp key the content is split into one member set per calendar month, the same split
 * Android's retire path makes: the external viewer derives each member's coverage from its
 * filename, so archiving months of content under the run month's name would hide it.
 *
 * Crash safety mirrors the Android protocol: a `.partial` sidecar marker records the target's
 * length before appending, is removed only after the data is synced, and a surviving marker
 * makes the next pass truncate the target back before anything else touches the archive.
 *
 * Deliberately not ported (future work): `trim` (rewriting live files to their hot window),
 * `retire` of stale files, and the orphan sweep. Those never delete unarchived data either,
 * but they are a separate, larger piece of machinery.
 */
internal class IosRetentionJanitor(
    private val aimiDir: String,
    private val logger: (String) -> Unit = {},
    private val warn: (String) -> Unit = logger,
    private val now: () -> Long = ::aimiWallClockMs,
) {

    private val io: AimiLocalFiles = aimiLocalFiles()

    /** One full pass: recover interrupted archives, drain overflows, evict old months. */
    fun runOnce(): Int {
        var changed = 0
        try {
            changed += recoverInterruptedArchives()
        } catch (e: Exception) {
            warn("retention: recovery of interrupted archives failed: ${e.message}")
        }
        for (rule in AimiRetentionPolicy.RULES) {
            try {
                changed += consumeOverflow(rule)
                changed += evict(rule)
            } catch (e: Exception) {
                warn("retention: ${rule.fileName} failed: ${e.message}")
            }
        }
        return changed
    }

    /**
     * Archives `<name>.overflow` for [rule] and removes it. Returns 1 when the overflow file
     * is gone, 0 when there was nothing to do or the archival failed.
     *
     * A failed archival never deletes: the overflow file stays for the next pass.
     */
    fun consumeOverflow(rule: AimiRetentionRule): Int {
        val overflowPath = "$aimiDir/${rule.fileName}${AimiRetentionPolicy.OVERFLOW_SUFFIX}"
        if (!io.exists(overflowPath)) return 0
        if (io.length(overflowPath) == 0L) {
            // Nothing to archive; mirrors Android retire() deleting an empty file.
            return if (io.delete(overflowPath)) 1 else 0
        }
        if (!archiveOverflow(overflowPath, rule)) return 0
        return if (io.delete(overflowPath)) {
            logger("retention: archived and removed ${rule.fileName}${AimiRetentionPolicy.OVERFLOW_SUFFIX}")
            1
        } else {
            // Archived but the source survived: the next pass will archive it again
            // (harmless duplicates) and retry the delete.
            warn("retention: archived ${rule.fileName} overflow but could not delete the source")
            0
        }
    }

    /**
     * Undoes archive appends that a kill cut short, using the `.partial` markers the append
     * path leaves behind. Same repair Android's `recoverInterruptedArchives` performs: restore
     * the target to the recorded length (clamped, never extended), or delete it when it did
     * not exist before the interrupted append. Malformed markers are left alone, never guessed.
     */
    fun recoverInterruptedArchives(): Int {
        val archiveDir = "$aimiDir/archive"
        val names = listDirectory(archiveDir) ?: return 0
        var recovered = 0
        for (name in names) {
            if (!name.endsWith(PARTIAL_SUFFIX)) continue
            val markerPath = "$archiveDir/$name"
            val targetPath = "$archiveDir/${name.removeSuffix(PARTIAL_SUFFIX)}"
            val previousLength = io.readText(markerPath)?.trim()?.toLongOrNull()
            if (previousLength == null) {
                warn("retention: unreadable partial marker $name, left for the next pass")
                continue
            }
            if (previousLength <= 0L || !io.exists(targetPath)) {
                io.delete(targetPath)
            } else {
                truncate(targetPath, minOf(previousLength, io.length(targetPath)))
            }
            io.delete(markerPath)
            recovered++
            logger("retention: recovered interrupted archive append")
        }
        return recovered
    }

    /**
     * Compresses the whole overflow file into the archive, one gzip member set per month.
     * Returns true only when every touched archive member file verifiably grew.
     */
    private fun archiveOverflow(overflowPath: String, rule: AimiRetentionRule): Boolean {
        val archiveDir = "$aimiDir/archive"
        if (!io.createDirectories(archiveDir)) {
            warn("retention: cannot create archive directory")
            return false
        }
        val spansByMonth = scanOverflow(overflowPath, rule.timestampKey) ?: return false
        val preLengths = mutableMapOf<String, Long>()
        for ((month, spans) in spansByMonth) {
            val target = "$archiveDir/${memberName(rule.fileName, month)}"
            val preLength = io.length(target)
            preLengths[target] = preLength
            if (!writePartialMarker(target, preLength)) return false
            if (!appendSpans(target, overflowPath, spans)) return false
            removePartialMarker(target)
        }
        for ((target, preLength) in preLengths) {
            if (!io.exists(target) || io.length(target) <= preLength) {
                warn("retention: archive did not grow for $target, overflow left in place")
                return false
            }
        }
        return true
    }

    /**
     * Maps the overflow file to byte spans per calendar month.
     *
     * Rules with a timestamp key get one span set per month, extracted from each line's first
     * [PREFIX_LIMIT] bytes with [AimiTimestampKey] (the keys sit at the start of the line, so a
     * full JSON parse per line would dominate the pass). Lines with no usable timestamp fall
     * back to the current month, the same defensive fallback Android's retire uses instead of
     * silently skipping data. Rules without a key (line-count-trimmed CSVs) archive as a single
     * span under the current month, which is also Android's behavior for them.
     *
     * The trailing line without a closing newline is included: unlike trim, which leaves such a
     * line on the live side of the cut, this pass has no live file left afterwards.
     *
     * Returns null when the file cannot be read; the caller then leaves it in place.
     */
    private fun scanOverflow(path: String, timestampKey: String?): Map<String, List<ByteSpan>>? {
        val fileLength = io.length(path)
        if (fileLength <= 0L) return null
        val fallbackMonth = currentMonth()
        if (timestampKey == null) {
            return mapOf(fallbackMonth to listOf(ByteSpan(0L, fileLength)))
        }
        val extractor = AimiTimestampKey(timestampKey)
        val spans = LinkedHashMap<String, MutableList<ByteSpan>>()

        var openMonth: String? = null
        var openStart = 0L
        fun closeSpan(endExclusive: Long) {
            val month = openMonth ?: return
            if (endExclusive > openStart) {
                spans.getOrPut(month) { mutableListOf() }.add(ByteSpan(openStart, endExclusive))
            }
            openMonth = null
        }

        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
        try {
            var fileOffset = 0L
            var lineStart = 0L
            val prefix = ByteArray(PREFIX_LIMIT)
            var prefixLen = 0
            while (true) {
                val chunk = handle.readDataOfLength(SCAN_CHUNK_BYTES.toULong()).toByteArray()
                if (chunk.isEmpty()) break
                var i = 0
                while (i < chunk.size) {
                    if (chunk[i] == NEWLINE_BYTE) {
                        val month = extractor.extract(prefix.copyOf(prefixLen))
                            ?.let { monthOf(it) }
                            ?.takeIf { MONTH_LABEL.matches(it) && it <= fallbackMonth }
                            ?: fallbackMonth
                        if (month != openMonth) {
                            closeSpan(lineStart)
                            openMonth = month
                            openStart = lineStart
                        }
                        lineStart = fileOffset + i + 1
                        prefixLen = 0
                    } else if (prefixLen < PREFIX_LIMIT) {
                        prefix[prefixLen++] = chunk[i]
                    }
                    i++
                }
                fileOffset += chunk.size
            }
            if (lineStart < fileLength) {
                val month = extractor.extract(prefix.copyOf(prefixLen))
                    ?.let { monthOf(it) }
                    ?.takeIf { MONTH_LABEL.matches(it) && it <= fallbackMonth }
                    ?: fallbackMonth
                if (month != openMonth) {
                    closeSpan(lineStart)
                    openMonth = month
                    openStart = lineStart
                }
            }
            closeSpan(fileLength)
        } finally {
            handle.closeFile()
        }
        return spans.ifEmpty { null }
    }

    /**
     * Streams [spans] of the source file into [target] as gzip members, one member per
     * [MEMBER_CHUNK_BYTES]. Concatenated members are valid gzip and read back as one stream.
     * The target is synced before returning so a later delete of the source cannot race data
     * still sitting in the page cache.
     */
    private fun appendSpans(target: String, sourcePath: String, spans: List<ByteSpan>): Boolean {
        if (!io.exists(target) && !io.writeText(target, "")) return false
        val writeHandle = NSFileHandle.fileHandleForWritingAtPath(target) ?: return false
        try {
            val readHandle = NSFileHandle.fileHandleForReadingAtPath(sourcePath) ?: return false
            try {
                writeHandle.seekToEndOfFile()
                for (span in spans) {
                    var remaining = span.endExclusive - span.start
                    readHandle.seekToFileOffset(span.start.toULong())
                    while (remaining > 0) {
                        val want = minOf(remaining, MEMBER_CHUNK_BYTES).toInt()
                        val chunk = readHandle.readDataOfLength(want.toULong()).toByteArray()
                        // Short read means the file shrank mid-pass: fail closed, keep everything.
                        if (chunk.isEmpty()) return false
                        val member = IosGzip.member(chunk) ?: return false
                        writeHandle.writeData(member.toNSData())
                        remaining -= chunk.size
                    }
                }
                writeHandle.synchronizeFile()
            } finally {
                readHandle.closeFile()
            }
        } finally {
            writeHandle.closeFile()
        }
        return true
    }

    /**
     * Deletes archive members older than the rule's [AimiRetentionRule.archiveMonths].
     * Matches Android `AimiArchive.evict`: only files matching the member name pattern go,
     * anything else in the directory is left alone.
     */
    private fun evict(rule: AimiRetentionRule): Int {
        if (rule.archiveMonths <= 0) return 0
        val archiveDir = "$aimiDir/archive"
        val names = listDirectory(archiveDir) ?: return 0
        val base = Regex.escape(rule.fileName.substringBeforeLast('.'))
        val ext = rule.fileName.substringAfterLast('.', "")
        val suffix = if (ext.isEmpty()) """\.gz""" else """\.${Regex.escape(ext)}\.gz"""
        val pattern = Regex("""^${base}_(\d{4})-(\d{2})$suffix$""")
        val oldestKept = monthMinus(currentMonth(), rule.archiveMonths)
        var removed = 0
        for (name in names.sorted()) {
            val match = pattern.find(name) ?: continue
            val month = "${match.groupValues[1]}-${match.groupValues[2]}"
            if (month < oldestKept && io.delete("$archiveDir/$name")) removed++
        }
        return removed
    }

    /** `AIMI_Decisions.jsonl` + `2026-09` becomes `AIMI_Decisions_2026-09.jsonl.gz`. */
    private fun memberName(fileName: String, month: String): String {
        val base = fileName.substringBeforeLast('.')
        val ext = fileName.substringAfterLast('.', "")
        return if (ext.isEmpty()) "${base}_${month}.gz" else "${base}_${month}.$ext.gz"
    }

    private fun writePartialMarker(target: String, previousLength: Long): Boolean =
        io.writeText("$target$PARTIAL_SUFFIX", previousLength.toString())

    private fun removePartialMarker(target: String) {
        io.delete("$target$PARTIAL_SUFFIX")
    }

    private fun listDirectory(dir: String): List<String>? =
        try {
            @OptIn(ExperimentalForeignApi::class)
            NSFileManager.defaultManager.contentsOfDirectoryAtPath(dir, null) as? List<String>
        } catch (e: Exception) {
            null
        }

    private fun truncate(path: String, length: Long): Boolean {
        val handle = NSFileHandle.fileHandleForUpdatingAtPath(path) ?: return false
        return try {
            handle.truncateFileAtOffset(length.toULong())
            true
        } catch (e: Exception) {
            false
        } finally {
            handle.closeFile()
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun monthOf(epochMs: Long): String {
        val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
        val components = NSCalendar.currentCalendar.components(
            NSCalendarUnitYear or NSCalendarUnitMonth,
            fromDate = date,
        )
        val year = components?.year ?: 1970
        val month = components?.month ?: 1
        return "$year-${month.toString().padStart(2, '0')}"
    }

    private fun currentMonth(): String = monthOf(now())

    private fun monthMinus(month: String, months: Int): String {
        val total = month.substring(0, 4).toInt() * 12 + month.substring(5, 7).toInt() - 1 - months
        return "${total / 12}-${(total % 12 + 1).toString().padStart(2, '0')}"
    }

    private fun NSData.toByteArray(): ByteArray =
        bytes?.readBytes(length.toInt()) ?: ByteArray(0)

    @OptIn(ExperimentalForeignApi::class)
    private fun ByteArray.toNSData(): NSData = memScoped {
        // dataWithBytes:length: copies, so the NSData outlives this scope.
        NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
    }

    private data class ByteSpan(val start: Long, val endExclusive: Long)

    companion object {

        /** Suffix of the crash-recovery sidecar, same as Android's `AimiArchive`. */
        const val PARTIAL_SUFFIX = ".partial"

        /** Read window for the month scan; lines are only inspected, never held. */
        const val SCAN_CHUNK_BYTES = 64L * 1024

        /** One gzip member per chunk; concatenated members read back as one stream. */
        const val MEMBER_CHUNK_BYTES = 1024L * 1024

        /** How many leading bytes of a line the timestamp extractor sees. */
        const val PREFIX_LIMIT = 4096

        const val NEWLINE_BYTE = '\n'.code.toByte()

        /** What a real archive member's month label looks like. */
        val MONTH_LABEL = Regex("""\d{4}-\d{2}""")
    }
}
