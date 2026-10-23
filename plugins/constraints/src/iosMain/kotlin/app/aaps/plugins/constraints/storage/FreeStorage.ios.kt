package app.aaps.plugins.constraints.storage

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSHomeDirectory
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLVolumeAvailableCapacityForImportantUsageKey

/**
 * Free space on the app's volume.
 *
 * The smaller of the two numbers iOS gives. The plain file system free size is what a database write
 * can really use now - the same thing Android's `StatFs` measures. "Capacity for important usage"
 * also counts space iOS may free later, so it can be higher than what a write gets on a full phone.
 * Taking the smaller one errs on the safe side: the closed loop stops before the database cannot be
 * written.
 *
 * If neither can be read, the answer is 0. That closes the loop off and says the disk is full, which
 * is the safe direction.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun availableInternalStorageMb(): Long {
    val home = NSHomeDirectory()
    val important = (NSURL.fileURLWithPath(home)
        .resourceValuesForKeys(listOf(NSURLVolumeAvailableCapacityForImportantUsageKey), null)
        ?.get(NSURLVolumeAvailableCapacityForImportantUsageKey) as? NSNumber)?.longLongValue
    val free = (NSFileManager.defaultManager.attributesOfFileSystemForPath(home, null)?.get(NSFileSystemFreeSize) as? NSNumber)?.longLongValue
    val bytes = listOfNotNull(important, free).minOrNull() ?: 0L
    return bytes / BYTES_PER_MB
}

private const val BYTES_PER_MB = 1048576L
