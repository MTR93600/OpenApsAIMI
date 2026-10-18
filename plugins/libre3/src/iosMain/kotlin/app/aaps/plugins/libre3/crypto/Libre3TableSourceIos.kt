package app.aaps.plugins.libre3.crypto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile

/**
 * iOS source: the app bundle.
 *
 * The table files live once in the Gradle module, under
 * `plugins/libre3/src/commonMain/resources/libre3/`. Android packages that folder as classpath
 * resources. The Xcode project adds the very same folder as a folder reference, so the app bundle
 * carries `libre3/<name>.bin` next to the other resources. There is one copy of the bytes in the
 * repository and both platforms read it, which is what stops the two sides from drifting apart.
 *
 * A missing file still answers null and false rather than made up bytes. That keeps
 * `Libre3RuntimeTables.missingTables` and `Libre3CgmDrivers.realDriverBlockedReason` honest: the
 * driver then refuses to pair and says which file is not there. A test binary is a good example,
 * because it has its own bundle and does not carry the tables.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun libre3ReadTable(path: String): ByteArray? {
    val full = bundlePathOf(path) ?: return null
    val data: NSData = NSData.dataWithContentsOfFile(full) ?: return null
    val size = data.length.toInt()
    if (size == 0) return ByteArray(0)
    return data.bytes?.readBytes(size)
}

internal actual fun libre3HasTable(path: String): Boolean = bundlePathOf(path) != null

/**
 * Turns the shared path, for example `/libre3/phone_cert_162b.bin`, into a path inside the app
 * bundle, or null when the bundle does not carry that file.
 *
 * This only asks the bundle where the file is. It never reads the bytes, because some of these
 * files are hundreds of kilobytes and the answer is needed on every session start.
 */
private fun bundlePathOf(path: String): String? {
    val trimmed = path.trimStart('/')
    val lastSlash = trimmed.lastIndexOf('/')
    if (lastSlash < 0) return NSBundle.mainBundle.pathForResource(name = trimmed, ofType = null)
    val folder = trimmed.substring(0, lastSlash)
    val name = trimmed.substring(lastSlash + 1)
    if (name.isEmpty()) return null
    return NSBundle.mainBundle.pathForResource(name = name, ofType = null, inDirectory = folder)
}
