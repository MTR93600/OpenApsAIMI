package app.aaps.plugins.aps.openAPSAIMI.utils

import java.io.File
import java.io.RandomAccessFile

internal actual fun aimiLocalFiles(): AimiLocalFiles = JvmAimiLocalFiles

internal actual fun iosTickAimiRoot(): String {
    val root = File(System.getProperty("java.io.tmpdir"), "aimi-ios-tick")
    root.mkdirs()
    return root.absolutePath
}

private object JvmAimiLocalFiles : AimiLocalFiles {
    override fun exists(path: String): Boolean = runCatching { File(path).exists() }.getOrDefault(false)

    override fun readText(path: String): String? = runCatching { File(path).readText() }.getOrNull()

    override fun writeText(path: String, text: String): Boolean = runCatching {
        File(path).writeText(text)
        true
    }.getOrDefault(false)

    override fun appendText(path: String, text: String): Boolean = runCatching {
        File(path).appendText(text)
        true
    }.getOrDefault(false)

    override fun delete(path: String): Boolean = runCatching {
        val file = File(path)
        if (file.exists()) file.delete()
        !file.exists()
    }.getOrDefault(false)

    override fun createDirectories(path: String): Boolean = runCatching {
        val file = File(path)
        file.exists() || file.mkdirs()
    }.getOrDefault(false)

    override fun rename(from: String, to: String): Boolean = runCatching {
        val target = File(to)
        if (target.exists()) target.delete()
        File(from).renameTo(target)
    }.getOrDefault(false)

    override fun length(path: String): Long = runCatching { File(path).length() }.getOrDefault(0L)

    override fun lastByte(path: String): Int? {
        val file = File(path)
        val length = runCatching { file.length() }.getOrDefault(0L)
        if (length <= 0L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { reader ->
                reader.seek(length - 1)
                reader.read().takeIf { it >= 0 }
            }
        }.getOrNull()
    }

    override fun lastModifiedMs(path: String): Long? =
        runCatching { File(path).lastModified().takeIf { it != 0L } }.getOrNull()

    override fun canRead(path: String): Boolean = runCatching { File(path).canRead() }.getOrDefault(false)

    override fun canWrite(path: String): Boolean = runCatching { File(path).canWrite() }.getOrDefault(false)
}
