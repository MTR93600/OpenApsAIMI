package app.aaps.plugins.aps.openAPSAIMI

import java.io.File

/** Where the build puts the shipped model in the Java resources (see `plugins/aps/build.gradle.kts`). */
internal const val BUNDLED_UAM_MODEL_RESOURCE = "/aimi/modelUAM.tflite"

/**
 * Android: the model ships as a Java resource, so it is read without a `Context`, and goes to the file
 * [AimiModelHandler] reads. The app writes it itself, which is what makes it readable under scoped storage.
 */
internal actual fun installBundledUamModel(): AimiUamModelInstall.Outcome {
    val target = AimiModelHandler.modelUamFile
    return AimiUamModelInstall.install(
        bundled = AimiModelHandler::class.java.getResourceAsStream(BUNDLED_UAM_MODEL_RESOURCE)?.use { it.readBytes() },
        current = { if (target.exists()) target.readBytes() else null },
        write = { bytes -> writeAtomically(target, bytes) },
    )
}

/** A start cut short in the middle never leaves half a model: the file is renamed into place whole. */
private fun writeAtomically(target: File, bytes: ByteArray) {
    val dir = target.parentFile ?: error("no folder for $target")
    if (!dir.exists() && !dir.mkdirs()) error("cannot create $dir")
    val temp = File(dir, "${target.name}.tmp")
    temp.writeBytes(bytes)
    if (!temp.renameTo(target)) {
        temp.delete()
        error("cannot replace $target")
    }
}
