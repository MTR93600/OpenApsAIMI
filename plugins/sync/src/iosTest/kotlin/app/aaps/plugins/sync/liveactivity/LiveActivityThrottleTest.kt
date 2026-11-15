package app.aaps.plugins.sync.liveactivity

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class LiveActivityThrottleTest {

    private val throttle = LiveActivityThrottle()
    private val t0 = 1_800_000_000_000L

    @Test
    fun firstSendIsNeverHeld() {
        assertEquals(0, throttle.waitBeforeSend(t0, t0, force = false))
    }

    @Test
    fun sameReadingWaitsForTheRestOfThirtySeconds() {
        throttle.sent(t0, t0)
        assertEquals(25_000, throttle.waitBeforeSend(t0 + 5_000, t0, force = false))
        assertEquals(1, throttle.waitBeforeSend(t0 + 29_999, t0, force = false))
        assertEquals(0, throttle.waitBeforeSend(t0 + 30_000, t0, force = false))
    }

    @Test
    fun newReadingIsNeverHeld() {
        throttle.sent(t0, t0)
        assertEquals(0, throttle.waitBeforeSend(t0 + 1_000, t0 + 1_000, force = false))
    }

    @Test
    fun lostReadingIsNeverHeld() {
        throttle.sent(t0, t0)
        assertEquals(0, throttle.waitBeforeSend(t0 + 1_000, null, force = false))
    }

    @Test
    fun forcedSendIsNeverHeld() {
        throttle.sent(t0, t0)
        assertEquals(0, throttle.waitBeforeSend(t0 + 1_000, t0, force = true))
    }

    @Test
    fun clockThatWentBackDoesNotBlock() {
        throttle.sent(t0, t0)
        assertEquals(0, throttle.waitBeforeSend(t0 - 60_000, t0, force = false))
    }

    @Test
    fun resetForgetsTheLastSend() {
        throttle.sent(t0, t0)
        throttle.reset()
        assertEquals(0, throttle.waitBeforeSend(t0 + 1_000, t0, force = false))
    }

    @Test
    fun installedCompanionIsTheOneThePluginUses() {
        val fake = object : LiveActivityCompanion {
            override fun start(callback: LiveActivityCallback) = Unit
            override fun update(stateJson: String) = Unit
            override fun end() = Unit
            override fun stop() = Unit
        }
        installLiveActivityCompanion(fake)
        assertSame(fake, LiveActivityRegistry.companion)
    }

    @AfterTest
    fun clearRegistry() {
        LiveActivityRegistry.companion = null
    }
}
