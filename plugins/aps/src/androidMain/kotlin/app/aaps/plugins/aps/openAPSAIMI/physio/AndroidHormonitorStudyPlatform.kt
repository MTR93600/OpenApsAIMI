package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import java.io.File
import java.security.MessageDigest

/**
 * Android half of [HormonitorStudyPlatform]. Every answer is the code the exporter had before it
 * moved to shared code, unchanged, so the files, their folders and the dataset id stay the same.
 */
class AndroidHormonitorStudyPlatform(private val context: Context) : HormonitorStudyPlatform {

    private val sharedDir = File(Environment.getExternalStorageDirectory().absolutePath + "/Documents/AAPS")
    private val appScopedDir = File(context.getExternalFilesDir(null), "AAPS")

    override fun uptimeMs(): Long = SystemClock.uptimeMillis()

    override fun appVersion(): String =
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "unknown"
        } catch (_: Exception) {
            "unknown"
        }

    override fun datasetIdSeed(): String {
        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        } catch (_: Exception) {
            ""
        }
        return "${context.packageName}|$androidId"
    }

    override fun sha256Hex(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.joinToString(separator = "") { eachByte -> (eachByte.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    override fun primaryFile(name: String): AimiPath = AimiPath(File(sharedDir, name).absolutePath)

    override fun fallbackFile(name: String): AimiPath = AimiPath(File(appScopedDir, name).absolutePath)
}
