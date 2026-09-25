package app.aaps.plugins.calibration

import app.aaps.core.data.model.CAL
import app.aaps.core.data.model.GV

/**
 * Which stored fingersticks the linear fit is allowed to see.
 *
 * This is the selection half of `LinearCalibrationPlugin.entriesForFit` / `lagPaired` /
 * `ignoreEntriesBefore` on `origin/dev_OAPSAIMI` @ `6598201d26` (introduced by `1b81e356c8`).
 * The plugin still does the database read and the preference write. Nothing here invents a
 * threshold: every constant is copied from that tip.
 *
 * Ref `LinearCalibrationPlugin.kt`:
 * - L283 cutoff `maxOf(sessionStart, EntriesValidFrom)`
 * - L287–290 `ignoreEntriesBefore` advances the cutoff only when the new timestamp is strictly later,
 *   then drops the in-memory lag cache
 * - L298 gate `now - entry.timestamp < PAIR_LAG_WINDOW_MS` keeps the stored pair
 * - L299 cache hit rewrites only the copy the fit sees
 * - L300–304 window `[timestamp, timestamp + PAIR_LAG_WINDOW_MS]`
 * - L305 pair target `timestamp + PAIR_LAG_MS`, median via [sensorValueForPairing]
 * - L521 `PAIR_LAG_MS = 10 min`, L524 `PAIR_LAG_WINDOW_MS = 15 min`
 *
 * ONE+ writes the cutoff at promotion (`DexcomOnePlusPlugin.kt` L934). Libre 3 writes the same
 * cutoff at promotion (`Libre3NativePlugin.kt` L1055). Study Libre 3 has no successful promotion
 * path; that call site is not invented here.
 */
object CalibrationEntriesForFit {

    /** Assumed delay between blood and the fluid the sensor reads. xDrip+ uses the same 10 min. */
    const val PAIR_LAG_MS = 10L * 60L * 1000L

    /** How far past a fingerstick the re-made pair may look, so the lag point is well covered. */
    const val PAIR_LAG_WINDOW_MS = 15L * 60L * 1000L

    /**
     * Lower bound passed to `getValidCalibrationEntriesSince`.
     * A promoted pre-soak session starts before the swap; the cutoff drops fingersticks taken on
     * the sensor that was just retired.
     */
    fun fitCutoff(sessionStart: Long, entriesValidFrom: Long): Long =
        maxOf(sessionStart, entriesValidFrom)

    /** Ref L288: `if (timestamp <= current) return`. */
    fun shouldAdvanceEntriesValidFrom(current: Long, requested: Long): Boolean =
        requested > current

    /**
     * In-memory lag pairs for one sensor session.
     *
     * Not thread-safe on its own. The plugin guards every call with [app.aaps.core.interfaces.concurrent.AapsLock].
     * `ConcurrentHashMap` is what the ref uses (L90) and is JVM-only, so it does not belong in `commonMain`.
     */
    class LagPairCache {
        private var session: Long? = null
        private val values = mutableMapOf<Long, Double>()

        /** Ref L277–280: a new session drops the previous sensor's pairs. */
        fun bindSession(sessionStart: Long) {
            if (session != sessionStart) {
                session = sessionStart
                values.clear()
            }
        }

        fun cached(entryId: Long): Double? = values[entryId]

        fun remember(entryId: Long, sensorMgdl: Double) {
            values[entryId] = sensorMgdl
        }

        /** Ref L290: `ignoreEntriesBefore` clears the pairs and leaves the session id in place. */
        fun dropPairs() {
            values.clear()
        }
    }

    sealed class LagRepairStep {
        /** Stored pair stands, or a cached lag pair is copied onto a new [CAL]. */
        data class Keep(val entry: CAL) : LagRepairStep()

        /** The window after the fingerstick is in the database. Read it, then [finishLagRepair]. */
        data class ReadWindow(val startMs: Long, val endMs: Long, val targetMs: Long) : LagRepairStep()
    }

    /**
     * First half of `lagPaired` (ref L295–299). Does not read the database.
     * [cachedSensorMgdl] is the value already remembered for this entry id, or null.
     */
    fun beginLagRepair(entry: CAL, now: Long, cachedSensorMgdl: Double?): LagRepairStep {
        if (now - entry.timestamp < PAIR_LAG_WINDOW_MS) return LagRepairStep.Keep(entry)
        if (cachedSensorMgdl != null) {
            return LagRepairStep.Keep(entry.copy(sensorMgdlAtPairing = cachedSensorMgdl))
        }
        return LagRepairStep.ReadWindow(
            startMs = entry.timestamp,
            endMs = entry.timestamp + PAIR_LAG_WINDOW_MS,
            targetMs = entry.timestamp + PAIR_LAG_MS
        )
    }

    /**
     * Second half of `lagPaired` (ref L305–310).
     * [storeInCache] is non-null only when a new sensor value was computed. The stored row is never
     * the object returned: the fit sees a copy.
     */
    fun finishLagRepair(entry: CAL, readings: List<GV>, targetMs: Long): LagRepairResult {
        val paired = sensorValueForPairing(readings, targetMs) ?: return LagRepairResult(entry, storeInCache = null)
        return LagRepairResult(entry.copy(sensorMgdlAtPairing = paired), storeInCache = paired)
    }
}

data class LagRepairResult(
    val entry: CAL,
    val storeInCache: Double?
)
