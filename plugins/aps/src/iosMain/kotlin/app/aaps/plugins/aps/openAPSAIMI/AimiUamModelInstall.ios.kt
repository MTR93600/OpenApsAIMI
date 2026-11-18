package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.ml.IosUamPredictor
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.usePinned
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile

/**
 * iOS: the model ships in the app bundle (the Xcode build phase copies it from
 * `plugins/aps/src/tfliteParity/`) and goes to the file [IosUamPredictor] reads, in the app's
 * Documents folder. Nothing else can put it there: the app does not share its Documents folder.
 */
internal actual fun installBundledUamModel(): AimiUamModelInstall.Outcome {
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
