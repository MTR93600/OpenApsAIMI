package app.aaps.plugins.aps.openAPSAIMI

import java.util.Locale

/**
 * Desktop half of [aimiDeviceLanguage]. Same `Locale` lookup as the Android half.
 *
 * The two are written out twice rather than shared through a `jvmSharedMain` source set, for the
 * same reason as `aimiWaitMs`: this module has no such source set, and adding one is a much larger
 * change than one duplicated line.
 */
actual fun aimiDeviceLanguage(): String = Locale.getDefault().language

/** Desktop half of [aimiDeviceLanguageName]. Same `Locale` lookup as the Android half. */
actual fun aimiDeviceLanguageName(): String = Locale.getDefault().displayLanguage
