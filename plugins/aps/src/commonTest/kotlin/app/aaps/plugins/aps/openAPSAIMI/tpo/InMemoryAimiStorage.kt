package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * An [AimiStorage] that keeps files in a map, for tests that run on every target.
 *
 * `AndroidAimiStorage` is the real one and is `java.io.File` all the way down, so a test that wants
 * to see what was written cannot use it on iOS. Mockito cannot be used either: `commonTest` runs on
 * Native as well, where it does not exist. Hence this hand written fake, as the module's build file
 * asks for.
 *
 * Only the parts the tests here touch really work - naming a file, writing it, reading it back. The
 * rest answers the way an empty directory would.
 */
internal class InMemoryAimiStorage : AimiStorage {

    /** Written files, keyed by the path string, so a test can assert on what landed. */
    val files: MutableMap<String, String> = mutableMapOf()

    override fun directory(): AimiPath = AimiPath("/aimi")

    override fun file(name: String): AimiPath = AimiPath("/aimi/$name")

    override fun file(subdirectory: String, name: String): AimiPath = AimiPath("/aimi/$subdirectory/$name")

    override fun resolve(directory: AimiPath, name: String): AimiPath = AimiPath("${directory.value}/$name")

    override fun sibling(path: AimiPath, suffix: String): AimiPath = AimiPath("${path.value}$suffix")

    override fun exists(path: AimiPath): Boolean = files.containsKey(path.value)

    override fun canRead(path: AimiPath): Boolean = exists(path)

    override fun createDirectories(path: AimiPath): Boolean = true

    override fun createParentDirectories(path: AimiPath): Boolean = true

    override fun createFile(path: AimiPath): Boolean {
        files.getOrPut(path.value) { "" }
        return true
    }

    override fun readText(path: AimiPath): String? = files[path.value]

    override fun readLines(path: AimiPath): List<String> = files[path.value]?.lines() ?: emptyList()

    override fun readFirstLine(path: AimiPath): String? = files[path.value]?.lineSequence()?.firstOrNull()

    override fun forEachLine(path: AimiPath, action: (String) -> Unit): Boolean {
        val text = files[path.value] ?: return false
        text.lineSequence().forEach(action)
        return true
    }

    override fun writeText(path: AimiPath, text: String): Boolean {
        files[path.value] = text
        return true
    }

    override fun appendText(path: AimiPath, text: String): Boolean {
        files[path.value] = files[path.value].orEmpty() + text
        return true
    }

    override fun displayPath(path: AimiPath): String = path.value

    override fun healthReport(): String = "in memory"

    override fun fallbackFile(name: String): AimiPath = file(name)

    override fun delete(path: AimiPath): Boolean = files.remove(path.value) != null

    override fun replaceText(path: AimiPath, text: String): Boolean = writeText(path, text)

    override fun replaceKeepingBackup(path: AimiPath, text: String): Boolean {
        files[path.value]?.let { files["${path.value}.bak"] = it }
        return writeText(path, text)
    }

    override fun readTailLines(path: AimiPath, maxLines: Int): List<String> = readLines(path).takeLast(maxLines)

    override fun sizeBytes(path: AimiPath): Long = (files[path.value]?.length ?: 0).toLong()

    override fun copy(from: AimiPath, to: AimiPath): Boolean {
        val text = files[from.value] ?: return false
        files[to.value] = text
        return true
    }

    override fun lastModifiedMs(path: AimiPath): Long? = if (exists(path)) 0L else null
}
