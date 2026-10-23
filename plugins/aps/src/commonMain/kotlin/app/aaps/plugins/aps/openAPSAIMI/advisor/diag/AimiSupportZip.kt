package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath

/**
 * Creates the ZIP file of the AIMI Advisor support package.
 *
 * This is the only platform part of the package. Which entries go in, in which order, and what each
 * one holds is shared code ([AimiSupportPackageContent]), so both phones send the same package. Only
 * the writing of ZIP bytes and the place of the file differ:
 * - Android: `AndroidAimiSupportZipFactory`, `java.util.zip` in the app cache directory, as before.
 * - iOS: `IosAimiSupportZipFactory`, [AimiStoredZipWriter] in the app temporary directory.
 *
 * An interface rather than an `expect fun`, because the Android side needs a directory from an
 * injected `Context`, which an `expect` signature cannot name. Same shape as
 * [app.aaps.plugins.aps.openAPSAIMI.advisor.AimiSharing].
 */
fun interface AimiSupportZipFactory {

    /**
     * Opens a new, empty archive called [fileName] in a directory the app may share files from.
     * A file of that name is replaced. Throws when the file cannot be created.
     */
    fun create(fileName: String): AimiSupportZipOutput
}

/**
 * One ZIP archive being written, one entry after the other.
 *
 * The calls come in this order: [beginEntry], any number of [write], [endEntry], and again for the
 * next entry, then [finish] once. Entries are streamed: an implementation must not need the whole
 * entry in memory, because the decision log can be several megabytes.
 *
 * Every method throws on an I/O failure. The exporter turns that into
 * [AimiSupportPackageResult.Failed], so a broken package is never shared as if it were complete.
 */
interface AimiSupportZipOutput {

    /** Where the archive is written. */
    val path: AimiPath

    /** Starts the entry [name]. [name] is stored as UTF-8. */
    fun beginEntry(name: String)

    /** Adds [bytes] to the current entry. */
    fun write(bytes: ByteArray)

    /** Ends the current entry. */
    fun endEntry()

    /** Writes the end of the archive and closes the file. Answers the size of the file in bytes. */
    fun finish(): Long

    /**
     * Gives up after a failure: closes the file without finishing it and deletes it, because a
     * partial package still holds health data. Also works when [finish] itself failed. Does nothing
     * after a [finish] that worked. Never throws.
     */
    fun abort()
}
