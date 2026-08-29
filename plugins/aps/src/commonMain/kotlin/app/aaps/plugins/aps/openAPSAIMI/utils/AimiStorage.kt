package app.aaps.plugins.aps.openAPSAIMI.utils

/**
 * A place on disk, as AIMI shared code sees it.
 *
 * The string inside is whatever the platform calls a path and shared code must not take it apart.
 * It exists so that a file can be named, passed around and stored in shared code without naming
 * `java.io.File`, which does not exist outside the JVM. Every operation on it goes through
 * [AimiStorage].
 *
 * A plain data class rather than a value class on purpose: this module has no `@JvmInline` anywhere
 * yet, and the saving would be one allocation per file access on a five-minute loop.
 */
data class AimiPath(val value: String)

/**
 * File storage for AIMI shared code.
 *
 * AIMI persists what it learns - cycle multipliers, PK/PD rows, decision journals - and *where* it
 * may write is a platform question, not a shared one. On Android the answer is three deep
 * (`Documents/AAPS`, then app-scoped external, then internal) and `AimiStorageHelper` owns it. This
 * interface is the shared half of that: name a file, read it, write it.
 *
 * **There is deliberately no implementation on iOS.** AIMI storage is a body of Android storage
 * policy with no iOS answer yet, and a stub that quietly wrote nowhere would leave the learning
 * loops looking alive while they persisted nothing. Without a binding, the feature is visibly
 * absent on that target and any future iOS graph fails at wiring time, loudly.
 *
 * All write operations answer `false` rather than throwing: AIMI logging must never take down a
 * dosing tick.
 */
interface AimiStorage {

    /** The AIMI directory. Created if it does not exist. */
    fun directory(): AimiPath

    /** [name] inside the AIMI directory. Does not create the file. */
    fun file(name: String): AimiPath

    /** [name] inside [subdirectory] of the AIMI directory. The subdirectory is created. */
    fun file(subdirectory: String, name: String): AimiPath

    /** [name] inside [directory], for a directory that did not come from [directory]. */
    fun resolve(directory: AimiPath, name: String): AimiPath

    /**
     * [path] with [suffix] added to its name - `weights.json` + `".bak"` gives `weights.json.bak`,
     * in the same directory.
     *
     * It exists so shared code can name a backup file without pulling an [AimiPath] apart, which the
     * path's own contract forbids.
     */
    fun sibling(path: AimiPath, suffix: String): AimiPath

    fun exists(path: AimiPath): Boolean

    fun canRead(path: AimiPath): Boolean

    /** Creates [path] as a directory, with parents. `true` if it exists afterwards. */
    fun createDirectories(path: AimiPath): Boolean

    /** Creates the directory holding [path], with parents. `true` if it exists afterwards. */
    fun createParentDirectories(path: AimiPath): Boolean

    /** Creates [path] as an empty file. `true` if it exists afterwards. */
    fun createFile(path: AimiPath): Boolean

    /** Whole content, or `null` when the file is missing or unreadable. */
    fun readText(path: AimiPath): String?

    /** Lines, or an empty list when the file is missing or unreadable. */
    fun readLines(path: AimiPath): List<String>

    /**
     * The first line of [path], or `null` when the file is missing, empty or unreadable.
     *
     * Separate from [readLines] on purpose. The CSV schema check runs on every loop tick against a
     * file that gains one row every five minutes and is never truncated, so reading the whole file to
     * look at its header would grow without limit. This reads one line, which is what the code did
     * before the port.
     */
    fun readFirstLine(path: AimiPath): String?

    /** Replaces the content. `false` on failure. */
    fun writeText(path: AimiPath, text: String): Boolean

    /** Adds to the end, creating the file if needed. `false` on failure. */
    fun appendText(path: AimiPath, text: String): Boolean

    /** Human readable form of [path], for log lines only. Never parse it. */
    fun displayPath(path: AimiPath): String
}
