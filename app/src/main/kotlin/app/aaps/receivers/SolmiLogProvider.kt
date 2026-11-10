package app.aaps.receivers

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Read-only diagnostic export for the paired Solmi package. Never runs pump commands. */
class SolmiLogProvider : ContentProvider() {
    private val writing = AtomicBoolean()
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val app = requireNotNull(context)
        val caller = callingPackage ?: throw SecurityException()
        val fingerprint = "3a7a1105c801df14bcca72bfa06f03146de438d5dbca90699c5e156cf546fac1"
        val cert = fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        if (caller != "ru.solmi.app" || !app.packageManager.hasSigningCertificate(caller, cert, PackageManager.CERT_INPUT_SHA256)) throw SecurityException("Unpaired caller")
        if (mode != "r" || uri.path != "/snapshot") throw FileNotFoundException()
        val path = (LoggerFactory.getILoggerFactory() as LoggerContext).getProperty("EXT_FILES_DIR")
            ?: throw FileNotFoundException("Logging directory unavailable")
        val dir = File(path).canonicalFile
        val files = dir.listFiles().orEmpty().filter { file ->
            file.isFile && file.canonicalFile.parentFile == dir &&
                (file.name == "AndroidAPS.log" || file.name.startsWith("AndroidAPS.") && file.name.endsWith(".zip"))
        }.sortedWith(compareByDescending<File> { it.name == "AndroidAPS.log" }.thenByDescending { it.lastModified() }).take(6)
        if (files.isEmpty() || !writing.compareAndSet(false, true)) throw FileNotFoundException("Logs unavailable or busy")
        val pipe = try { ParcelFileDescriptor.createReliablePipe() } catch (e: Exception) { writing.set(false); throw e }
        Thread {
            val sink = pipe[1]
            val zip = ZipOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(sink))
            try {
                var remaining = 96L * 1024 * 1024
                val plan = files.mapNotNull { file ->
                    val size = file.length()
                    val bytes = minOf(size, remaining)
                    if (bytes <= 0 || bytes < size && file.name != "AndroidAPS.log") null else {
                        remaining -= bytes
                        Triple(file, size, bytes)
                    }
                }
                for ((file, size, bytes) in plan.asReversed()) {
                    zip.putNextEntry(ZipEntry(file.name))
                    file.inputStream().use { input ->
                        input.channel.position(size - bytes)
                        val buf = ByteArray(32768)
                        var left = bytes
                        while (left > 0) {
                            val n = input.read(buf, 0, minOf(left, buf.size.toLong()).toInt())
                            if (n < 0) throw java.io.EOFException()
                            zip.write(buf, 0, n); left -= n
                        }
                    }
                    zip.closeEntry()
                }
                check(remaining < 96L * 1024 * 1024) { "Empty logs" }
                zip.finish()
            } catch (e: Exception) { runCatching { sink.closeWithError("Log snapshot failed") } }
            finally { runCatching { zip.close() }; writing.set(false) }
        }.apply { name = "SolmiLogExport"; start() }
        return pipe[0]
    }

    override fun getType(uri: Uri) = "application/zip"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
