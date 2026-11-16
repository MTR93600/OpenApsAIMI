package app.aaps.plugins.sync.liveactivity

import app.aaps.core.interfaces.widget.WidgetStateExport
import app.aaps.core.interfaces.widget.WidgetStateSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the Live Activity gets a new state. Time is the test scheduler's clock, so the 30 s throttle
 * and the delayed send are checked with [advanceTimeBy], never with a wait for idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveActivityPublisherTest {

    private val min = 60_000L

    private class FakeCompanion : LiveActivityCompanion {
        var callback: LiveActivityCallback? = null
        val updates = mutableListOf<String>()
        var ends = 0
        var stops = 0

        override fun start(callback: LiveActivityCallback) {
            this.callback = callback
        }

        override fun update(stateJson: String) {
            updates.add(stateJson)
        }

        override fun end() {
            ends++
        }

        override fun stop() {
            stops++
            callback = null
        }
    }

    /** Builds a state around the current reading time. Counts builds and remembers the chart flag. */
    private class FakeExport(var bgTimestamp: Long?) : WidgetStateExport {
        var builds = 0
        var lastWithChart: Boolean? = null

        override suspend fun export(withChart: Boolean, withDetails: Boolean): WidgetStateSnapshot {
            builds++
            lastWithChart = withChart
            val json = buildJsonObject {
                put("v", 1)
                put("bgTime", bgTimestamp)
                put("staleAt", bgTimestamp?.let { it + 9 * 60_000L })
                put("stale", bgTimestamp == null)
            }
            return WidgetStateSnapshot(json, bgTimestamp)
        }
    }

    private class Harness(
        val scope: TestScope,
        startBg: Long?,
        var settings: LiveActivitySettings = LiveActivitySettings(enabled = true, showChart = true, showIobCob = true, showLoop = true),
        withCompanion: Boolean = true,
        initialized: Boolean = true,
    ) {
        val companion = FakeCompanion()
        val export = FakeExport(startBg)
        val logs = mutableListOf<String>()
        val appInitialized = MutableStateFlow(initialized)
        val publisher = LiveActivityPublisher(
            scope = scope,
            export = export,
            appInitialized = appInitialized,
            settings = { settings },
            nowMs = { scope.testScheduler.currentTime },
            log = { logs.add(it) },
            companion = { if (withCompanion) companion else null },
        )
    }

    private fun lastJson(h: Harness): JsonObject = Json.parseToJsonElement(h.companion.updates.last()).jsonObject

    @Test
    fun startHandsTheCallbackToSwiftAndSendsAtOnce() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        assertTrue(h.companion.callback != null)
        assertEquals(1, h.companion.updates.size)
        assertEquals(0, h.companion.ends)
    }

    /** Building the state reads the active pump, which does not exist while the plugins are elected. */
    @Test
    fun nothingIsBuiltBeforeTheAppIsInitialized() = runTest {
        val h = Harness(this, startBg = 1_000L, initialized = false)
        h.publisher.start()
        runCurrent()
        assertEquals(0, h.export.builds)
        assertEquals(0, h.companion.updates.size)

        h.appInitialized.value = true
        runCurrent()
        assertEquals(1, h.companion.updates.size)
    }

    @Test
    fun startWhileSwitchedOffEndsLeftoverActivitiesAndSendsNothing() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.settings = h.settings.copy(enabled = false)
        h.publisher.start()
        h.publisher.request("bg")
        h.publisher.request("aps")
        runCurrent()
        assertEquals(1, h.companion.ends)
        assertEquals(0, h.companion.updates.size)
        assertEquals(0, h.export.builds)
    }

    @Test
    fun stopDropsTheCallback() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        h.publisher.stop()
        runCurrent()
        assertEquals(1, h.companion.stops)
        assertTrue(h.companion.callback == null)
    }

    @Test
    fun withoutTheSwiftBridgeNothingCrashesAndItIsLoggedOnce() = runTest {
        val h = Harness(this, startBg = 1_000L, withCompanion = false)
        h.publisher.start()
        h.publisher.request("bg")
        h.publisher.request("bg")
        runCurrent()
        assertEquals(0, h.companion.updates.size)
        assertEquals(2, h.logs.size) // "not installed" at start, and one "update skipped"
    }

    @Test
    fun loopResultSecondsAfterTheReadingIsDelayedNotDropped() = runTest {
        val h = Harness(this, startBg = 0L)
        h.publisher.request("bg")
        runCurrent()
        assertEquals(1, h.companion.updates.size)

        advanceTimeBy(5_000)
        h.publisher.request("aps")
        runCurrent()
        // Same reading, 5 s later: held back.
        assertEquals(1, h.companion.updates.size)

        advanceTimeBy(24_000)
        runCurrent()
        assertEquals(1, h.companion.updates.size)

        advanceTimeBy(1_001)
        runCurrent()
        // 30 s after the first send, one delayed send with a freshly built state.
        assertEquals(2, h.companion.updates.size)
    }

    @Test
    fun manyTriggersInsideOneWindowGiveOneDelayedSend() = runTest {
        val h = Harness(this, startBg = 0L)
        h.publisher.request("bg")
        runCurrent()
        repeat(5) {
            advanceTimeBy(2_000)
            h.publisher.request("aps")
            runCurrent()
        }
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(2, h.companion.updates.size)
    }

    @Test
    fun aNewReadingIsAlwaysSentAtOnce() = runTest {
        val h = Harness(this, startBg = 0L)
        h.publisher.request("bg")
        runCurrent()
        advanceTimeBy(3_000)
        // A 1-minute CGM, or a backfill: a new reading time goes out inside the 30 s window.
        h.export.bgTimestamp = 3_000L
        h.publisher.request("bg")
        runCurrent()
        assertEquals(2, h.companion.updates.size)
        assertEquals(3_000L, lastJson(h).getValue("bgTime").jsonPrimitive.long)
    }

    @Test
    fun aReadingThatDisappearsIsSentAtOnceAsStale() = runTest {
        val h = Harness(this, startBg = 0L)
        h.publisher.request("bg")
        runCurrent()
        advanceTimeBy(1_000)
        h.export.bgTimestamp = null
        h.publisher.request("bg")
        runCurrent()
        assertEquals(2, h.companion.updates.size)
        assertTrue(lastJson(h).getValue("stale").jsonPrimitive.boolean)
    }

    @Test
    fun oldReadingIsStillSentWithItsOwnTimeAndStaleAt() = runTest {
        // The app ran again after a long stop, but the sensor has not sent: the state is old. It
        // must still go out, carrying the real reading time, so the lock screen shows it as old.
        val h = Harness(this, startBg = 0L)
        advanceTimeBy(20 * min)
        h.publisher.request("aps")
        runCurrent()
        assertEquals(1, h.companion.updates.size)
        val json = lastJson(h)
        assertEquals(0L, json.getValue("bgTime").jsonPrimitive.long)
        assertEquals(9 * min, json.getValue("staleAt").jsonPrimitive.long)
    }

    @Test
    fun refreshFromSwiftSkipsTheThrottle() = runTest {
        val h = Harness(this, startBg = 0L)
        h.publisher.start()
        runCurrent()
        advanceTimeBy(1_000)
        h.companion.callback!!.refreshRequested()
        runCurrent()
        assertEquals(2, h.companion.updates.size)
    }

    @Test
    fun switchingOffEndsAndSwitchingOnSendsAtOnce() = runTest {
        val h = Harness(this, startBg = 0L)
        h.publisher.request("bg")
        runCurrent()

        h.settings = h.settings.copy(enabled = false)
        h.publisher.onEnabledChanged(false)
        runCurrent()
        assertEquals(1, h.companion.ends)
        h.publisher.request("aps")
        runCurrent()
        assertEquals(1, h.companion.updates.size)

        advanceTimeBy(1_000)
        h.settings = h.settings.copy(enabled = true)
        h.publisher.onEnabledChanged(true)
        runCurrent()
        assertEquals(2, h.companion.updates.size)
    }

    @Test
    fun switchingOffCancelsADelayedSend() = runTest {
        val h = Harness(this, startBg = 0L)
        h.publisher.request("bg")
        runCurrent()
        advanceTimeBy(1_000)
        h.publisher.request("aps")
        runCurrent()
        h.settings = h.settings.copy(enabled = false)
        h.publisher.onEnabledChanged(false)
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, h.companion.updates.size)
    }

    @Test
    fun payloadCarriesTheStateAndTheShowFlags() = runTest {
        val h = Harness(this, startBg = 5_000L)
        h.settings = h.settings.copy(showIobCob = false, showLoop = true, showChart = false)
        h.publisher.request("bg")
        runCurrent()
        val json = lastJson(h)
        assertEquals(5_000L, json.getValue("bgTime").jsonPrimitive.long)
        assertFalse(json.getValue("showChart").jsonPrimitive.boolean)
        assertFalse(json.getValue("showIobCob").jsonPrimitive.boolean)
        assertTrue(json.getValue("showLoop").jsonPrimitive.boolean)
    }

    @Test
    fun chartIsBuiltOnlyWhenShown() = runTest {
        val h = Harness(this, startBg = 5_000L)
        h.settings = h.settings.copy(showChart = false)
        h.publisher.request("bg")
        runCurrent()
        assertEquals(false, h.export.lastWithChart)

        h.settings = h.settings.copy(showChart = true)
        h.publisher.onShowChanged()
        runCurrent()
        assertEquals(true, h.export.lastWithChart)
        assertEquals(2, h.companion.updates.size)
    }
}
