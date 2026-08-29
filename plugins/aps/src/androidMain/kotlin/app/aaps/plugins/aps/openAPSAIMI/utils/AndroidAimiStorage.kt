package app.aaps.plugins.aps.openAPSAIMI.utils

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import java.io.File

/**
 * Android half of [AimiStorage].
 *
 * It delegates to [AimiStorageHelper] rather than resolving a directory of its own, so there is
 * exactly one storage policy at runtime and one health report. The helper is a singleton and
 * resolves its directory lazily, so constructing this class does no I/O.
 *
 * Every write is wrapped: a failed AIMI log line must never take down a dosing tick.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AndroidAimiStorage @Inject constructor(
    private val helper: AimiStorageHelper
) : AimiStorage {

    private fun fileOf(path: AimiPath): File = File(path.value)

    override fun directory(): AimiPath = AimiPath(helper.getAimiDirectory().absolutePath)

    override fun file(name: String): AimiPath = AimiPath(helper.getAimiFile(name).absolutePath)

    override fun file(subdirectory: String, name: String): AimiPath =
        AimiPath(helper.getAimiFile(subdirectory, name).absolutePath)

    override fun resolve(directory: AimiPath, name: String): AimiPath =
        AimiPath(File(fileOf(directory), name).absolutePath)

    override fun exists(path: AimiPath): Boolean = runCatching { fileOf(path).exists() }.getOrDefault(false)

    override fun canRead(path: AimiPath): Boolean = runCatching { fileOf(path).canRead() }.getOrDefault(false)

    override fun createDirectories(path: AimiPath): Boolean =
        runCatching { fileOf(path).let { it.exists() || it.mkdirs() } }.getOrDefault(false)

    override fun createParentDirectories(path: AimiPath): Boolean =
        runCatching { fileOf(path).parentFile?.let { it.exists() || it.mkdirs() } ?: true }.getOrDefault(false)

    override fun createFile(path: AimiPath): Boolean =
        runCatching { fileOf(path).let { it.exists() || it.createNewFile() } }.getOrDefault(false)

    override fun readText(path: AimiPath): String? = runCatching { fileOf(path).readText() }.getOrNull()

    override fun readLines(path: AimiPath): List<String> =
        runCatching { fileOf(path).readLines() }.getOrDefault(emptyList())

    override fun writeText(path: AimiPath, text: String): Boolean =
        runCatching { fileOf(path).writeText(text); true }.getOrDefault(false)

    override fun appendText(path: AimiPath, text: String): Boolean =
        runCatching { fileOf(path).appendText(text); true }.getOrDefault(false)

    override fun displayPath(path: AimiPath): String = path.value
}
