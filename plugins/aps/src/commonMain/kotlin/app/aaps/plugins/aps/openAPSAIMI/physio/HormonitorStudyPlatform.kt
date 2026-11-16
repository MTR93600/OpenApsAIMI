package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath

/**
 * The few things [AimiHormonitorStudyExporterMTR] needs from the platform. Everything else (the
 * study files, their lines, the day counters, the QA report, the loop black box) is shared code, so
 * an iPhone writes the same files with the same lines as an Android phone.
 *
 * Android: `SystemClock`, `PackageManager`, `ANDROID_ID`, `Documents/AAPS` with the app-scoped
 * fallback, exactly as before the move. iOS: `NSProcessInfo`, `NSBundle`, `identifierForVendor`, and
 * the app's own `Documents/AAPS` for both paths.
 */
interface HormonitorStudyPlatform {

    /** Time since the device started, in ms (`uptime_ms` in the black box lines). */
    fun uptimeMs(): Long

    /** App version name for `app_version`; "unknown" when it cannot be read. */
    fun appVersion(): String

    /**
     * `"<app id>|<device id>"`. It is hashed (with a fixed suffix) into `dataset_id`, so the raw ids
     * never reach a file. On Android it must stay `"<packageName>|<ANDROID_ID>"`, or every phone would
     * start a new dataset id.
     */
    fun datasetIdSeed(): String

    /** Lowercase hex SHA-256 of [input]. */
    fun sha256Hex(input: String): String

    /** Where a study file is written first. */
    fun primaryFile(name: String): AimiPath

    /** Where it is written when the primary place refuses; also where the day state file lives. */
    fun fallbackFile(name: String): AimiPath
}
