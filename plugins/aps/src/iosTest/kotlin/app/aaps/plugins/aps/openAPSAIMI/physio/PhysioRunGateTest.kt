package app.aaps.plugins.aps.openAPSAIMI.physio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The start and stop rule of the iOS physio lifecycle. If the gate stays open after stop, or opens
 * with the switch off, the pipeline feeds the baseline when the person asked it not to.
 */
class PhysioRunGateTest {

    private var enabled = true
    private var restoreCalls = 0
    private val lines = mutableListOf<String>()

    private val gate = PhysioRunGate(
        isEnabled = { enabled },
        restoreLastUpdate = { restoreCalls++; 0L },
        log = { lines += it },
    )

    @Test
    fun `a new gate is closed`() {
        assertFalse(gate.isOpen)
    }

    @Test
    fun `start opens the gate and restores the last run time`() {
        gate.start()
        assertTrue(gate.isOpen)
        assertEquals(1, restoreCalls)
    }

    @Test
    fun `starting twice restores once and stays open`() {
        gate.start()
        gate.start()
        assertTrue(gate.isOpen)
        assertEquals(1, restoreCalls)
    }

    @Test
    fun `stop closes the gate`() {
        gate.start()
        gate.stop()
        assertFalse(gate.isOpen)
    }

    @Test
    fun `start after stop opens again and restores again`() {
        gate.start()
        gate.stop()
        gate.start()
        assertTrue(gate.isOpen)
        assertEquals(2, restoreCalls)
    }

    @Test
    fun `start with the assistant off keeps the gate closed`() {
        enabled = false
        gate.start()
        assertFalse(gate.isOpen)
        assertEquals(0, restoreCalls)
    }

    @Test
    fun `start with the assistant off closes a gate that was open`() {
        gate.start()
        enabled = false
        gate.start()
        assertFalse(gate.isOpen)
    }

    @Test
    fun `stop without start is harmless`() {
        gate.stop()
        assertFalse(gate.isOpen)
    }

    @Test
    fun `every decision leaves a log line`() {
        gate.start()
        gate.stop()
        enabled = false
        gate.start()
        assertEquals(3, lines.size)
    }
}
