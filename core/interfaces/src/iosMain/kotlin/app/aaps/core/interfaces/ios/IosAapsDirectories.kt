package app.aaps.core.interfaces.ios

import app.aaps.core.interfaces.configuration.ExternalOptions
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile

/**
 * The one place that says where the app keeps its files on iOS.
 *
 * The Files app shows the app's own `Documents` folder as **On My iPhone › AAPS** (or
 * **› AAPSClient** for the follower app). Inside it there is one folder, `AAPS`, that holds
 * everything, laid out like the `AAPS` folder on an Android phone:
 *
 * ```
 * Documents/AAPS/
 *   preferences/   settings exports
 *   exports/       the user-entry CSV
 *   extra/         option marker files, like engineering_mode, and a README.txt
 *   ml/            the bundled models (master app only)
 *   logs/          aaps.log and its rotated files
 * ```
 *
 * The AIMI data files are written straight into `Documents/AAPS`, as before.
 *
 * Every iOS class that reads or writes one of these folders asks this class for the path. Do not
 * build a path under `Documents` for them anywhere else.
 *
 * There is no permission for an app's own `Documents` folder on iOS. The app can always read and
 * write it. The only thing that can go wrong is that a folder cannot be made, for example when the
 * phone is full.
 *
 * @param documents the `Documents` folder. Null only if iOS gives none. A test passes a temporary
 *   folder.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosAapsDirectories(val documents: String? = defaultDocumentsDirectory()) {

    /** `Documents/AAPS`: the folder that holds everything, and the AIMI data files. */
    val root: String? = documents?.let { "$it/$ROOT_NAME" }

    /** Settings exports, the counterpart of `AAPS/preferences` on Android. */
    val preferences: String? = root?.let { "$it/$PREFERENCES_NAME" }

    /** The user-entry CSV, the counterpart of `AAPS/exports` on Android. */
    val exports: String? = root?.let { "$it/$EXPORTS_NAME" }

    /** The option marker files ([ExternalOptions]), the counterpart of `AAPS/extra` on Android. */
    val extra: String? = root?.let { "$it/$EXTRA_NAME" }

    /** The bundled models. Only the master app has this folder. */
    val ml: String? = root?.let { "$it/$ML_NAME" }

    /** `aaps.log` and its rotated files. */
    val logs: String? = root?.let { "$it/$LOGS_NAME" }

    /** What [ensure] found. */
    data class EnsureResult(
        /** The folders that could not be made, or that the app cannot write to. Empty when all is well. */
        val failed: List<String>
    ) {

        val ok: Boolean get() = failed.isEmpty()
    }

    /** What [migrateLegacy] did. */
    data class MigrationResult(
        /** Files and folders moved into the new tree, as "from -> to". */
        val moved: List<String>,
        /** Legacy files left where they are, because a file with the same name was already in the target. */
        val kept: List<String>,
        /** Legacy folders that were empty after the move and were removed. */
        val removedFolders: List<String>
    )

    /** What [prepare] did, for the log and for a notification. */
    data class PrepareResult(
        val ensure: EnsureResult,
        val migration: MigrationResult,
        /** True when this start made the tree for the first time on this install. */
        val firstCreation: Boolean,
        /** True when `engineering_mode` was created by this start. */
        val engineeringModeCreated: Boolean,
        /** True when `extra/README.txt` was written by this start. */
        val readmeWritten: Boolean
    ) {

        /** Plain lines for the app log. */
        fun logLines(): List<String> = buildList {
            if (firstCreation) add("AAPS folder created for the first time")
            ensure.failed.forEach { add("AAPS folder could not be created or is not writable: $it") }
            migration.moved.forEach { add("Moved to the AAPS folder: $it") }
            migration.kept.forEach { add("Not moved, a file with the same name is already in the AAPS folder: $it") }
            migration.removedFolders.forEach { add("Removed the empty old folder: $it") }
            if (engineeringModeCreated) add("Created ${ExternalOptions.ENGINEERING_MODE.filename} on the first start")
            if (readmeWritten) add("Wrote $README_NAME in the extra folder")
        }
    }

    /**
     * Makes every folder of the tree. A follower app ([isClient]) gets no `ml` folder: it never runs
     * the loop, so it has no model.
     *
     * Safe to call on every start. A folder that is already there is left as it is.
     */
    fun ensure(isClient: Boolean): EnsureResult {
        val wanted = listOfNotNull(root, preferences, exports, extra, logs, if (isClient) null else ml)
        if (documents == null) return EnsureResult(failed = listOf("Documents"))
        val manager = NSFileManager.defaultManager
        val failed = wanted.filter { path ->
            manager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
            !isDirectory(path) || !manager.isWritableFileAtPath(path)
        }
        return EnsureResult(failed)
    }

    /**
     * Moves the files that older builds wrote straight into `Documents` into the new tree:
     * `Documents/extra`, `Documents/preferences`, `Documents/exports` and `Documents/aaps.log*`.
     *
     * - A file that already exists in the target is never overwritten. The target is kept, the
     *   legacy file stays where it is, and it is listed in [MigrationResult.kept].
     * - A legacy folder is removed only when it is empty after the move.
     * - Running it again does nothing, because there is nothing left to move.
     *
     * Call [ensure] first, so the target folders exist.
     */
    fun migrateLegacy(): MigrationResult {
        val documents = documents ?: return MigrationResult(emptyList(), emptyList(), emptyList())
        val moved = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val removed = mutableListOf<String>()
        val manager = NSFileManager.defaultManager

        fun moveOne(from: String, toFolder: String, name: String) {
            val to = "$toFolder/$name"
            if (manager.fileExistsAtPath(to)) {
                kept += from
                return
            }
            if (manager.moveItemAtPath(from, toPath = to, error = null)) moved += "$from -> $to"
            else kept += from
        }

        for ((legacyName, target) in listOf(EXTRA_NAME to extra, PREFERENCES_NAME to preferences, EXPORTS_NAME to exports)) {
            val legacy = "$documents/$legacyName"
            if (target == null || !isDirectory(legacy)) continue
            manager.createDirectoryAtPath(target, withIntermediateDirectories = true, attributes = null, error = null)
            contentsOf(legacy).forEach { name -> moveOne("$legacy/$name", target, name) }
            if (contentsOf(legacy).isEmpty() && manager.removeItemAtPath(legacy, null)) removed += legacy
        }

        val logs = logs
        if (logs != null) {
            val legacyLogs = contentsOf(documents).filter { isLogFileName(it) && !isDirectory("$documents/$it") }
            if (legacyLogs.isNotEmpty()) {
                manager.createDirectoryAtPath(logs, withIntermediateDirectories = true, attributes = null, error = null)
                legacyLogs.forEach { name -> moveOne("$documents/$name", logs, name) }
            }
        }
        return MigrationResult(moved, kept, removed)
    }

    /**
     * Everything that has to happen at app start, in the right order. Call it once, as early as
     * possible and before anything reads or writes one of the folders.
     *
     * 1. Notes whether this is the very first start of the tree: no `Documents/AAPS/extra` and no
     *    legacy `Documents/extra`.
     * 2. [ensure]: makes the folders.
     * 3. [migrateLegacy]: moves the files of older builds in.
     * 4. Only on that very first start, creates the empty `engineering_mode` file. The owner decided
     *    this. If the user deletes the file later, it does not come back, because `extra` exists then.
     * 5. Writes `extra/README.txt` when it is missing.
     */
    fun prepare(isClient: Boolean): PrepareResult {
        val firstCreation = documents != null && extra != null && !isDirectory(extra) && !isDirectory("$documents/$EXTRA_NAME")
        val ensured = ensure(isClient)
        val migration = migrateLegacy()
        val engineeringModeCreated = firstCreation && createEmptyFileIfMissing(extra, ExternalOptions.ENGINEERING_MODE.filename)
        val readmeWritten = writeReadmeIfMissing()
        return PrepareResult(ensured, migration, firstCreation, engineeringModeCreated, readmeWritten)
    }

    /** Writes `extra/README.txt` when it is not there. True when it was written now. */
    fun writeReadmeIfMissing(): Boolean {
        val extra = extra ?: return false
        if (!isDirectory(extra)) return false
        val path = "$extra/$README_NAME"
        if (NSFileManager.defaultManager.fileExistsAtPath(path)) return false
        return NSString.create(string = readmeText()).writeToFile(path, atomically = true, encoding = NSUTF8StringEncoding, error = null)
    }

    private fun createEmptyFileIfMissing(folder: String?, name: String): Boolean {
        if (folder == null || !isDirectory(folder)) return false
        val path = "$folder/$name"
        val manager = NSFileManager.defaultManager
        if (manager.fileExistsAtPath(path)) return false
        return manager.createFileAtPath(path, contents = null, attributes = null)
    }

    private fun contentsOf(folder: String): List<String> {
        @Suppress("UNCHECKED_CAST")
        return NSFileManager.defaultManager.contentsOfDirectoryAtPath(folder, null) as? List<String> ?: emptyList()
    }

    private fun isDirectory(path: String): Boolean {
        val manager = NSFileManager.defaultManager
        if (!manager.fileExistsAtPath(path)) return false
        // A path that lists as a folder is a folder. `contentsOfDirectoryAtPath` answers null for a file.
        return manager.contentsOfDirectoryAtPath(path, null) != null
    }

    companion object {

        const val ROOT_NAME = "AAPS"
        const val PREFERENCES_NAME = "preferences"
        const val EXPORTS_NAME = "exports"
        const val EXTRA_NAME = "extra"
        const val ML_NAME = "ml"
        const val LOGS_NAME = "logs"

        /** The live log file. Rotated files add `.1`, `.2` and so on. */
        const val LOG_FILE_NAME = "aaps.log"

        /** The help file in `extra`. */
        const val README_NAME = "README.txt"

        /** `aaps.log` or one of its rotated files, `aaps.log.1` and so on. */
        fun isLogFileName(name: String): Boolean =
            name == LOG_FILE_NAME || name.removePrefix("$LOG_FILE_NAME.").let { it != name && it.toIntOrNull()?.let { n -> n > 0 } == true }

        /**
         * The text of `extra/README.txt`. The list is made from [ExternalOptions], so a new option
         * is listed without anyone editing this.
         */
        fun readmeText(): String = buildString {
            appendLine("AAPS option files")
            appendLine()
            appendLine("Each name below turns on one option.")
            appendLine("To turn an option on, create an empty file with exactly that name in this folder.")
            appendLine("To turn it off, delete the file.")
            appendLine("If a change does not work at once, close the app and open it again.")
            appendLine("These options are for testing and development. Only use one if you know what it does.")
            appendLine()
            ExternalOptions.entries.forEach { option ->
                appendLine("${option.filename} - an empty file with this name turns this option on, deleting it turns it off.")
            }
        }
    }
}

/** The app's own `Documents` folder. Null only if iOS gives none, which should not happen. */
@OptIn(ExperimentalForeignApi::class)
private fun defaultDocumentsDirectory(): String? =
    NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String
