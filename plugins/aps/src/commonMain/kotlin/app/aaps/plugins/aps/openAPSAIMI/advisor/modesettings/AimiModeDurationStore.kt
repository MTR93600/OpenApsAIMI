package app.aaps.plugins.aps.openAPSAIMI.advisor.modesettings

/**
 * Where the mode settings screen keeps the duration of each meal mode, in minutes.
 *
 * The duration is not a setting in the preference tree. On `dev_OAPSAIMI` it lives in its own small
 * store called `aimi_mode_activity`, and only the mode settings screen reads it, to set the length of
 * the mode note it writes. Each platform keeps it in its own store with that name:
 * - Android: the same `SharedPreferences` file as on `dev_OAPSAIMI`, so durations a user already
 *   saved are kept.
 * - iOS: an `AimiKeyValueCache` suite with the same name.
 */
interface AimiModeDurationStore {

    /** The saved duration for [key], or [DEFAULT_MODE_DURATION_MIN] when nothing was saved. */
    fun minutes(key: String): Int

    /** Saves [minutes] for [key]. */
    fun save(key: String, minutes: Int)

    companion object {

        /** The store name, the same on both platforms and on `dev_OAPSAIMI`. */
        const val STORE_NAME = "aimi_mode_activity"

        /** The duration used when nothing was saved, as on `dev_OAPSAIMI`. */
        const val DEFAULT_MODE_DURATION_MIN = 60
    }
}
