package app.aaps.plugins.source.notificationreader

import app.aaps.plugins.source.notificationreader.GlucoseDeduplicator.Companion.MIN_ACCEPT_GAP_MS
import app.aaps.plugins.source.notificationreader.GlucoseDeduplicator.Companion.SNAP_UP_CONSECUTIVE
import app.aaps.plugins.source.notificationreader.GlucoseDeduplicator.Companion.snapGapToKnownIntervalMs
import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-package deduplication for glucose readings extracted from CGM notifications.
 *
 * The same CGM notification is often re-posted multiple times during a single sensor cycle,
 * producing duplicate readings. This class enforces a per-package interval window for
 * **same-value** reposts: any reading with the same mg/dL within that window is rejected.
 *
 * **Value-aware accept (guarded):** if the parsed glucose **changed** within the sensor window
 * but the gap is still ≥ [MIN_ACCEPT_GAP_MS] (just above `BgQualityCheck`’s 20s DOUBLED
 * threshold), the reading is accepted so AAPS does not lag behind an already-updated
 * notification shade. Gaps under [MIN_ACCEPT_GAP_MS] are always rejected — even on value
 * change — so Dexcom transition glitches cannot insert two GVs a few seconds apart and force
 * CLOSED_LOOP_LGS / max IOB 0.
 *
 * Short-gap accepts (value change ≥ [MIN_ACCEPT_GAP_MS]) do **not** participate in interval
 * snap-up (seed remains a hard floor).
 *
 * Adaptation:
 *  - **Snap up** (longer interval) — requires [SNAP_UP_CONSECUTIVE] consecutive gaps that snap to the
 *    same larger known interval, with no shorter gaps interrupting. This covers seed-too-low
 *    cases (e.g. default 5 min for an actual 15-min sensor).
 *  - **No snap down.** The configured seed (or default) is a hard floor.
 *
 * Known intervals: 1, 3, 5, 15 minutes (mapped via [snapGapToKnownIntervalMs]).
 */
class GlucoseDeduplicator(
    private val packageConfig: PackageConfig,
    private val store: StateStore,
    private val defaultIntervalMs: Long = DEFAULT_INTERVAL_MS
) {

    interface StateStore {

        fun load(): String?
        fun save(json: String)
    }

    private data class State(
        var lastAcceptedTimestamp: Long,
        var intervalMs: Long,
        var pendingLongerIntervalMs: Long,
        var consecutiveLongGapCount: Int,
        var lastGlucoseMgdl: Int?,
    )

    private val states: MutableMap<String, State> = loadStates()

    /**
     * Returns true if the reading should be accepted (and persists state). Returns false for
     * a detected duplicate. Caller must only invoke this after parsing a valid glucose value.
     *
     * @param glucoseMgdl parsed glucose in mg/dL (used for same-value vs value-change decisions)
     */
    @Synchronized
    fun process(packageName: String, now: Long, glucoseMgdl: Int): Boolean {
        val state = states[packageName]
        if (state == null) {
            val seed = packageConfig.intervalForPackage(packageName, defaultIntervalMs)
            states[packageName] = State(now, seed, 0L, 0, glucoseMgdl)
            persist()
            return true
        }

        val gap = now - state.lastAcceptedTimestamp
        val threshold = state.intervalMs - state.intervalMs / 5
        val sameValue = state.lastGlucoseMgdl != null && state.lastGlucoseMgdl == glucoseMgdl

        if (gap < threshold) {
            // Absolute floor vs BgQualityCheck DOUBLED (≤20s) — reject even on value change.
            if (gap < MIN_ACCEPT_GAP_MS) return false
            // Migrated / incomplete state without "v": treat as time-only until a long-gap re-seed.
            if (state.lastGlucoseMgdl == null) return false
            // Same-value repost inside the sensor window → duplicate noise.
            if (sameValue) return false
            // Notification already shows a new BG after a safe gap: accept without adapting interval.
            state.lastAcceptedTimestamp = now
            state.lastGlucoseMgdl = glucoseMgdl
            persist()
            return true
        }

        val snapped = snapGapToKnownIntervalMs(gap)
        when {
            snapped > state.intervalMs -> {
                if (snapped == state.pendingLongerIntervalMs) {
                    state.consecutiveLongGapCount++
                    if (state.consecutiveLongGapCount >= SNAP_UP_CONSECUTIVE) {
                        state.intervalMs = snapped
                        state.pendingLongerIntervalMs = 0L
                        state.consecutiveLongGapCount = 0
                    }
                } else {
                    state.pendingLongerIntervalMs = snapped
                    state.consecutiveLongGapCount = 1
                }
            }

            else                       -> {
                state.pendingLongerIntervalMs = 0L
                state.consecutiveLongGapCount = 0
            }
        }

        state.lastAcceptedTimestamp = now
        state.lastGlucoseMgdl = glucoseMgdl
        persist()
        return true
    }

    /** Currently-effective interval for a package (for diagnostics/tests). */
    fun currentIntervalMs(packageName: String): Long =
        states[packageName]?.intervalMs
            ?: packageConfig.intervalForPackage(packageName, defaultIntervalMs)

    private fun persist() {
        val root = JSONArray()
        for ((pkg, s) in states) {
            val o = JSONObject()
                .put("p", pkg)
                .put("t", s.lastAcceptedTimestamp)
                .put("i", s.intervalMs)
                .put("pi", s.pendingLongerIntervalMs)
                .put("c", s.consecutiveLongGapCount)
            s.lastGlucoseMgdl?.let { o.put("v", it) }
            root.put(o)
        }
        store.save(root.toString())
    }

    private fun loadStates(): MutableMap<String, State> {
        val raw = store.load() ?: return mutableMapOf()
        if (raw.isBlank()) return mutableMapOf()
        return try {
            val arr = JSONArray(raw)
            val map = mutableMapOf<String, State>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                map[o.getString("p")] = State(
                    lastAcceptedTimestamp = o.getLong("t"),
                    intervalMs = o.getLong("i"),
                    pendingLongerIntervalMs = o.optLong("pi", 0L),
                    consecutiveLongGapCount = o.optInt("c", 0),
                    lastGlucoseMgdl = if (o.has("v")) o.getInt("v") else null,
                )
            }
            map
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    companion object {

        const val DEFAULT_INTERVAL_MS = 5 * 60_000L
        const val SNAP_UP_CONSECUTIVE = 3

        /**
         * Hard floor for any accept after the first reading for a package.
         * Must stay **above** `BgQualityCheck` DOUBLED window (20s) so two notification
         * inserts cannot force LGS / max IOB 0.
         */
        const val MIN_ACCEPT_GAP_MS = 21_000L

        /**
         * Snap a measured gap to the nearest known sensor interval using fixed thresholds.
         * Used for snap-up detection only (seed is a hard floor, so shorter bands are only
         * reached when a package is seeded with a low interval).
         */
        fun snapGapToKnownIntervalMs(gapMs: Long): Long = when {
            gapMs <= 2 * 60_000L  -> 1 * 60_000L
            gapMs <= 4 * 60_000L  -> 3 * 60_000L
            gapMs <= 10 * 60_000L -> 5 * 60_000L
            else                  -> 15 * 60_000L
        }
    }
}
