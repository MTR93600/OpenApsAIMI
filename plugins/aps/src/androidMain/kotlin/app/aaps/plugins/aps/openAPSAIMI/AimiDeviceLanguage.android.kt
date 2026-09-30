package app.aaps.plugins.aps.openAPSAIMI

import java.util.Locale

/**
 * Android half of [aimiDeviceLanguage].
 *
 * This is the exact lookup the viewer labels used before they moved to shared code. `Locale` here is
 * the one Android keeps in step with the app's own language setting, so a user who switches AAPS to
 * French keeps getting the French labels.
 */
actual fun aimiDeviceLanguage(): String = Locale.getDefault().language
