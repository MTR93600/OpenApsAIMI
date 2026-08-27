package app.aaps.plugins.aps.openAPSAIMI.trajectory

import app.aaps.core.data.iob.InMemoryGlucoseValue

/**
 * CGM sample → phase-space derivatives used by [TrajectoryHistoryProvider].
 *
 * Kept as a pure object so synthetic series can be unit-tested (RFC C.3) without DB/IOB.
 */
object TrajectoryBgDerivatives {

    /**
     * First derivative proxy: ΔBG per 5 minutes at [timestamp], from bucketed readings.
     */
    fun deltaAt(timestamp: Long, allReadings: List<InMemoryGlucoseValue>): Double {
        val current = allReadings.find { it.timestamp == timestamp } ?: return 0.0
        val previous = allReadings
            .filter { it.timestamp < timestamp }
            .maxByOrNull { it.timestamp }

        if (previous != null) {
            val timeDiffMin = (timestamp - previous.timestamp) / 60_000.0
            if (timeDiffMin > 0 && timeDiffMin <= 10.0) {
                return ((current.recalculated - previous.recalculated) / timeDiffMin) * 5.0
            }
        }

        return 0.0
    }

    /**
     * Second derivative proxy (acceleration), normalized per 5-minute² step.
     */
    fun accelAt(timestamp: Long, allReadings: List<InMemoryGlucoseValue>): Double {
        val idx = allReadings.indexOfFirst { it.timestamp == timestamp }
        if (idx < 0 || idx >= allReadings.size - 1) return 0.0

        val curr = allReadings[idx]
        val next = allReadings[idx + 1]

        val delta1 = deltaAt(curr.timestamp, allReadings)
        val delta2 = deltaAt(next.timestamp, allReadings)

        val timeDiff = (next.timestamp - curr.timestamp) / 60_000.0

        return if (timeDiff > 0) {
            ((delta2 - delta1) / timeDiff) * 5.0
        } else {
            0.0
        }
    }
}
