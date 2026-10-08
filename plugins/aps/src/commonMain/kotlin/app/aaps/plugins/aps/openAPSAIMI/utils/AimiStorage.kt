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

    /**
     * Whether [path] can be written to right now.
     *
     * Diagnostic only, and deliberately not a gate: every write here already answers `false` instead
     * of throwing, so asking first buys nothing but a race. It exists because the stores that log
     * "the file is not where I left it" print this next to [exists] and [canRead], and that triple is
     * what tells a reader whether a missing file is a permission problem or a first run.
     */
    fun canWrite(path: AimiPath): Boolean

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

    /**
     * Walks the lines of [path] one at a time, without ever holding the whole file.
     *
     * Separate from [readLines] for the same reason [readFirstLine] is: AIMI's journals grow by a
     * line every loop tick and are never truncated, so a file this code reads can be tens of
     * megabytes on a device whose heap is 256 MB. [readLines] is right for a file you know is small;
     * this is right for a journal. The support package export and the training CSV readers are the
     * callers that need it.
     *
     * Answers `false` when the file is missing or unreadable, or when the walk stopped early because
     * of an I/O failure - so a caller that must not present a truncated file as a complete one can
     * tell the difference. [action] is called once per line, in file order.
     */
    fun forEachLine(path: AimiPath, action: (String) -> Unit): Boolean

    /** Replaces the content. `false` on failure. */
    fun writeText(path: AimiPath, text: String): Boolean

    /** Adds to the end, creating the file if needed. `false` on failure. */
    fun appendText(path: AimiPath, text: String): Boolean

    /** Human readable form of [path], for log lines only. Never parse it. */
    fun displayPath(path: AimiPath): String

    /**
     * One human readable line per storage location AIMI tried, for the loop's learners health block.
     *
     * A diagnostic string, not data: the caller prints it into the reasoning text and never parses
     * it. The Android half already owns the three deep directory policy this describes, so the report
     * is its to write.
     */
    fun healthReport(): String

    /**
     * The app scoped fallback file AIMI writes a CSV row to when the shared storage write is denied.
     *
     * A second location, not a second policy: this is the same app scoped external directory the
     * Android storage helper already resolves at its second tier, named here so shared code can reach
     * the fallback without holding a platform path. Falls back to [directory] when the platform has
     * no app scoped external directory, exactly as the loop has always done.
     */
    fun fallbackFile(name: String): AimiPath

    /** Removes [path]. `true` when nothing is left there afterwards, including when it never existed. */
    fun delete(path: AimiPath): Boolean

    /**
     * Replaces the content of [path] with [text], or leaves what was there before untouched.
     *
     * The guarantee: on `false`, whatever [path] held before this call is still there and still
     * readable. This is not academic - one of the callers this exists for is `AimiNeuralModelStore`,
     * whose weight file the dosing algorithm loads on the next loop tick, so a write that dies halfway
     * must never leave a corrupt file in its place. Three callers today hand-roll a
     * write-tmp/drop-bak/rename protocol to get this; that dance moves into the Android
     * implementation here, so no caller has to own it, or get it wrong, on its own.
     */
    fun replaceText(path: AimiPath, text: String): Boolean

    /**
     * Replaces the content of [path] with [text], keeping the version that was there before as a
     * `.bak` sibling (see [sibling]). `false` on failure.
     *
     * This exists next to [replaceText], not instead of it, because one caller needs a different
     * guarantee. `AimiNeuralModelStore` persists a weight file the dosing algorithm loads on the
     * next tick, and its `.bak` is the rollback: `load` tries the target and then the `.bak`, and
     * `delete` removes both, precisely so a model just judged dead cannot come back through the
     * backup. Routing that save through plain [replaceText] would delete that rollback, not tidy
     * it - so this method keeps it. Implementations write a `.tmp` sibling, rotate the current
     * target to `.bak`, then rename the `.tmp` over the target; a crash between steps must leave
     * either the previous target or the `.bak` intact.
     */
    fun replaceKeepingBackup(path: AimiPath, text: String): Boolean

    /**
     * Replaces the whole content of [path] with [lines], one line at a time, and never holds the
     * file as a single string.
     *
     * This exists next to [replaceText] because of what it is used on. The caller is the Autodrive
     * backfiller, and the file is the on-device training corpus: it only ever grows inside its
     * retention window, and on a real phone it is already megabytes. Joining it into one [String] to
     * hand to [replaceText] would put a second full copy of the corpus on the heap of a device whose
     * whole heap is a few hundred megabytes, so the lines are written as they are produced and
     * [lines] is walked exactly once.
     *
     * Each line is followed by a single `\n`, including the last one, so the file always ends with a
     * line break. That is what every reader and writer of these AIMI CSV files assumes: the data lake
     * appends `row + "\n"`, and the backfiller used to get the same thing from the platform line
     * separator, which on Android is always `\n`. Writing it explicitly makes the file identical on
     * every host instead of depending on a system property.
     *
     * [temporary] is the scratch file the new content is built in before it takes the place of
     * [path]. The caller names it rather than this method, because in the AIMI directory a temporary
     * file is not private: `AimiBackupManager` walks that directory and decides what to back up from
     * the file **name**, so the name is observable behaviour and belongs to the caller that owns the
     * file. [temporary] must sit next to [path]; a scratch file on another volume cannot be renamed
     * into place.
     *
     * The guarantee, and its limit:
     * - A failure while the new content is being written leaves [path] exactly as it was, and leaves
     *   no scratch file behind. This is the failure that can actually happen — a full disk, a
     *   revoked permission — and it is why the content is built somewhere else first.
     * - The final swap is a rename, which is atomic. When the platform refuses the rename the
     *   implementation copies instead, and a failure *during that copy* can leave [path] truncated.
     *   Nothing can be done about it without a second copy of the corpus, which is the very thing
     *   this method exists to avoid, and it is the same window the hand-rolled code had before.
     *
     * @return `true` when [path] holds exactly [lines] afterwards, `false` when it was left untouched.
     */
    fun rewriteLines(path: AimiPath, temporary: AimiPath, lines: Sequence<String>): Boolean

    /**
     * The last [maxLines] complete lines of [path], newest first.
     *
     * For a journal that only ever grows - a decision log, a training CSV - reading the whole file to
     * see its tail does not scale, so this scans backward from the end instead of loading everything.
     * A missing or unreadable file, or a line that cannot be decoded, is skipped rather than thrown:
     * a broken history line must not take down the read.
     */
    fun readTailLines(path: AimiPath, maxLines: Int): List<String>

    /** How big [path] is, in bytes. `0` when it is missing or unreadable. */
    fun sizeBytes(path: AimiPath): Long

    /**
     * The last character stored in [path], or `null` when it is empty, missing or unreadable.
     *
     * Only the final byte is read, and that is the whole point of having this next to [readLines] and
     * [readTailLines]. The caller is the training CSV writer, which runs on every loop tick against a
     * file that only ever grows, so reading the file - or even its tail - to look at its end would not
     * scale.
     *
     * The single question it answers is whether the file already ends with a line break, which
     * `AimiCorpusPruner.rowPrefix` turns into the prefix of the next row. One byte is enough for that
     * on UTF-8 text, because no byte of a multi byte character is ever `0x0A`. A file whose final byte
     * belongs to a multi byte character therefore answers with that raw byte rather than with a
     * decoded character; it is not a line break either way, which is all the caller looks at.
     */
    fun lastChar(path: AimiPath): Char?

    /** Copies [from] to [to], overwriting [to] if it exists. `false` on failure; [from] is untouched. */
    fun copy(from: AimiPath, to: AimiPath): Boolean

    /** When [path] last changed, in epoch milliseconds. `null` when it is missing or unknown. */
    fun lastModifiedMs(path: AimiPath): Long?
}
