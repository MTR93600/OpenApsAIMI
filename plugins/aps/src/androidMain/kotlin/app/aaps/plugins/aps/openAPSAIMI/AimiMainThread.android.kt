package app.aaps.plugins.aps.openAPSAIMI

import android.os.Looper

/**
 * Android half of [aimiIsMainThread], on `Looper`.
 *
 * This is the exact check the activity provider used before it moved to shared code.
 */
actual fun aimiIsMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
