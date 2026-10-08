package app.aaps.plugins.aps.openAPSAIMI.utils

/**
 * The bytes of one file, as the platform stores them.
 *
 * [DirectoryAimiStorage] is the shared policy (names, the temporary-file replace, a failed write
 * answering `false`). This is only the system call underneath. Paths use `/`.
 */
internal interface AimiLocalFiles {
    fun exists(path: String): Boolean
    fun readText(path: String): String?
    fun writeText(path: String, text: String): Boolean
    fun appendText(path: String, text: String): Boolean
    fun delete(path: String): Boolean
    fun createDirectories(path: String): Boolean
    fun rename(from: String, to: String): Boolean
    fun length(path: String): Long

    /**
     * The final byte of the file, as an unsigned 0..255, or `null` when the file is empty, missing or
     * unreadable.
     *
     * A real seek to the end, not a read of the whole file: it backs [AimiStorage.lastChar], whose
     * reason to exist is that its caller runs on every loop tick over a file that only grows.
     */
    fun lastByte(path: String): Int?
    fun lastModifiedMs(path: String): Long?
    fun canRead(path: String): Boolean
    fun canWrite(path: String): Boolean
}

internal expect fun aimiLocalFiles(): AimiLocalFiles

/** Directory that holds `tpo/tpo_session.json` for the iOS tick. */
internal expect fun iosTickAimiRoot(): String
