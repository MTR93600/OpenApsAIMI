package app.aaps.plugins.aps.openAPSAIMI.orchestration

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the phase ring of [AimiLoopTelemetry]: how many lines it keeps, which one it drops first and
 * what a reader gets back.
 *
 * These are the parts a lock swap can change without anything else noticing, so they live in
 * `commonTest` and run on the JVM, on Android and on the iOS simulator.
 */
class AimiLoopTelemetryRingTest {

    private val phases = AimiLoopPhase.entries

    /** The ring is a singleton, so put the shared phase back where the object starts. */
    @AfterTest
    fun restoreStartPhase() {
        AimiLoopTelemetry.enterPhase(AimiLoopPhase.BOOTSTRAP, null)
    }

    @Test
    fun ringKeepsTheNewestLinesAndDropsTheOldestFirst() {
        val written = fillRing(300)
        val tail = AimiLoopTelemetry.ringSnapshotTail(RING_MAX)
        assertEquals(RING_MAX, tail.size, "the ring must be capped")
        assertEquals(written.takeLast(RING_MAX), tail.map { phaseNameOf(it) }, "eviction must drop the oldest line first")
    }

    @Test
    fun aShortTailReturnsTheLastLinesInWriteOrder() {
        val written = fillRing(300)
        val tail = AimiLoopTelemetry.ringSnapshotTail(5)
        assertEquals(5, tail.size)
        assertEquals(written.takeLast(5), tail.map { phaseNameOf(it) })
    }

    @Test
    fun theAskedForLineCountIsClampedToTheRingBounds() {
        fillRing(300)
        assertEquals(1, AimiLoopTelemetry.ringSnapshotTail(0).size, "0 is clamped up to 1")
        assertEquals(1, AimiLoopTelemetry.ringSnapshotTail(-7).size, "a negative count is clamped up to 1")
        assertEquals(RING_MAX, AimiLoopTelemetry.ringSnapshotTail(10_000).size, "a huge count is clamped down")
    }

    @Test
    fun aPhaseLineCarriesTheStampTheTickIdAndThePhaseName() {
        AimiLoopTelemetry.enterPhase(AimiLoopPhase.CORE_DECISION, null)
        val line = AimiLoopTelemetry.ringSnapshotTail(1).single()
        val stamp = line.substringBefore(' ')
        assertTrue(stamp.toLongOrNull() != null, "line must start with the wall clock stamp: $line")
        assertTrue(line.contains(" phase id=0 CORE_DECISION wall_ms="), "unexpected line: $line")
        assertTrue(line.contains(" ms_since_tick=-1 "), "no tick is open, so the age must be -1: $line")
    }

    /** Writes [count] phase lines and returns the phase names in write order. */
    private fun fillRing(count: Int): List<String> {
        val written = ArrayList<String>(count)
        repeat(count) { index ->
            val phase = phases[index % phases.size]
            AimiLoopTelemetry.enterPhase(phase, null)
            written.add(phase.name)
        }
        return written
    }

    private fun phaseNameOf(line: String): String = line.substringAfter("phase id=0 ").substringBefore(' ')

    private companion object {

        /** Mirrors the private ring cap of `AimiLoopTelemetry`. */
        const val RING_MAX = 128
    }
}
