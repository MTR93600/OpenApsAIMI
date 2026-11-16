package app.aaps.plugins.dexcomoneplus.gatt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The driver must not change a link that works: it asks for Android's normal parameters only when the
 * sensor's preferred parameters gave the link a peripheral latency, never during a pump setup
 * back-off, and only a few times per link.
 */
class OnePlusLinkParameterPolicyTest {

    @Test
    fun aNormalLinkGetsNoRequest() {
        // 45 ms / latency 0: every production sensor in the field logs.
        assertFalse(OnePlusLinkParameterPolicy.requestNormalAfterUpdate(peripheralLatency = 0, lowPowerWanted = false, requestsThisLink = 0))
    }

    @Test
    fun theSensorsLatency40GetsARequest() {
        // The pre-soak sensor's PPCP: 50-75 ms, latency 40, timeout 6.5 s.
        assertTrue(OnePlusLinkParameterPolicy.requestNormalAfterUpdate(peripheralLatency = 40, lowPowerWanted = false, requestsThisLink = 0))
    }

    @Test
    fun aPumpSetupBackOffIsNeverUndone() {
        // LOW_POWER itself has a latency; asking for BALANCED would end the lease.
        assertFalse(OnePlusLinkParameterPolicy.requestNormalAfterUpdate(peripheralLatency = 40, lowPowerWanted = true, requestsThisLink = 0))
        assertFalse(OnePlusLinkParameterPolicy.requestNormalAfterUpdate(peripheralLatency = 2, lowPowerWanted = true, requestsThisLink = 0))
    }

    @Test
    fun aPhoneThatNeverHonoursTheRequestIsNotAskedForever() {
        val max = OnePlusLinkParameterPolicy.MAX_REQUESTS_PER_LINK
        assertTrue(OnePlusLinkParameterPolicy.requestNormalAfterUpdate(peripheralLatency = 40, lowPowerWanted = false, requestsThisLink = max - 1))
        assertFalse(OnePlusLinkParameterPolicy.requestNormalAfterUpdate(peripheralLatency = 40, lowPowerWanted = false, requestsThisLink = max))
    }

    @Test
    fun theLogLineUsesMillisecondsLikeTheSnoopLog() {
        // Android reports 48 x 1.25 ms and 650 x 10 ms.
        assertEquals("interval=60.0ms latency=40 timeout=6500ms status=0", OnePlusLinkParameterPolicy.describe(interval = 48, latency = 40, timeout = 650, status = 0))
        assertEquals("interval=45.0ms latency=0 timeout=5000ms status=0", OnePlusLinkParameterPolicy.describe(interval = 36, latency = 0, timeout = 500, status = 0))
    }
}
