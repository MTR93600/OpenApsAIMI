package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.cinterop.readBytes
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSize
import platform.Foundation.NSFileSystemFileNumber
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSNumber
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.fileHandleForWritingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToEndOfFile
import platform.Foundation.seekToFileOffset
import platform.Foundation.synchronizeFile
import platform.Foundation.truncateFileAtOffset
import platform.Foundation.writeData
import platform.posix.O_RDONLY
import platform.posix.close
import platform.posix.fsync
import platform.posix.open

/** iOS [AimiRetentionFs]: Foundation (`NSFileManager`, `NSFileHandle`) + [IosGzip]. */
@OptIn(ExperimentalForeignApi::class)
internal actual class AimiByteReader actual constructor(path: AimiPath) {
    private val handle: NSFileHandle =
        NSFileHandle.fileHandleForReadingAtPath(path.value)
            ?: throw IllegalStateException("retention: cannot open ${path.value} for read")
    private val fileLength: Long =
        (NSFileManager.defaultManager.attributesOfItemAtPath(path.value, null)
            ?.get(NSFileSize) as? NSNumber)?.longLongValue ?: 0L

    actual fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= fileLength) return -1
        handle.seekToFileOffset(position.toULong())
        var total = 0
        while (total < length) {
            val chunk = handle.readDataOfLength((length - total).toULong())
            val n = chunk.length.toInt()
            if (n <= 0) break
            chunk.toByteArray().copyInto(buffer, offset + total, 0, n)
            total += n
        }
        return if (total == 0) -1 else total
    }

    actual fun length(): Long = fileLength

    actual fun close() {
        handle.closeFile()
    }
}

internal actual class AimiByteWriter actual constructor(path: AimiPath, append: Boolean) {
    private val handle: NSFileHandle = run {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path.value)) {
            manager.createFileAtPath(path.value, contents = null, attributes = null)
        }
        val h = NSFileHandle.fileHandleForWritingAtPath(path.value)
            ?: throw IllegalStateException("retention: cannot open ${path.value} for write")
        if (append) h.seekToEndOfFile() else h.truncateFileAtOffset(0u)
        h
    }

    actual fun write(buffer: ByteArray, offset: Int, length: Int) {
        val slice =
            if (offset == 0 && length == buffer.size) buffer
            else buffer.copyOfRange(offset, offset + length)
        handle.writeData(slice.toNSData())
    }

    actual fun sync() {
        handle.synchronizeFile()
    }

    actual fun close() {
        handle.closeFile()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aimiFsListFiles(dir: AimiPath): List<AimiDirEntry> {
    val manager = NSFileManager.defaultManager
    val names = manager.contentsOfDirectoryAtPath(dir.value, error = null) ?: return emptyList()
    val prefix = dir.value.trimEnd('/')
    return names.mapNotNull { raw ->
        val name = raw as? String ?: return@mapNotNull null
        val full = "$prefix/$name"
        val type = manager.attributesOfItemAtPath(full, null)?.get(NSFileType) as? String
        if (type == NSFileTypeRegular) AimiDirEntry(AimiPath(full), name) else null
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aimiFsMoveAtomic(source: AimiPath, target: AimiPath) {
    val manager = NSFileManager.defaultManager
    memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        if (manager.fileExistsAtPath(target.value) &&
            !manager.removeItemAtPath(target.value, error.ptr)
        ) {
            throw IllegalStateException(
                "retention: cannot replace ${target.value}: ${error.value?.localizedDescription}",
            )
        }
        if (!manager.moveItemAtPath(source.value, toPath = target.value, error = error.ptr)) {
            throw IllegalStateException(
                "retention: atomic move failed ${source.value} -> ${target.value}: " +
                    error.value?.localizedDescription,
            )
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aimiFsRename(source: AimiPath, to: AimiPath): Boolean {
    val manager = NSFileManager.defaultManager
    if (manager.fileExistsAtPath(to.value)) manager.removeItemAtPath(to.value, null)
    return manager.moveItemAtPath(source.value, toPath = to.value, error = null)
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aimiFsFreeBytes(path: AimiPath): Long {
    val attrs = NSFileManager.defaultManager.attributesOfFileSystemForPath(path.value, null)
    return (attrs?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue ?: 0L
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aimiFsIdentityKey(path: AimiPath): String? {
    val attrs = NSFileManager.defaultManager.attributesOfItemAtPath(path.value, null) ?: return null
    return (attrs[NSFileSystemFileNumber] as? NSNumber)?.stringValue
}

internal actual fun aimiFsTruncate(path: AimiPath, length: Long) {
    val handle = NSFileHandle.fileHandleForWritingAtPath(path.value)
        ?: throw IllegalStateException("retention: cannot open ${path.value} for truncate")
    try {
        handle.truncateFileAtOffset(length.toULong())
    } finally {
        handle.closeFile()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun aimiFsSyncParentDir(path: AimiPath) {
    // Best-effort: filesystems that refuse are skipped, never throws.
    val parent = path.value.substringBeforeLast('/', "")
    if (parent.isEmpty()) return
    runCatching {
        val fd = open(parent, O_RDONLY)
        if (fd >= 0) {
            try {
                fsync(fd)
            } finally {
                close(fd)
            }
        }
    }
}

internal actual fun aimiGzipAppendMember(
    source: AimiPath,
    start: Long,
    endExclusive: Long,
    target: AimiPath,
    header: ByteArray?,
) {
    require(endExclusive >= start) { "retention: invalid gzip range [$start, $endExclusive)" }
    val rangeLength = endExclusive - start
    require(rangeLength <= Int.MAX_VALUE) { "retention: gzip range too large: $rangeLength" }
    val reader = AimiByteReader(source)
    val data = try {
        val buf = ByteArray(rangeLength.toInt())
        var filled = 0
        while (filled < buf.size) {
            val n = reader.readAt(start + filled, buf, filled, buf.size - filled)
            if (n < 0) break
            filled += n
        }
        if (filled != buf.size) throw IllegalStateException("retention: short read of ${source.value}")
        buf
    } finally {
        reader.close()
    }
    val member = IosGzip.member(data)
        ?: throw IllegalStateException("retention: gzip compression failed for ${source.value}")
    val manager = NSFileManager.defaultManager
    if (!manager.fileExistsAtPath(target.value)) {
        manager.createFileAtPath(target.value, contents = null, attributes = null)
    }
    val handle = NSFileHandle.fileHandleForWritingAtPath(target.value)
        ?: throw IllegalStateException("retention: cannot open ${target.value} for append")
    try {
        handle.seekToEndOfFile()
        if (header != null) handle.writeData(header.toNSData())
        handle.writeData(member.toNSData())
        handle.synchronizeFile()
    } finally {
        handle.closeFile()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ByteArray.toNSData(): NSData = memScoped {
    // dataWithBytes:length: copies, so the NSData outlives this scope.
    NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong())
}

@OptIn(ExperimentalForeignApi::class)
private fun NSData.toByteArray(): ByteArray =
    bytes?.readBytes(length.toInt()) ?: ByteArray(0)
