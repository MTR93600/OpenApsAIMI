package app.aaps.plugins.source

/**
 * Where the loop's glucose comes from now: the phone's own link to the sensor, or only the readings
 * the watch sends back (direct to watch, [app.aaps.plugins.source.keys.DexcomOnePlusBooleanKey.WatchReadingsToLoop]).
 *
 * - [PHONE]: the phone got a reading of its own lately. The normal case.
 * - [WATCH_ONLY]: the phone lost the sensor, but the watch still sends readings. The loop runs, but
 *   only while the watch stays near the sensor and the phone. The user must know it.
 * - [STARTING]: the plugin just started and no reading came yet. The times of the last readings
 *   are kept in memory only, so after a restart nothing says where the next one will come from.
 *   Shown until the first reading, or for [FRESH_MS] at most.
 * - [NONE]: no recent reading from either. The usual signal loss alarm takes care of it.
 */
internal enum class DexcomOnePlusFeed {

    PHONE,
    WATCH_ONLY,
    STARTING,
    NONE;

    companion object {

        /** Two reading intervals and one minute: one missed reading does not change the source. */
        const val FRESH_MS: Long = 11L * 60L * 1000L

        /**
         * While the loop runs on the watch, the silent notice shows again when the phone screen
         * turns on, but not more often than this. No sound: glucose still arrives, so there is
         * nothing urgent, and these outages also happen at night. The sound stays with the signal
         * loss alarm, when the watch stops too ([NONE]).
         */
        const val WATCH_ONLY_REMIND_MS: Long = 60L * 60L * 1000L

        /** True when the notice, last shown at [lastShownAtMs], may show again at screen on. */
        fun remindAtScreenOn(lastShownAtMs: Long, nowMs: Long): Boolean = nowMs - lastShownAtMs >= WATCH_ONLY_REMIND_MS

        fun isFresh(readingAtMs: Long?, nowMs: Long): Boolean = readingAtMs != null && nowMs - readingAtMs < FRESH_MS

        /** @param startedAtMs when the plugin started, null when not known (then never [STARTING]) */
        fun of(lastPhoneReadingAtMs: Long?, lastWatchReadingAtMs: Long?, nowMs: Long, startedAtMs: Long? = null): DexcomOnePlusFeed = when {
            isFresh(lastPhoneReadingAtMs, nowMs) -> PHONE
            isFresh(lastWatchReadingAtMs, nowMs) -> WATCH_ONLY
            lastPhoneReadingAtMs == null && lastWatchReadingAtMs == null && isFresh(startedAtMs, nowMs) -> STARTING
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
)
