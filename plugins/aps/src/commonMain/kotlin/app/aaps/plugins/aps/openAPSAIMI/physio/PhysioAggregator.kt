package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import dev.zacsweers.metro.AppScope

/**
 * 🧮 Physio Aggregator
 * 
 * Computes sliding window metrics (last 15m, 60m) from valid data streams.
 * Holds small in-memory buffers of recent samples (Steps, HR) to provide specific window sums/averages.
 */
@SingleIn(AppScope::class)
class PhysioAggregator @Inject constructor() {

    private data class TimestampedValue(val ts: Long, val value: Double)

    // Buffers
    private val stepBuffer = ArrayDeque<TimestampedValue>()
    private val hrBuffer = ArrayDeque<TimestampedValue>()
    private val stepLock = AapsLock()
    private val hrLock = AapsLock()

    // Window Constants
    private val WINDOW_15M = 15 * 60 * 1000L
    private val WINDOW_60M = 60 * 60 * 1000L

    /**
     * Ingest a new Step Count increment (delta)
     */
    fun addStepDelta(steps: Int) {
        if (steps <= 0) return
        val now = aimiWallClockMs()
        stepLock.withLock {
            stepBuffer.add(TimestampedValue(now, steps.toDouble()))
            cleanup(stepBuffer, WINDOW_60M, now)
        }
    }

    /**
     * Ingest a new Heart Rate sample
     */
    fun addHeartRate(bpm: Int) {
        if (bpm <= 0) return
        val now = aimiWallClockMs()
        hrLock.withLock {
            hrBuffer.add(TimestampedValue(now, bpm.toDouble()))
            cleanup(hrBuffer, WINDOW_60M, now) // Keep 60m for context if needed
        }
    }

    /**
     * Get Steps sum for last X minutes
     */
    fun getStepsLast(minutes: Int): Int {
        val windowMs = minutes * 60 * 1000L
        val now = aimiWallClockMs()
        val threshold = now - windowMs
        
        return stepLock.withLock {
            stepBuffer.filter { it.ts >= threshold }.sumOf { it.value }.toInt()
        }
    }

    /**
     * Get Average HR for last X minutes
     */
    fun getHrAverage(minutes: Int): Int {
        val windowMs = minutes * 60 * 1000L
        val now = aimiWallClockMs()
        val threshold = now - windowMs
        
        return hrLock.withLock {
            val samples = hrBuffer.filter { it.ts >= threshold }
            if (samples.isEmpty()) 0
            else samples.map { it.value }.average().toInt()
        }
    }

    private fun cleanup(buffer: ArrayDeque<TimestampedValue>, maxRetentionMs: Long, now: Long) {
        val threshold = now - maxRetentionMs
        while (buffer.isNotEmpty() && buffer.first().ts < threshold) {
            buffer.removeFirst()
        }
    }
    
    fun clear() {
        stepLock.withLock { stepBuffer.clear() }
        hrLock.withLock { hrBuffer.clear() }
    }
}
