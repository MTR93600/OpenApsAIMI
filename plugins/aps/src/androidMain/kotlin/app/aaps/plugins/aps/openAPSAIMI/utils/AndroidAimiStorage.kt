package app.aaps.plugins.aps.openAPSAIMI.utils

import app.aaps.plugins.aps.openAPSAIMI.advisor.data.JsonlTailReader
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File
import java.io.RandomAccessFile

/**
 * Android half of [AimiStorage].
 *
 * It delegates to [AimiStorageHelper] rather than resolving a directory of its own, so there is
 * exactly one storage policy at runtime and one health report. The helper is a singleton and
 * resolves its directory lazily, so constructing this class does no I/O.
 *
 * Every write is wrapped: a failed AIMI log line must never take down a dosing tick.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiStorage @Inject constructor(
    private val helper: AimiStorageHelper
) : AimiStorage {

    private fun fileOf(path: AimiPath): File = File(path.value)

    override fun directory(): AimiPath = AimiPath(helper.getAimiDirectory().absolutePath)

    override fun file(name: String): AimiPath = AimiPath(helper.getAimiFile(name).absolutePath)

    override fun file(subdirectory: String, name: String): AimiPath =
        AimiPath(helper.getAimiFile(subdirectory, name).absolutePath)

    override fun resolve(directory: AimiPath, name: String): AimiPath =
        AimiPath(File(fileOf(directory), name).absolutePath)

    override fun sibling(path: AimiPath, suffix: String): AimiPath = AimiPath(path.value + suffix)

    override fun exists(path: AimiPath): Boolean = runCatching { fileOf(path).exists() }.getOrDefault(false)

    override fun canRead(path: AimiPath): Boolean = runCatching { fileOf(path).canRead() }.getOrDefault(false)

    override fun canWrite(path: AimiPath): Boolean = runCatching { fileOf(path).canWrite() }.getOrDefault(false)

    override fun createDirectories(path: AimiPath): Boolean =
        runCatching { fileOf(path).let { it.exists() || it.mkdirs() } }.getOrDefault(false)

    override fun createParentDirectories(path: AimiPath): Boolean =
        runCatching { fileOf(path).parentFile?.let { it.exists() || it.mkdirs() } ?: true }.getOrDefault(false)

    override fun createFile(path: AimiPath): Boolean =
        runCatching { fileOf(path).let { it.exists() || it.createNewFile() } }.getOrDefault(false)

    override fun readText(path: AimiPath): String? = runCatching { fileOf(path).readText() }.getOrNull()

    override fun readLines(path: AimiPath): List<String> =
        runCatching { fileOf(path).readLines() }.getOrDefault(emptyList())

    override fun readFirstLine(path: AimiPath): String? =
        runCatching { fileOf(path).bufferedReader().use { it.readLine() } }.getOrNull()

    override fun forEachLine(path: AimiPath, action: (String) -> Unit): Boolean =
        runCatching {
            fileOf(path).bufferedReader().use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    action(line)
                    line = reader.readLine()
                }
            }
            true
        }.getOrDefault(false)

    override fun writeText(path: AimiPath, text: String): Boolean =
        runCatching { fileOf(path).writeText(text); true }.getOrDefault(false)

    override fun appendText(path: AimiPath, text: String): Boolean =
        runCatching { fileOf(path).appendText(text); true }.getOrDefault(false)

    override fun displayPath(path: AimiPath): String = path.value

    override fun healthReport(): String = helper.getHealthReport()

    override fun fallbackFile(name: String): AimiPath =
        AimiPath(File(File(helper.appScopedExternalDir() ?: helper.getAimiDirectory(), "AAPS"), name).absolutePath)

    override fun delete(path: AimiPath): Boolean =
        runCatching { fileOf(path).let { it.delete(); !it.exists() } }.getOrDefault(false)

    override fun replaceText(path: AimiPath, text: String): Boolean {
        val tmpFile = fileOf(sibling(path, ".tmp"))
        val target = fileOf(path)
        return try {
            tmpFile.writeText(text)
            val replaced = tmpFile.renameTo(target)
            if (!replaced) tmpFile.delete()
            replaced
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            false
        }
    }

    override fun replaceKeepingBackup(path: AimiPath, text: String): Boolean {
        createParentDirectories(path)
        val tmpFile = fileOf(sibling(path, ".tmp"))
        val bakFile = fileOf(sibling(path, ".bak"))
        val target = fileOf(path)
        return try {
            tmpFile.writeText(text)
            if (target.exists()) {
                bakFile.delete()
                target.renameTo(bakFile)
            }
            val replaced = tmpFile.renameTo(target)
            if (!replaced) tmpFile.delete()
            replaced
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            false
        }
    }

    /**
     * Streams [lines] into [temporary], then puts it in the place of [path].
     *
     * The rename is the swap. `renameTo` answers `false` rather than throwing on the emulated volume
     * that holds `Documents/AAPS`, so the copy is kept as the second attempt exactly as the
     * hand-rolled code in the Autodrive backfiller had it; without it the rewrite would silently do
     * nothing on those devices. The scratch file is removed when the write fails, so a truncated CSV
     * cannot stay in the AIMI directory and be picked up as a backup candidate.
     */
    override fun rewriteLines(path: AimiPath, temporary: AimiPath, lines: Sequence<String>): Boolean {
        val tmpFile = fileOf(temporary)
        val target = fileOf(path)
        return try {
            tmpFile.bufferedWriter().use { writer ->
                lines.forEach { line ->
                    writer.write(line)
                    writer.write("\n")
                }
            }
            if (!tmpFile.renameTo(target)) {
                tmpFile.copyTo(target, overwrite = true)
                tmpFile.delete()
            }
            true
        } catch (e: Exception) {
            runCatching { tmpFile.delete() }
            false
        }
    }

    override fun readTailLines(path: AimiPath, maxLines: Int): List<String> =
        runCatching { JsonlTailReader.readTailLines(fileOf(path), maxLines) }.getOrDefault(emptyList())

    override fun sizeBytes(path: AimiPath): Long = runCatching { fileOf(path).length() }.getOrDefault(0L)

    /**
     * Reads the final byte with a [RandomAccessFile] seek, which is the same read the training CSV
     * writer did before this method existed. [java.io.RandomAccessFile.read] answers an unsigned byte,
     * so the returned `Char` is that raw byte and not a decoded character - see [AimiStorage.lastChar]
     * for why that is what the caller wants.
     */
    override fun lastChar(path: AimiPath): Char? {
        val file = fileOf(path)
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { reader ->
                reader.seek(length - 1)
                reader.read().takeIf { it >= 0 }?.toChar()
            }
        }.getOrNull()
    }

    override fun copy(from: AimiPath, to: AimiPath): Boolean =
        runCatching { fileOf(from).copyTo(fileOf(to), overwrite = true); true }.getOrDefault(false)

    override fun lastModifiedMs(path: AimiPath): Long? =
        runCatching { fileOf(path).lastModified().takeIf { it != 0L } }.getOrNull()
}
