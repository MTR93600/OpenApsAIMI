package app.aaps.core.interfaces.pump.ble.session

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * [IosRadioWake.ping] is called on the CoreBluetooth queue. The listener has to stay off that queue,
 * or a socket open and a UIKit call run inside `didUpdateValueForCharacteristic`.
 */
class IosRadioWakeTest {

    @AfterTest
    fun reset() = IosRadioWake.resetForTests()

    @Test
    fun pingReturnsWithoutRunningTheListener() {
        var ran = false
        var scheduled = 0
        IosRadioWake.enqueue = { scheduled += 1 }
        IosRadioWake.listener = { ran = true }
        IosRadioWake.nowMs = { 10_000L }
        IosRadioWake.ping()
        assertEquals(1, scheduled)
        assertFalse(ran)
    }

    @Test
    fun aSecondPingInsideTheGapIsNotScheduled() {
        var scheduled = 0
        IosRadioWake.enqueue = { scheduled += 1 }
        IosRadioWake.listener = { }
        IosRadioWake.nowMs = { 10_000L }
        IosRadioWake.ping()
        IosRadioWake.ping()
        assertEquals(1, scheduled)
    }

    @Test
    fun aPingAfterTheWindowIsQueued() {
        var scheduled = 0
        IosRadioWake.enqueue = { scheduled += 1 }
        IosRadioWake.listener = { }
        IosRadioWake.nowMs = { 10_000L }
        IosRadioWake.ping()
        IosRadioWake.nowMs = { 10_000L + IosRadioWake.minGapMs }
        IosRadioWake.ping()
        assertEquals(2, scheduled)
    }

    @Test
    fun theQueuedBlockRunsTheListener() {
        var ran = 0
        var queued: (() -> Unit)? = null
        IosRadioWake.enqueue = { block -> queued = block }
        IosRadioWake.listener = { ran += 1 }
        IosRadioWake.nowMs = { 10_000L }
        IosRadioWake.ping()
        assertEquals(0, ran)
        queued?.invoke()
        assertEquals(1, ran)
    }
}
