package app.aaps.core.ui.toast

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.annotation.StringRes

/**
 * Backward-compatible toast helper kept for modules still calling ToastUtils.
 */
object ToastUtils {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun infoToast(context: Context?, text: String) = show(context, text, Toast.LENGTH_SHORT)
    fun infoToast(context: Context?, @StringRes textRes: Int) = show(context, textRes, Toast.LENGTH_SHORT)

    fun okToast(context: Context?, text: String) = show(context, text, Toast.LENGTH_SHORT)
    fun okToast(context: Context?, @StringRes textRes: Int) = show(context, textRes, Toast.LENGTH_SHORT)

    fun errorToast(context: Context?, text: String) = show(context, text, Toast.LENGTH_LONG)
    fun errorToast(context: Context?, @StringRes textRes: Int) = show(context, textRes, Toast.LENGTH_LONG)

    private fun show(context: Context?, text: String, duration: Int) {
        val safeContext = context ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(safeContext, text, duration).show()
        } else {
            mainHandler.post { Toast.makeText(safeContext, text, duration).show() }
        }
    }

    private fun show(context: Context?, @StringRes textRes: Int, duration: Int) {
        val safeContext = context ?: return
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(safeContext, textRes, duration).show()
        } else {
            mainHandler.post { Toast.makeText(safeContext, textRes, duration).show() }
        }
    }
}
