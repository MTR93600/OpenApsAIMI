package app.aaps.implementation.logging

import app.aaps.core.interfaces.ios.IosAapsDirectories
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager

/**
 * How the iOS log files are named, in one place for the logger that writes them and the maintenance
 * code that lists, sends and deletes them.
 *
 * The live file is `aaps.log`. When it is full it is moved to `aaps.log.1`, the previous `.1` to
 * `.2` and so on, so a higher number is an older file.
 *
 * They live in `Documents/AAPS/logs` ([IosAapsDirectories.logs]). Older builds wrote them at the
 * `Documents` root; they are moved once at app start ([IosAapsDirectories.migrateLegacy]).
 */
object IosLogFiles {

    const val LOG_NAME = IosAapsDirectories.LOG_FILE_NAME

    /**
     * `Documents/AAPS/logs`, which the Files app shows as **On My iPhone > AAPS > AAPS > logs**. The
     * logger that writes and the maintenance code that sends and deletes both use this.
     */
    fun defaultDirectory(): String? = IosAapsDirectories().logs

    /** The name of rotated file number [index], 1 being the newest. */
    fun rotatedName(name: String, index: Int): String = "$name.$index"

    /**
     * The rotated files of [name] in [directory], newest first. The live file is not included.
     *
     * Ordered by the number rather than as text, so `.10` is older than `.2` instead of sorting
     * between `.1` and `.2`.
     */
    @OptIn(ExperimentalForeignApi::class)
    fun rotatedNewestFirst(directory: String, name: String = LOG_NAME): List<String> {
        @Suppress("UNCHECKED_CAST")
        val names = NSFileManager.defaultManager.contentsOfDirectoryAtPath(directory, null) as? List<String> ?: return emptyList()
        return names
            .mapNotNull { file -> file.takeIf { it.startsWith("$name.") }?.removePrefix("$name.")?.toIntOrNull()?.takeIf { it > 0 }?.let { it to file } }
            .sortedBy { (index, _) -> index }
            .map { (_, file) -> file }
    }

    /** The live file first and then the rotated ones, newest first: all the log there is. */
    @OptIn(ExperimentalForeignApi::class)
    fun allNewestFirst(directory: String, name: String = LOG_NAME): List<String> {
        val live = name.takeIf { NSFileManager.defaultManager.fileExistsAtPath("$directory/$it") }
        return listOfNotNull(live) + rotatedNewestFirst(directory, name)
    }
}
