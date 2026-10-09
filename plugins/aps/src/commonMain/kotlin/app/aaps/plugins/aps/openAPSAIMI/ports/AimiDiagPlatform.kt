package app.aaps.plugins.aps.openAPSAIMI.ports

/**
 * Platform answers for the AIMI diagnostic report.
 *
 * Android: `PackageManager` + `Build` + `SharedPreferences`.
 * iOS: `Bundle.main` + `UIDevice`.
 */
interface AimiDiagPlatform {
    /** e.g. "1.2.3". */
    fun appVersionName(): String

    /** e.g. 123. */
    fun appVersionCode(): Long

    /** e.g. "Android 14 (SDK 34)". */
    fun osInfo(): String

    /** e.g. "Google Pixel 8". */
    fun deviceInfo(): String

    /** Raw key/value dump of the app preferences, for the report's filtered section. */
    fun allPreferences(): Map<String, Any?>

    /** Lowercase hex SHA-256 of [input]. */
    fun sha256Hex(input: String): String
}
