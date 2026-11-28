package app.aaps.implementation.maintenance

import app.aaps.core.interfaces.ios.IosAapsDirectories
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSFileManager
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * [PrefsFileAccess] over the app's own `Documents/AAPS` folder ([IosAapsDirectories]), which the
 * Files app shows to the user.
 *
 * On Android the user picks a folder through the storage access framework and AAPS holds a permission
 * to it. iOS has no such picker for a folder the app then keeps writing to, and does not need one:
 * `Documents` is exposed by `UIFileSharingEnabled`, so the user can reach an export, copy it off the
 * phone, or drop one in to import. There is no permission to lose.
 *
 * The database used to live in `Documents` and no longer does, which is what makes exposing it
 * safe: a user tidying up their files can no longer delete the treatment history by accident.
 *
 * ## The layout is Android's
 *
 * `Documents/AAPS` is the counterpart of the `AAPS` folder a phone writes to, and is laid out the
 * same way: settings exports in `preferences`, the user-entry CSV in `exports`, and the option marker
 * files in `extra`. A user copying a backup between a phone and an iPhone finds it in the same
 * relative place.
 *
 * Older builds wrote into `Documents/preferences` and `Documents/exports`, and before that into
 * `Documents` itself. The first two are moved into `Documents/AAPS` once at app start
 * ([IosAapsDirectories.migrateLegacy]). Files at the `Documents` root are not listed: see [list].
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class IosPrefsFileAccess(
    /**
     * Settings exports, the counterpart of Android's `AAPS/preferences`.
     *
     * A test points it somewhere of its own so it neither reads the user's exports nor leaves files
     * behind for the next test to list.
     */
    private val directory: String? = IosAapsDirectories().preferences,
    /** The CSV goes elsewhere, for the reason given on [write]. */
    private val csvDirectory: String? = IosAapsDirectories().exports
) : PrefsFileAccess {

    /**
     * Pinned to `en_US_POSIX` and the Gregorian calendar on purpose.
     *
     * An `NSDateFormatter` otherwise follows the user's own calendar and digits, so the same pattern
     * gives a Buddhist year on a Thai phone and Eastern Arabic numerals on an Arabic one. A file name
     * is not something the user reads in their language - it is sorted, matched and compared with
     * files written by Android, which uses these digits.
     */
    private val nameFormatter = NSDateFormatter().apply {
        setLocale(NSLocale("en_US_POSIX"))
        setDateFormat("yyyy-MM-dd'_'HHmmss")
    }

    override fun newExportName(flavour: String): String = "${nameFormatter.stringFromDate(NSDate())}_$flavour.json"

    override fun newCsvName(): String = "${nameFormatter.stringFromDate(NSDate())}_UserEntry.csv"

    /**
     * Writes into the folder Android would write this kind of file into.
     *
     * A phone splits them - `newPreferenceFile` creates in `AAPS/preferences` and `newExportCsvFile`
     * in `AAPS/exports` - so putting both in one folder would not be a mirror of a phone, and a user
     * copying a backup between the two would be looking in the wrong place. The name is what says
     * which: only the CSV is not a settings export, and it is the only `.csv` written.
     */
    override fun write(name: String, contents: String) {
        val target = (if (name.endsWith(".csv")) csvDirectory else directory) ?: error("no Documents directory")
        NSFileManager.defaultManager.createDirectoryAtPath(target, withIntermediateDirectories = true, attributes = null, error = null)
        val ok = NSString.create(string = contents).writeToFile("$target/$name", atomically = true, encoding = NSUTF8StringEncoding, error = null)
        if (!ok) error("could not write $name")
    }

    /**
     * The settings exports, from `preferences` only.
     *
     * Deliberately not also `Documents` itself, where a very old build wrote them. Listing both would
     * mean a file the app can no longer produce keeps appearing next to ones it can, with nothing to
     * tell the two apart - and the fallback would have to be carried until someone could prove no
     * phone still had one, which is not a thing anyone can prove.
     */
    override fun list(): List<Pair<String, String>> {
        val manager = NSFileManager.defaultManager
        val directory = this.directory ?: return emptyList()
        @Suppress("UNCHECKED_CAST")
        val names = manager.contentsOfDirectoryAtPath(directory, null) as? List<String> ?: return emptyList()
        return names.filter { it.endsWith(".json") }.mapNotNull { name ->
            NSString.stringWithContentsOfFile(directory + "/" + name, encoding = NSUTF8StringEncoding, error = null)?.let { name to it }
        }
    }

    /** Where settings exports are, so a screen can tell the user where to look. */
    val path: String? get() = directory
}

/**
 * A named folder directly inside the app's own `Documents` folder, or null on a build that has none.
 *
 * Used only by the Autotune files, which are not part of the `Documents/AAPS` tree yet. Not created
 * here: a build that never writes there should leave nothing behind.
 */
internal fun defaultDocumentsSubdirectory(name: String): String? = IosAapsDirectories().documents?.let { "$it/$name" }
