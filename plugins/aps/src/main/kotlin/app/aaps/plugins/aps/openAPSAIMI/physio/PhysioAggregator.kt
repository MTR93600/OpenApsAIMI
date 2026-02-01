package app.aaps.plugins.aps.openAPSAIMI.physio

import javax.inject.Inject
import javax.inject.Singleton
import java.util.LinkedList

/**
 * 🧮 Physio Aggregator
 * 
 * Computes sliding window metrics (last 15m, 60m) from valid data streams.
 * Holds small in-memory buffers of recent samples (Steps, HR) to provide specific window sums/averages.
 */
@Singleton
class PhysioAggregator @Inject constructor() {

    private data class TimestampedValue(val ts: Long, val value: Double)

    // Buffers
    private val stepBuffer = LinkedList<TimestampedValue>()
    private val hrBuffer = LinkedList<TimestampedValue>()

    // Window Constants
    private val WINDOW_15M = 15 * 60 * 1000L
    private val WINDOW_60M = 60 * 60 * 1000L

    /**
     * Ingest a new Step Count increment (delta)
     */
    fun addStepDelta(steps: Int) {
        if (steps <= 0) return
        val now = System.currentTimeMillis()
        synchronized(stepBuffer) {
            stepBuffer.add(TimestampedValue(now, steps.toDouble()))
            cleanup(stepBuffer, WINDOW_60M)
        }
    }

    /**
     * Ingest a new Heart Rate sample
     */
    fun addHeartRate(bpm: Int) {
        if (bpm <= 0) return
        val now = System.currentTimeMillis()
        synchronized(hrBuffer) {
            hrBuffer.add(TimestampedValue(now, bpm.toDouble()))
            cleanup(hrBuffer, WINDOW_60M) // Keep 60m for context if needed
        }
    }

    /**
     * Get Steps sum for last X minutes
     */
    fun getStepsLast(minutes: Int): Int {
        val windowMs = minutes * 60 * 1000L
        val now = System.currentTimeMillis()
        val threshold = now - windowMs
        
        synchronized(stepBuffer) {
            return stepBuffer.filter { it.ts >= threshold }.sumOf { it.value }.toInt()
        }
    }

    /**
     * Get Average HR for last X minutes
     */
    fun getHrAverage(minutes: Int): Int {
        val windowMs = minutes * 60 * 1000L
        val now = System.currentTimeMillis()
        val threshold = now - windowMs
        
        synchronized(hrBuffer) {
            val samples = hrBuffer.filter { it.ts >= threshold }
            if (samples.isEmpty()) return 0
            return samples.map { it.value }.average().toInt()
        }
    }

    private fun cleanup(buffer: LinkedList<TimestampedValue>, maxRetentionMs: Long) {
        val now = System.currentTimeMillis()
        val threshold = now - maxRetentionMs
        while (buffer.isNotEmpty() && buffer.first.ts < threshold) {
            buffer.removeFirst()
        }
    }
    
    fun clear() {
        synchronized(stepBuffer) { stepBuffer.clear() }
        synchronized(hrBuffer) { hrBuffer.clear() }
    }
}
