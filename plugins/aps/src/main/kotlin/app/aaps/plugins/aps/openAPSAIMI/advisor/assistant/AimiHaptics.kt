package app.aaps.plugins.aps.openAPSAIMI.advisor.assistant

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Short vibration on a button press and on saving a setting in the AIMI screens ("Я ем", Assistant).
 * It uses the vibrator directly, so it works also when the system "touch feedback" is off.
 */
object AimiHaptics {

    /** Length of one click, milliseconds. */
    private const val CLICK_MS = 25L

    fun click(context: Context) {
        try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                context.getSystemService(Vibrator::class.java)
            }
            if (vibrator?.hasVibrator() != true) return
            vibrator.vibrate(VibrationEffect.createOneShot(CLICK_MS, VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Exception) {
            // No vibration is not an error.
        }
    }
}
