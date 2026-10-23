package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Android half of [AimiSupportZipFactory]: `java.util.zip` in [directory], which is the app cache
 * directory the `FileProvider` shares from.
 *
 * The same writer the exporter used before the package moved to shared code, so the archive is
 * unchanged on Android: DEFLATED entries with the default level and the current time.
 */
class AndroidAimiSupportZipFactory(private val directory: File) : AimiSupportZipFactory {

    override fun create(fileName: String): AimiSupportZipOutput = JavaZipOutput(File(directory, fileName))

    private class JavaZipOutput(private val file: File) : AimiSupportZipOutput {

        private val out = ZipOutputStream(BufferedOutputStream(FileOutputStream(file)))
        private var finished = false
        private var aborted = false

        override val path: AimiPath = AimiPath(file.absolutePath)

        override fun beginEntry(name: String) {
            out.putNextEntry(ZipEntry(name))
        }

        override fun write(bytes: ByteArray) {
            out.write(bytes)
        }

        override fun endEntry() {
            out.closeEntry()
        }

        override fun finish(): Long {
            out.close()
            finished = true
            return if (file.exists()) file.length() else 0L
        }

        /** Closes the stream and deletes the partial file: it holds health data. */
        override fun abort() {
            if (finished || aborted) return
            aborted = true
            runCatching { out.close() }
            runCatching { file.delete() }
        }
    }
}
