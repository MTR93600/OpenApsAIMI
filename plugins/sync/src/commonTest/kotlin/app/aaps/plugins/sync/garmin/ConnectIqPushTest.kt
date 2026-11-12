package app.aaps.plugins.sync.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectIqPushTest {

    private var now = 0L
    private var on = true
    private val push = ConnectIqPush(nowMs = { now }, enabled = { on })
    private val watch = listOf("WATCH")
    private val apps = listOf("APP")

    @Test
    fun switchOffSendsNothing() {
        on = false
        val plan = push.request(watch, apps)
        assertTrue(plan.send.isEmpty())
        assertNull(plan.log)
    }

    @Test
    fun aFailureWaitsAndIsLoggedOnce() {
        assertEquals(1, push.request(watch, apps).send.size)
        val failed = push.onResult("WATCH", "APP", ConnectIqSendResult.FAILURE_TIMEOUT)
        assertTrue(failed.send.isEmpty())
        assertEquals(
            "Connect IQ FAILURE_TIMEOUT device=WATCH app=APP attempt=1 retryInMs=2000",
            failed.log,
        )
        now = 1_999
        assertTrue(push.request(watch, apps).send.isEmpty())
        now = 2_000
        assertEquals(listOf(ConnectIqTarget("WATCH", "APP")), push.request(watch, apps).send)
    }

    @Test
    fun backoffDoublesAndStopsAtOneMinute() {
        assertEquals(2_000L, waitAfter(ConnectIqSendResult.FAILURE_DEVICE_NOT_AVAILABLE))
        assertEquals(4_000L, waitAfter(ConnectIqSendResult.FAILURE_DEVICE_NOT_AVAILABLE))
        assertEquals(8_000L, waitAfter(ConnectIqSendResult.FAILURE_TIMEOUT))
        assertEquals(16_000L, waitAfter(ConnectIqSendResult.FAILURE_TIMEOUT))
        assertEquals(32_000L, waitAfter(ConnectIqSendResult.FAILURE_TIMEOUT))
        assertEquals(60_000L, waitAfter(ConnectIqSendResult.FAILURE_TIMEOUT))
        assertEquals(60_000L, waitAfter(ConnectIqSendResult.FAILURE_TIMEOUT))
    }

    @Test
    fun successClearsTheWait() {
        push.request(watch, apps)
        push.onResult("WATCH", "APP", ConnectIqSendResult.FAILURE_TIMEOUT)
        now = 2_000
        push.request(watch, apps)
        push.onResult("WATCH", "APP", ConnectIqSendResult.SUCCESS)
        assertEquals(1, push.request(watch, apps).send.size)
    }

    @Test
    fun aNewerValueWhileOneIsInFlightIsSentOnceAfterwards() {
        assertEquals(1, push.request(watch, apps).send.size)
        assertTrue(push.request(watch, apps).send.isEmpty())
        val follow = push.onResult("WATCH", "APP", ConnectIqSendResult.SUCCESS)
        assertEquals(listOf(ConnectIqTarget("WATCH", "APP")), follow.send)
        assertTrue(push.onResult("WATCH", "APP", ConnectIqSendResult.SUCCESS).send.isEmpty())
    }

    @Test
    fun aMissingAppWaitsHalfAnHour() {
        push.request(watch, apps)
        val failed = push.onResult("WATCH", "APP", ConnectIqSendResult.FAILURE_APP_NOT_FOUND)
        assertTrue(failed.log!!.contains("retryInMs=1800000"))
        now = 1_800_000 - 1
        assertTrue(push.request(watch, apps).send.isEmpty())
        now = 1_800_000
        assertEquals(1, push.request(watch, apps).send.size)
    }

    @Test
    fun anAppKnownMissingIsNotSent() {
        val plan = push.request(watch, listOf("MISSING", "THERE")) { _, app -> app != "MISSING" }
        assertEquals(listOf(ConnectIqTarget("WATCH", "THERE")), plan.send)
    }

    @Test
    fun turningTheSwitchOffDropsAFollowUp() {
        push.request(watch, apps)
        push.request(watch, apps)
        on = false
        assertTrue(push.onResult("WATCH", "APP", ConnectIqSendResult.SUCCESS).send.isEmpty())
        assertTrue(push.request(watch, apps).send.isEmpty())
    }

    @Test
    fun anUnknownResultNameIsAFailure() {
        assertEquals(ConnectIqSendResult.FAILURE_UNKNOWN, ConnectIqSendResult.fromWire("NOPE"))
        assertEquals(ConnectIqSendResult.FAILURE_TIMEOUT, ConnectIqSendResult.fromWire("FAILURE_TIMEOUT"))
    }

    private fun waitAfter(result: ConnectIqSendResult): Long {
        val before = now
        val log = push.onResult("WATCH", "APP", result).log!!
        val delay = log.substringAfter("retryInMs=").toLong()
        now = before + delay
        push.request(watch, apps)
        return delay
    }
}
