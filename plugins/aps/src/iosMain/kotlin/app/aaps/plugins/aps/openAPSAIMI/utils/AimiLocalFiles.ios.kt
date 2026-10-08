package app.aaps.plugins.aps.openAPSAIMI.utils

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import platform.Foundation.NSDate
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileHandle
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.closeFile
import platform.Foundation.create
import platform.Foundation.fileHandleForReadingAtPath
import platform.Foundation.readDataOfLength
import platform.Foundation.seekToFileOffset
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

internal actual fun aimiLocalFiles(): AimiLocalFiles = IosAimiLocalFiles

@OptIn(ExperimentalForeignApi::class)
internal actual fun iosTickAimiRoot(): String {
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        .firstOrNull() as? String
        ?: error("no Documents directory")
    val root = "$documents/AAPS"
    NSFileManager.defaultManager.createDirectoryAtPath(
        root,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    return root
}

@OptIn(ExperimentalForeignApi::class)
private object IosAimiLocalFiles : AimiLocalFiles {
    private val manager = NSFileManager.defaultManager

    override fun exists(path: String): Boolean = manager.fileExistsAtPath(path)

    override fun readText(path: String): String? =
        NSString.stringWithContentsOfFile(path, encoding = NSUTF8StringEncoding, error = null)

    override fun writeText(path: String, text: String): Boolean =
        NSString.create(string = text).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)

    override fun appendText(path: String, text: String): Boolean {
        val current = readText(path).orEmpty()
        return writeText(path, current + text)
    }

    override fun delete(path: String): Boolean {
        if (manager.fileExistsAtPath(path)) {
            manager.removeItemAtPath(path, null)
        }
        return !manager.fileExistsAtPath(path)
    }

    override fun createDirectories(path: String): Boolean {
        if (manager.fileExistsAtPath(path)) return true
        return manager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
    }

    override fun rename(from: String, to: String): Boolean {
        if (manager.fileExistsAtPath(to)) manager.removeItemAtPath(to, null)
        return manager.moveItemAtPath(from, toPath = to, error = null)
    }

    override fun length(path: String): Long =
        (manager.attributesOfItemAtPath(path, null)?.get(NSFileSize) as? Number)?.toLong() ?: 0L

    /**
     * Seeks to the last byte and reads exactly that one byte.
     *
     * `NSFileHandle` is used rather than `NSString.stringWithContentsOfFile` on purpose: the whole
     * point of this call is not to load the file. The byte is widened to an unsigned 0..255 so it
     * matches what `java.io.RandomAccessFile.read` answers on the other two targets.
     */
    override fun lastByte(path: String): Int? {
        val length = length(path)
        if (length <= 0L) return null
        val handle = NSFileHandle.fileHandleForReadingAtPath(path) ?: return null
        try {
            handle.seekToFileOffset((length - 1L).toULong())
            val data = handle.readDataOfLength(1u)
            if (data.length.toLong() < 1L) return null
            val bytes = data.bytes?.reinterpret<ByteVar>() ?: return null
            return bytes[0].toInt() and 0xFF
        } finally {
            handle.closeFile()
        }
    }

    override fun lastModifiedMs(path: String): Long? {
        val date = manager.attributesOfItemAtPath(path, null)?.get(NSFileModificationDate) as? NSDate
        return date?.timeIntervalSince1970?.times(1000.0)?.toLong()
    }

    override fun canRead(path: String): Boolean = manager.isReadableFileAtPath(path)

    override fun canWrite(path: String): Boolean = manager.isWritableFileAtPath(path)
}
