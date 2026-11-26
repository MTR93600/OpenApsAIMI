package app.aaps.wear.watchdirect

import app.aaps.core.interfaces.rx.weardata.EventData
import java.util.TreeMap

/**
 * The readings the watch took from the sensor itself and the phone does not have yet.
 *
 * They go to the phone only when the phone asked for them ([EventData.CgmWatchDirect.sendReadings]).
 * Then the loop keeps running when only the watch hears the sensor. The phone checks each reading
 * again before the loop gets it.
 *
 * The answer that a reading arrived is the phone's own newest reading: the phone sends it to the
 * watch after each new value. Every reading at that time or older leaves the list. A hole older
 * than the phone's newest reading is filled by the phone itself, with the sensor's backfill at its
 * next connection.
 *
 * Kept in memory only, at most [WatchDirectReadings.GRAPH_WINDOW_MS] long. Collected also while the
 * phone has not asked yet, so the readings of a watch app restart are not lost before the phone's
 * first message.
 *
 * Not thread safe: [WatchDirect] calls it from its one thread.
 */
class WatchReadingsForPhone {

    private val pending = TreeMap<Long, EventData.CgmWatchReading>()

    /** The newest reading the phone has, the reading's own time. */
    private var phoneNewest = 0L

    /** True while the phone wants the readings. */
    var wanted: Boolean = false
        private set

    fun setWanted(wanted: Boolean) {
        this.wanted = wanted
        if (!wanted) pending.clear()
    }

    /** The watch follows another sensor: the readings of the old one must not go to the phone. */
    fun clear() {
        pending.clear()
        phoneNewest = 0L
    }

    /** @return true when the reading is new for the phone */
    fun add(reading: WatchSensorReading, now: Long): Boolean {
        val at = reading.timestampMs
        if (at <= phoneNewest + SAME_READING_MS) return false
        // The sensor's backfill gives the same reading a few seconds off the live one.
        if (pending.subMap(at - SAME_READING_MS, true, at + SAME_READING_MS, true).isNotEmpty()) return false
        pending[reading.timestampMs] = EventData.CgmWatchReading(reading.timestampMs, reading.mgdl, reading.slopeMgdlPerMin)
        dropOld(now)
        return true
    }

    /** The phone has a reading at [readingAt]: it does not need the watch's readings up to that time. */
    fun phoneHas(readingAt: Long) {
        if (readingAt <= phoneNewest) return
        phoneNewest = readingAt
        pending.headMap(readingAt + SAME_READING_MS, true).clear()
    }

    /** What to send to the phone now, oldest first. Empty when the phone did not ask, or has them all. */
    fun toSend(now: Long): List<EventData.CgmWatchReading> {
        if (!wanted) return emptyList()
        dropOld(now)
        return pending.values.toList()
    }

    private fun dropOld(now: Long) {
        pending.headMap(now - WatchDirectReadings.GRAPH_WINDOW_MS, false).clear()
    }

    companion object {

        /**
         * Two times closer than this are the same reading: the sensor sends one about every 5
         * minutes, and its backfill gives the live reading a few seconds off (field log 26/11/2026).
         */
        const val SAME_READING_MS: Long = 60_000L
    }
}
