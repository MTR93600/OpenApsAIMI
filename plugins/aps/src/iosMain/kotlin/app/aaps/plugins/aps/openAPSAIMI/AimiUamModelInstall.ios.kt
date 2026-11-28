package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ml.IosUamPredictor
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import kotlin.concurrent.Volatile
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS: the model ships in the app bundle (the Xcode build phase copies it from
 * `plugins/aps/src/tfliteParity/`) and goes to the file [IosUamPredictor] reads,
 * `Documents/AAPS/ml/modelUAM.tflite` (`IosAapsDirectories.ml`).
 *
 * The Files app shows that folder to the user, so a person could also put a model there or delete
 * it by hand. The next start writes the shipped one again when the file is missing or different.
 */
internal actual fun installBundledUamModel(): AimiUamModelInstall.Outcome {
    installAttempted = true
    val target = IosUamPredictor.modelPath() ?: return AimiUamModelInstall.Outcome.FAILED
    return AimiUamModelInstall.install(
        bundled = NSBundle.mainBundle.pathForResource("modelUAM", "tflite")?.let(::readFile),
        current = { readFile(target) },
        write = { bytes -> write(target, bytes) },
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun readFile(path: String): ByteArray? {
    val data = NSData.dataWithContentsOfFile(path) ?: return null
    return data.bytes?.readBytes(data.length.toInt())
}

/** Atomic, so a start cut short never leaves half a model. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun write(path: String, bytes: ByteArray) {
    val dir = path.substringBeforeLast('/')
    NSFileManager.defaultManager.createDirectoryAtPath(dir, withIntermediateDirectories = true, attributes = null, error = null)
    val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
    if (!data.writeToFile(path, atomically = true)) error("cannot write $path")
}

/** True once [installBundledUamModel] has run in this process, which it does only when AIMI starts. */
@Volatile
private var installAttempted = false

/**
 * True when the model install step ran in this process and the model file is still missing, empty
 * or unreadable afterwards. False when the step did not run (AIMI is not the active loop plugin), so
 * a phone that does not use AIMI is not told about a missing model it does not need.
 *
 * The app start asks this after the plugins have started, to post one notification.
 */
fun isIosUamModelMissingAfterInstall(): Boolean {
    if (!installAttempted) return false
    val path = IosUamPredictor.modelPath() ?: return true
    return readFile(path)?.isNotEmpty() != true
}
