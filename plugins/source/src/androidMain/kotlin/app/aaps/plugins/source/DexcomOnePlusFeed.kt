package app.aaps.plugins.source

/**
 * Where the loop's glucose comes from now: the phone's own link to the sensor, or only the readings
 * the watch sends back (direct to watch, [app.aaps.plugins.source.keys.DexcomOnePlusBooleanKey.WatchReadingsToLoop]).
 *
 * - [PHONE]: the phone got a reading of its own lately. The normal case.
 * - [WATCH_ONLY]: the phone lost the sensor, but the watch still sends readings. The loop runs, but
 *   only while the watch stays near the sensor and the phone. The user must know it.
 * - [NONE]: no recent reading from either. The usual signal loss alarm takes care of it.
 */
internal enum class DexcomOnePlusFeed {

    PHONE,
    WATCH_ONLY,
    NONE;

    companion object {

        /** Two reading intervals and one minute: one missed reading does not change the source. */
        const val FRESH_MS: Long = 11L * 60L * 1000L

        /** Watch only for this long: the user gets an alert with a sound, once per episode. */
        const val WATCH_ONLY_ALERT_MS: Long = 15L * 60L * 1000L

        fun isFresh(readingAtMs: Long?, nowMs: Long): Boolean = readingAtMs != null && nowMs - readingAtMs < FRESH_MS

        fun of(lastPhoneReadingAtMs: Long?, lastWatchReadingAtMs: Long?, nowMs: Long): DexcomOnePlusFeed = when {
            isFresh(lastPhoneReadingAtMs, nowMs) -> PHONE
            isFresh(lastWatchReadingAtMs, nowMs) -> WATCH_ONLY
            else                                 -> NONE
        }
    }
}

/**
 * @param watchOnlySinceMs while [feed] is [DexcomOnePlusFeed.WATCH_ONLY]: the phone's last own reading,
 *   or when the watch-only time was first seen if the phone had none since the app started.
 */
internal data class DexcomOnePlusFeedState(
    val feed: DexcomOnePlusFeed,
    val watchOnlySinceMs: Long? = null,
) {

    /** True when the watch-only time is long enough for the alert with a sound. */
    fun alert(nowMs: Long): Boolean =
        feed == DexcomOnePlusFeed.WATCH_ONLY && watchOnlySinceMs != null && nowMs - watchOnlySinceMs >= DexcomOnePlusFeed.WATCH_ONLY_ALERT_MS
}
