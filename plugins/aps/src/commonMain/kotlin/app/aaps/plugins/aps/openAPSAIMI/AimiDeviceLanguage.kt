package app.aaps.plugins.aps.openAPSAIMI

/**
 * The device language as an ISO 639 code, like `fr` or `en`.
 *
 * This exists for one caller:
 * [app.aaps.plugins.aps.openAPSAIMI.hormonitor.viewer.HormonitorLabels], which picks the French or
 * the English wording of a study label from it. That choice read `Locale.getDefault().language`
 * before the file moved to shared code, and `Locale` is a JVM class, so the lookup is the one line
 * that has to stay on the platform.
 *
 * Deliberately the *device* language and not the AAPS language preference. Reading
 * [app.aaps.core.interfaces.resources.TextRefValueRegistry] instead was considered and rejected: on
 * Android that registry is never filled, on purpose - text there comes through AAPT - so its
 * `locale` is null on the one platform this viewer ships on today, and every French user would
 * silently get the English labels. Following the device is what the ported code did, so it is what
 * this keeps doing.
 *
 * @return the language part of the current locale, or an empty string when the platform has none.
 *   Callers compare it with `ignoreCase = true`, because the case of the code is not promised.
 */
expect fun aimiDeviceLanguage(): String
