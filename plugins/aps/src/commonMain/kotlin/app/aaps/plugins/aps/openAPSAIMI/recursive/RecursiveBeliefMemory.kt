package app.aaps.plugins.aps.openAPSAIMI.recursive

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Per-scale belief memory for Belief Echo (MR-7 clause 7).
 */
object RecursiveBeliefMemory {

    private const val RING_SIZE = 12
    private val rings = mutableMapOf<Int, ArrayDeque<BeliefMemoryEntry>>()

    data class BeliefMemoryEntry(
        val belief: Double,
        val terminalMgdl: Double,
        val urgency: Double,
        val timestampMs: Long,
    )

    fun remember(tauMin: Int, belief: Double, terminalMgdl: Double, urgency: Double, nowMs: Long) {
        val ring = rings.getOrPut(tauMin) { ArrayDeque(RING_SIZE) }
        if (ring.size >= RING_SIZE) ring.removeFirst()
        ring.addLast(BeliefMemoryEntry(belief, terminalMgdl, urgency, nowMs))
    }

    fun echo(tauMin: Int): Double {
        val ring = rings[tauMin] ?: return 0.0
        if (ring.size < 3) return 0.5
        val beliefs = ring.map { it.belief }
        val mean = beliefs.average()
        if (mean <= 0.01) return 0.5
        val variance = beliefs.map { (it - mean) * (it - mean) }.average()
        return (1.0 - min(1.0, variance * 4.0)).coerceIn(0.0, 1.0)
    }

    fun clearForTests() {
        rings.clear()
    }
}
