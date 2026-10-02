package app.aaps.plugins.aps.openAPSAIMI

import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.localizedStringForLanguageCode

/**
 * iOS half of [aimiDeviceLanguage], on `NSLocale`.
 *
 * It really answers. Returning a constant here would be a quiet behaviour change of the kind this
 * port is meant to avoid: the study viewer would show English to a French user and nothing would
 * look broken. `languageCode` gives the same short ISO 639 code the JVM `Locale.getDefault()
 * .language` gives, so the French check on the other side reads the same value on both platforms.
 */
actual fun aimiDeviceLanguage(): String = NSLocale.currentLocale.languageCode

/**
 * iOS half of [aimiDeviceLanguageName], on `NSLocale`.
 *
 * `localizedStringForLanguageCode` writes the name in the current locale itself, which is what the
 * JVM `displayLanguage` does, so both platforms hand the prompt the same kind of word. It answers
 * null for a code it does not know, and an empty string is then the honest reply - the same reply
 * [aimiDeviceLanguage] promises when the platform has no language to give.
 */
actual fun aimiDeviceLanguageName(): String =
    NSLocale.currentLocale.localizedStringForLanguageCode(NSLocale.currentLocale.languageCode) ?: ""
