package app.aaps.plugins.aps.openAPSAIMI

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode

/**
 * iOS half of [aimiDeviceLanguage], on `NSLocale`.
 *
 * It really answers. Returning a constant here would be a quiet behaviour change of the kind this
 * port is meant to avoid: the study viewer would show English to a French user and nothing would
 * look broken. `languageCode` gives the same short ISO 639 code the JVM `Locale.getDefault()
 * .language` gives, so the French check on the other side reads the same value on both platforms.
 */
actual fun aimiDeviceLanguage(): String = NSLocale.currentLocale.languageCode
