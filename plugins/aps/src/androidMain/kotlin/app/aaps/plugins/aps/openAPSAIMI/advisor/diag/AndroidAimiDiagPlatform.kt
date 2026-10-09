package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import android.content.Context
import android.os.Build
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiDiagPlatform
import java.security.MessageDigest

/** [AimiDiagPlatform] backed by Android APIs. */
class AndroidAimiDiagPlatform(private val context: Context) : AimiDiagPlatform {

    override fun appVersionName(): String = try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        pInfo.versionName ?: "Unknown"
    } catch (_: Exception) {
        "Unknown"
    }

    override fun appVersionCode(): Long = try {
        val pInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pInfo.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pInfo.versionCode.toLong()
        }
    } catch (_: Exception) {
        0L
    }

    override fun osInfo(): String = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

    override fun deviceInfo(): String = "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun allPreferences(): Map<String, Any?> {
        val prefs = context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
        return prefs.all.mapValues { it.value }
    }

    override fun sha256Hex(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }
}
