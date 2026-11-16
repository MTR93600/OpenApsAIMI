package app.aaps.plugins.sync.homewidget

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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When the iOS widgets get a new state. Time is the test scheduler's clock, so the 30 s rule and the
 * delayed save are checked with [advanceTimeBy], never with a wait for idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeWidgetPublisherTest {

    private class FakeCompanion : HomeWidgetCompanion {
        val saves = mutableListOf<String>()
        val reloads = mutableListOf<Boolean>()

        override fun save(stateJson: String, reload: Boolean) {
            saves.add(stateJson)
            reloads.add(reload)
        }
    }

    /** Builds a state around the current reading and IOB. Remembers what was asked for. */
    private class FakeExport(var bgTimestamp: Long?) : WidgetStateExport {
        var iob = 1.0
        var builds = 0
        var lastWithChart: Boolean? = null
        var lastWithDetails: Boolean? = null

        override suspend fun export(withChart: Boolean, withDetails: Boolean): WidgetStateSnapshot {
            builds++
            lastWithChart = withChart
            lastWithDetails = withDetails
            val json = buildJsonObject {
                put("v", 1)
                put("bgTime", bgTimestamp)
                put("staleAt", bgTimestamp?.let { it + 9 * 60_000L })
                put("stale", bgTimestamp == null)
                put("iob", iob)
            }
            return WidgetStateSnapshot(json, bgTimestamp)
        }
    }

    private class Harness(val scope: TestScope, startBg: Long?, withCompanion: Boolean = true, initialized: Boolean = true) {
        val companion = FakeCompanion()
        val export = FakeExport(startBg)
        val logs = mutableListOf<String>()
        val appInitialized = MutableStateFlow(initialized)
        val publisher = HomeWidgetPublisher(
            scope = scope,
            export = export,
            appInitialized = appInitialized,
            nowMs = { scope.testScheduler.currentTime },
            log = { logs.add(it) },
            companion = { if (withCompanion) companion else null },
        )
    }

    private fun lastJson(h: Harness): JsonObject = Json.parseToJsonElement(h.companion.saves.last()).jsonObject

    @Test
    fun startSavesAtOnceWithChartAndDetails() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        assertEquals(1, h.companion.saves.size)
        assertEquals(true, h.export.lastWithChart)
        assertEquals(true, h.export.lastWithDetails)
    }

    /**
     * The first launch of a fresh install: the plugin starts while the plugins are still being elected,
     * and building the state then reads a pump that does not exist yet ("No pump selected", which took
     * the app down). The first save waits until the app is initialized, and then goes out.
     */
    @Test
    fun nothingIsBuiltBeforeTheAppIsInitialized() = runTest {
        val h = Harness(this, startBg = 1_000L, initialized = false)
        h.publisher.start()
        h.publisher.request("bg")
        runCurrent()
        assertEquals(0, h.export.builds)
        assertEquals(0, h.companion.saves.size)

        h.appInitialized.value = true
        runCurrent()
        assertEquals(1, h.companion.saves.size)
    }

    @Test
    fun newReadingIsSavedAtOnce() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        h.export.bgTimestamp = 2_000L
        h.publisher.request("bg")
        runCurrent()
        assertEquals(2, h.companion.saves.size)
        assertEquals(2_000L, lastJson(h).getValue("bgTime").jsonPrimitive.long)
    }

    @Test
    fun loopResultInsideTheWindowIsSavedOnceAtItsEnd() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        h.export.iob = 2.5
        h.publisher.request("aps")
        h.publisher.request("aps")
        runCurrent()
        assertEquals(1, h.companion.saves.size)

        advanceTimeBy(29_000L)
        runCurrent()
        assertEquals(1, h.companion.saves.size)

        advanceTimeBy(1_001L)
        runCurrent()
        assertEquals(2, h.companion.saves.size)
        assertEquals(2.5, lastJson(h).getValue("iob").jsonPrimitive.double)

        // Nothing else is waiting.
        advanceTimeBy(120_000L)
        runCurrent()
        assertEquals(2, h.companion.saves.size)
    }

    @Test
    fun sameStateIsNotSavedAgain() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        advanceTimeBy(60_000L)
        h.publisher.request("aps")
        runCurrent()
        h.publisher.request("aps", force = true)
        runCurrent()
        assertEquals(1, h.companion.saves.size)
        assertEquals(3, h.export.builds)
    }

    @Test
    fun readingThatDisappearsIsSavedAtOnceAsStale() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        h.export.bgTimestamp = null
        h.publisher.request("bg")
        runCurrent()
        assertEquals(2, h.companion.saves.size)
        assertTrue(lastJson(h).getValue("stale").jsonPrimitive.boolean)
    }

    @Test
    fun missingBridgeIsLoggedOnceAndBuildsNothing() = runTest {
        val h = Harness(this, startBg = 1_000L, withCompanion = false)
        h.publisher.start()
        h.publisher.request("bg")
        h.publisher.request("aps")
        runCurrent()
        assertEquals(0, h.export.builds)
        assertEquals(1, h.logs.size)
    }

    @Test
    fun stopCancelsTheDelayedSave() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        h.export.iob = 3.0
        h.publisher.request("aps")
        runCurrent()
        h.publisher.stop()
        runCurrent()
        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(1, h.companion.saves.size)
    }

    @Test
    fun savedStateCarriesTheFlagsTheSwiftStructNeeds() = runTest {
        // `AapsActivityAttributes.ContentState` declares these three as non-optional. Without them
        // Swift cannot decode the file and every widget shows "---".
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        val json = lastJson(h)
        for (key in listOf("showChart", "showIobCob", "showLoop")) {
            assertTrue(json.getValue(key).jsonPrimitive.boolean, key)
        }
        // The widget state itself is passed on unchanged.
        assertEquals(1_000L, json.getValue("bgTime").jsonPrimitive.long)
        assertEquals(1.0, json.getValue("iob").jsonPrimitive.double)
    }

    @Test
    fun firstSaveReloadsTheWidgets() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        assertEquals(listOf(true), h.companion.reloads)
    }

    @Test
    fun loopResultIsSavedWithoutReload() = runTest {
        val h = Harness(this, startBg = 1_000L)
        h.publisher.start()
        runCurrent()
        advanceTimeBy(10 * 60_000L)
        h.export.iob = 2.0
        h.publisher.request("aps")
        runCurrent()
        assertEquals(listOf(true, false), h.companion.reloads)
    }

    @Test
    fun newReadingReloadsAtMostOnceInTheReloadInterval() = runTest {
        val h = Harness(this, startBg = 0L)
        h.publisher.start()
        runCurrent()
        // A 1 minute CGM: a reading every minute.
        for (minute in 1..8) {
            advanceTimeBy(60_000L)
            h.export.bgTimestamp = minute * 60_000L
            h.publisher.request("bg")
            runCurrent()
        }
        assertEquals(9, h.companion.saves.size)
        // Reloads at start, at minute 4 and at minute 8.
        assertEquals(
            listOf(true, false, false, false, true, false, false, false, true),
            h.companion.reloads
        )
    }

    @Test
    fun fiveMinuteCgmReloadsOnEveryReading() = runTest {
        val h = Harness(this, startBg = 0L)
        h.publisher.start()
        runCurrent()
        for (reading in 1..4) {
            advanceTimeBy(5 * 60_000L)
            h.export.bgTimestamp = reading * 5 * 60_000L
            h.publisher.request("bg")
            runCurrent()
        }
        assertEquals(listOf(true, true, true, true, true), h.companion.reloads)
    }

    @Test
    fun reloadIntervalLeavesNoReadingStaleOnScreen() {
        // A reading skipped by the interval waits for the next one. Its age on screen must stay below
        // the 9 minute stale edge, or a 5 minute CGM would look stale while it works.
        assertTrue(HomeWidgetPublisher.MIN_RELOAD_INTERVAL_MS + 5 * 60_000L < 9 * 60_000L)
    }
}
