package app.aaps.plugins.aps.openAPSAIMI.retention

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.zip.GZIPOutputStream

/** Android [AimiRetentionFs]: `java.io` + `java.nio` + `java.util.zip`. */
internal actual class AimiByteReader actual constructor(path: AimiPath) {
    private val raf = RandomAccessFile(File(path.value), "r")

    actual fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        raf.seek(position)
        return raf.read(buffer, offset, length)
    }

    actual fun length(): Long = raf.length()

    actual fun close() = raf.close()
}

internal actual class AimiByteWriter actual constructor(path: AimiPath, append: Boolean) {
    private val raf = RandomAccessFile(File(path.value), "rw").also {
        if (append) it.seek(it.length()) else it.setLength(0)
    }

    actual fun write(buffer: ByteArray, offset: Int, length: Int) = raf.write(buffer, offset, length)

    actual fun sync() = raf.fd.sync()

    actual fun close() = raf.close()
}

internal actual fun aimiFsListFiles(dir: AimiPath): List<AimiDirEntry> =
    File(dir.value).listFiles()
        ?.filter { it.isFile }
        ?.map { AimiDirEntry(AimiPath(it.absolutePath), it.name) }
        ?: emptyList()

internal actual fun aimiFsMoveAtomic(source: AimiPath, target: AimiPath) {
    val src = File(source.value).toPath()
    val dst = File(target.value).toPath()
    try {
        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        // Same-volume renames are atomic on POSIX filesystems even without ATOMIC_MOVE.
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING)
    }
}

internal actual fun aimiFsRename(source: AimiPath, to: AimiPath): Boolean =
    runCatching {
        Files.move(
            File(source.value).toPath(),
            File(to.value).toPath(),
            StandardCopyOption.REPLACE_EXISTING,
        )
    }.isSuccess

internal actual fun aimiFsFreeBytes(path: AimiPath): Long = File(path.value).usableSpace

internal actual fun aimiFsIdentityKey(path: AimiPath): String? =
    runCatching {
        Files.readAttributes(File(path.value).toPath(), BasicFileAttributes::class.java)
            .fileKey()?.toString()
    }.getOrNull()

internal actual fun aimiFsTruncate(path: AimiPath, length: Long) {
    RandomAccessFile(File(path.value), "rw").use { it.setLength(length) }
}

internal actual fun aimiFsSyncParentDir(path: AimiPath) {
    runCatching {
        val parent = File(path.value).parentFile ?: return
        RandomAccessFile(parent, "r").use { it.fd.sync() }
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
    val dst = File(target.value)
    RandomAccessFile(File(source.value), "r").use { raf ->
        FileOutputStream(dst, true).use { fos ->
            if (header != null) fos.write(header)
            val gzip = GZIPOutputStream(fos)
            val buf = ByteArray(DEFAULT_COPY_BUFFER)
            raf.seek(start)
            var remaining = rangeLength
            while (remaining > 0) {
                val n = raf.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (n < 0) throw IllegalStateException("retention: short read of ${source.value}")
                gzip.write(buf, 0, n)
                remaining -= n
            }
            // finish() writes the trailer without closing the underlying stream,
            // so the file descriptor can be fsynced before close.
            gzip.finish()
            gzip.flush()
            fos.fd.sync()
            gzip.close()
        }
    }
}

private const val DEFAULT_COPY_BUFFER = 8192
