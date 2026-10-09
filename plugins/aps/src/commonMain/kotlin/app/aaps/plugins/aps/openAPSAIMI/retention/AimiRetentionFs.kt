package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * Byte-level file operations the retention janitor needs, as the platform provides them.
 *
 * [AimiStorage] is the shared file policy - names, text reads, safe writes that answer `false`
 * instead of throwing - but the janitor works below that level: it copies byte ranges between
 * files, moves a finished file into place atomically, truncates an archive target back after a
 * failed append, and compresses ranges with gzip. None of that fits the text-oriented storage
 * contract, so each operation is declared here as an `expect` and implemented per platform (on
 * Android with `java.io`, on iOS with Foundation) rather than guessed in shared code.
 *
 * Errors: one-shot functions throw on failure unless documented otherwise. The callers
 * ([AimiRetentionManager], [AimiArchive]) already run every pass under a catch that warns and
 * leaves the live file untouched, so a throw is the fail-closed signal, the same role
 * `IOException` played before the port.
 */
internal expect class AimiByteReader(path: AimiPath) {

    /**
     * Reads up to [length] bytes at [position] into [buffer] at [offset].
     * Returns the bytes read, or -1 when [position] is at or past the end.
     */
    fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /** Current length in bytes. */
    fun length(): Long

    fun close()
}

/**
 * A truncating or appending byte sink.
 *
 * Closed explicitly with [close] rather than `use`, so the seam stays tiny; every call site
 * closes in a `finally`.
 */
internal expect class AimiByteWriter(path: AimiPath, append: Boolean) {

    fun write(buffer: ByteArray, offset: Int, length: Int)

    /** Flushes through the OS page cache to the device. */
    fun sync()

    fun close()
}

/** One regular file directly inside a directory: its path, and its platform file name. */
internal data class AimiDirEntry(val path: AimiPath, val name: String)

/**
 * The regular files directly inside [dir], no recursion. Empty when [dir] is missing or
 * unreadable. [AimiDirEntry.name] is supplied by the platform so shared code can match file names
 * without taking the opaque [AimiPath] apart.
 */
internal expect fun aimiFsListFiles(dir: AimiPath): List<AimiDirEntry>

/**
 * Moves [source] onto [target] atomically, replacing it. Throws when the move cannot be done:
 * the caller treats that as "leave the live file exactly as it was".
 */
internal expect fun aimiFsMoveAtomic(source: AimiPath, target: AimiPath)

/** Renames [source] onto [to], replacing it. Answers `false` instead of throwing on failure. */
internal expect fun aimiFsRename(source: AimiPath, to: AimiPath): Boolean

/** Free bytes usable on [path]'s volume. */
internal expect fun aimiFsFreeBytes(path: AimiPath): Long

/**
 * A filesystem identity for [path] (inode / file key), or null when the platform has none or the
 * file cannot be statted. Two reads naming the same file must answer equal strings.
 */
internal expect fun aimiFsIdentityKey(path: AimiPath): String?

/** Sets [path] to [length] bytes. The janitor only ever shrinks, never grows. */
internal expect fun aimiFsTruncate(path: AimiPath, length: Long)

/**
 * Best-effort fsync of the directory that holds [path], so a new file's *presence* survives a
 * power cut, not just its content. Never throws: filesystems that refuse are simply skipped.
 */
internal expect fun aimiFsSyncParentDir(path: AimiPath)

/**
 * Appends `[start, endExclusive)` of [source] to [target] as one gzip member, writing [header]
 * first when it is not null, then fsyncs [target] so the member is on the device before this
 * returns. Creates [target] when it does not exist yet; its parents must already exist.
 */
internal expect fun aimiGzipAppendMember(
    source: AimiPath,
    start: Long,
    endExclusive: Long,
    target: AimiPath,
    header: ByteArray?,
)
