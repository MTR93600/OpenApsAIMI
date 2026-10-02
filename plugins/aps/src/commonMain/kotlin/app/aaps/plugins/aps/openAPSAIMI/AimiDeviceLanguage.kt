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

/**
 * The device language as a name a person reads, like `français` or `English`.
 *
 * This is the other half of [aimiDeviceLanguage] and it exists for one caller: `AiCoachingService`
 * puts it straight into the coaching prompt, as `Respond in '<name>'`. That line read
 * `Locale.getDefault().displayLanguage` before the file moved to shared code, so the name is what
 * the assistant has always been given and what it has to keep being given. The ISO code from
 * [aimiDeviceLanguage] is deliberately not reused here: swapping `French` for `fr` inside a prompt
 * is a change to the words a model reads, which is exactly what this port must not do.
 *
 * The name is written in the current language itself, the way the JVM renders it, so a French phone
 * gets `français` and not `French`.
 *
 * @return the display name of the current locale's language, or an empty string when the platform
 *   has none.
 */
expect fun aimiDeviceLanguageName(): String
