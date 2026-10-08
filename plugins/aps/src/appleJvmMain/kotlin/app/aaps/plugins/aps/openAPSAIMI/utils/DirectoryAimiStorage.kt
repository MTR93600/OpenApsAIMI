package app.aaps.plugins.aps.openAPSAIMI.utils

/**
 * [AimiStorage] over one directory.
 *
 * The relative names are Android's: `tpo/tpo_session.json`, `aimi_basal_learner.json`. The
 * directory itself is the platform's AIMI folder. A failed write answers `false` and leaves the
 * previous file in place when the failure happened before the rename.
 */
internal class DirectoryAimiStorage(
    private val root: String,
    private val io: AimiLocalFiles = aimiLocalFiles(),
) : AimiStorage {

    override fun directory(): AimiPath {
        io.createDirectories(root)
        return AimiPath(root)
    }

    override fun file(name: String): AimiPath {
        io.createDirectories(root)
        return AimiPath(join(root, name))
    }

    override fun file(subdirectory: String, name: String): AimiPath {
        val dir = join(root, subdirectory)
        io.createDirectories(dir)
        return AimiPath(join(dir, name))
    }

    override fun resolve(directory: AimiPath, name: String): AimiPath = AimiPath(join(directory.value, name))

    override fun sibling(path: AimiPath, suffix: String): AimiPath = AimiPath(path.value + suffix)

    override fun exists(path: AimiPath): Boolean = io.exists(path.value)

    override fun canRead(path: AimiPath): Boolean = io.canRead(path.value)

    override fun canWrite(path: AimiPath): Boolean = io.canWrite(path.value)

    override fun createDirectories(path: AimiPath): Boolean = io.createDirectories(path.value)

    override fun createParentDirectories(path: AimiPath): Boolean {
        val parent = parentOf(path.value) ?: return true
        return io.createDirectories(parent)
    }

    override fun createFile(path: AimiPath): Boolean {
        if (io.exists(path.value)) return true
        createParentDirectories(path)
        return io.writeText(path.value, "")
    }

    override fun readText(path: AimiPath): String? = io.readText(path.value)

    override fun readLines(path: AimiPath): List<String> {
        val text = io.readText(path.value) ?: return emptyList()
        if (text.isEmpty()) return emptyList()
        return text.removeSuffix("\n").removeSuffix("\r").split(Regex("\r?\n"))
    }

    override fun readFirstLine(path: AimiPath): String? = readLines(path).firstOrNull()

    override fun forEachLine(path: AimiPath, action: (String) -> Unit): Boolean {
        if (!io.exists(path.value)) return false
        return runCatching {
            readLines(path).forEach(action)
            true
        }.getOrDefault(false)
    }

    override fun writeText(path: AimiPath, text: String): Boolean {
        createParentDirectories(path)
        return io.writeText(path.value, text)
    }

    override fun appendText(path: AimiPath, text: String): Boolean {
        createParentDirectories(path)
        return io.appendText(path.value, text)
    }

    override fun displayPath(path: AimiPath): String = path.value

    override fun healthReport(): String = "directory=$root"

    override fun fallbackFile(name: String): AimiPath = file(name)

    override fun delete(path: AimiPath): Boolean = io.delete(path.value)

    override fun replaceText(path: AimiPath, text: String): Boolean {
        createParentDirectories(path)
        val tmp = sibling(path, ".tmp")
        if (!io.writeText(tmp.value, text)) return false
        val replaced = io.rename(tmp.value, path.value)
        if (!replaced) io.delete(tmp.value)
        return replaced
    }

    override fun replaceKeepingBackup(path: AimiPath, text: String): Boolean {
        createParentDirectories(path)
        val tmp = sibling(path, ".tmp")
        val bak = sibling(path, ".bak")
        if (!io.writeText(tmp.value, text)) return false
        if (io.exists(path.value)) {
            io.delete(bak.value)
            if (!io.rename(path.value, bak.value)) {
                io.delete(tmp.value)
                return false
            }
        }
        val replaced = io.rename(tmp.value, path.value)
        if (!replaced) io.delete(tmp.value)
        return replaced
    }

    override fun rewriteLines(path: AimiPath, temporary: AimiPath, lines: Sequence<String>): Boolean {
        createParentDirectories(path)
        val text = buildString {
            lines.forEach { line ->
                append(line)
                append('\n')
            }
        }
        if (!io.writeText(temporary.value, text)) return false
        val replaced = io.rename(temporary.value, path.value)
        if (!replaced) {
            val copied = io.readText(temporary.value)?.let { io.writeText(path.value, it) } == true
            io.delete(temporary.value)
            return copied
        }
        return true
    }

    override fun readTailLines(path: AimiPath, maxLines: Int): List<String> {
        if (maxLines <= 0) return emptyList()
        return readLines(path).takeLast(maxLines).asReversed()
    }

    override fun sizeBytes(path: AimiPath): Long = io.length(path.value)

    /**
     * The final byte, widened to a `Char` the way `java.io.RandomAccessFile.read` gives it on Android,
     * so a file ending in a multi byte character answers with its raw last byte on every target.
     */
    override fun lastChar(path: AimiPath): Char? = io.lastByte(path.value)?.toChar()

    override fun copy(from: AimiPath, to: AimiPath): Boolean {
        val text = io.readText(from.value) ?: return false
        createParentDirectories(to)
        return io.writeText(to.value, text)
    }

    override fun lastModifiedMs(path: AimiPath): Long? = io.lastModifiedMs(path.value)

    private fun join(directory: String, name: String): String = directory.trimEnd('/') + "/" + name

    private fun parentOf(path: String): String? {
        val slash = path.lastIndexOf('/')
        if (slash <= 0) return null
        return path.substring(0, slash)
    }
}
