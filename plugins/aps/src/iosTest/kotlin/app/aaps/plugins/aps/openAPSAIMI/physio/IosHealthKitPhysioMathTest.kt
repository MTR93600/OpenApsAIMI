package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.plugins.aps.openAPSAIMI.physio.IosHealthKitPhysioMath.SdnnSample
import app.aaps.plugins.aps.openAPSAIMI.physio.IosHealthKitPhysioMath.SleepSegment
import app.aaps.plugins.aps.openAPSAIMI.physio.IosHealthKitPhysioMath.WristTemperatureSample
import kotlinx.datetime.TimeZone
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives `IosHealthKitPhysioMath` with scripted samples.
 *
 * None of this needs a health store. The simulator holds no HealthKit data and the real samples
 * need a paired Apple Watch, so the only way these rules can be tested at all is from plain data
 * classes - which is why the maths was split out of the repository in the first place.
 */
class IosHealthKitPhysioMathTest {

    private val hour = 3_600_000L
    private val minute = 60_000L

    // ═══════════════════════════════════════════════════════════════════════
    // SLEEP
    // ═══════════════════════════════════════════════════════════════════════

    private fun stage(startMs: Long, endMs: Long, value: Long = IosHealthKitPhysioMath.SLEEP_ASLEEP_CORE) =
        SleepSegment(startMs, endMs, value)

    @Test
    fun `touching stage samples become one night`() {
        val night = listOf(
            stage(0, 2 * hour, IosHealthKitPhysioMath.SLEEP_ASLEEP_CORE),
            stage(2 * hour, 3 * hour, IosHealthKitPhysioMath.SLEEP_ASLEEP_DEEP),
            stage(3 * hour, 7 * hour, IosHealthKitPhysioMath.SLEEP_ASLEEP_REM),
        )

        val sleep = assertNotNull(IosHealthKitPhysioMath.sleepFrom(night, nowMs = 9 * hour))

        assertEquals(7.0, sleep.durationHours)
        assertEquals(0L, sleep.startTime)
        assertEquals(7 * hour, sleep.endTime)
    }

    /**
     * Two writers for the same night - the watch and a third party app - must not double the
     * sleep. Health Connect would have had one session.
     */
    @Test
    fun `overlapping samples are not counted twice`() {
        val doubled = listOf(
            stage(0, 6 * hour),
            stage(1 * hour, 7 * hour),
        )

        val sleep = assertNotNull(IosHealthKitPhysioMath.sleepFrom(doubled, nowMs = 9 * hour))

        assertEquals(7.0, sleep.durationHours)
    }

    @Test
    fun `a nap after a long break is a second session`() {
        val nightAndNap = listOf(
            stage(0, 6 * hour),
            stage(12 * hour, 13 * hour),
        )

        val sleep = assertNotNull(IosHealthKitPhysioMath.sleepFrom(nightAndNap, nowMs = 20 * hour))

        assertEquals(7.0, sleep.durationHours)
        assertEquals(0L, sleep.startTime)
        assertEquals(13 * hour, sleep.endTime)
    }

    /**
     * A waking shorter than the bridge stays inside the night. Health Connect reports the whole
     * session span including that waking so the two sides have to agree here.
     */
    @Test
    fun `a short waking inside the night is kept in the span`() {
        val broken = listOf(
            stage(0, 3 * hour),
            stage(3 * hour + 20 * minute, 7 * hour),
        )

        val sleep = assertNotNull(IosHealthKitPhysioMath.sleepFrom(broken, nowMs = 9 * hour))

        assertEquals(7.0, sleep.durationHours)
    }

    @Test
    fun `awake and in bed samples are ignored when stages exist`() {
        val mixed = listOf(
            stage(0, 6 * hour, IosHealthKitPhysioMath.SLEEP_ASLEEP_CORE),
            stage(0, 9 * hour, IosHealthKitPhysioMath.SLEEP_IN_BED),
            stage(6 * hour, 9 * hour, IosHealthKitPhysioMath.SLEEP_AWAKE),
        )

        val sleep = assertNotNull(IosHealthKitPhysioMath.sleepFrom(mixed, nowMs = 10 * hour))

        assertEquals(6.0, sleep.durationHours)
    }

    /** An iPhone with no watch writes only `inBed`. Losing that night would be the real gap. */
    @Test
    fun `in bed is used when no stage sample exists`() {
        val inBedOnly = listOf(stage(0, 8 * hour, IosHealthKitPhysioMath.SLEEP_IN_BED))

        val sleep = assertNotNull(IosHealthKitPhysioMath.sleepFrom(inBedOnly, nowMs = 10 * hour))

        assertEquals(8.0, sleep.durationHours)
    }

    @Test
    fun `an empty window gives no sleep`() {
        assertNull(IosHealthKitPhysioMath.sleepFrom(emptyList(), nowMs = 10 * hour))
    }

    @Test
    fun `a night that is still running is reported as ongoing`() {
        val running = listOf(stage(0, 5 * hour))

        val sleep = assertNotNull(IosHealthKitPhysioMath.sleepFrom(running, nowMs = 4 * hour))

        assertEquals(0L, sleep.ongoingStartMs)
        assertEquals(5 * hour, sleep.ongoingEndMs)
        assertTrue(sleep.isOngoingAt(4 * hour))
    }

    @Test
    fun `a finished night is not reported as ongoing`() {
        val finished = listOf(stage(0, 5 * hour))

        val sleep = assertNotNull(IosHealthKitPhysioMath.sleepFrom(finished, nowMs = 9 * hour))

        assertNull(sleep.ongoingStartMs)
        assertNull(sleep.ongoingEndMs)
    }

    /**
     * Deliberate parity with Android: `AIMIPhysioFeatureExtractorMTR` branches on
     * `deepSleepMinutes <= 0`. Filling the stage minutes here would make the same night dose
     * differently on the two phones.
     */
    @Test
    fun `stage minutes stay zero to match the Android side`() {
        val staged = listOf(
            stage(0, 2 * hour, IosHealthKitPhysioMath.SLEEP_ASLEEP_DEEP),
            stage(2 * hour, 6 * hour, IosHealthKitPhysioMath.SLEEP_ASLEEP_REM),
        )

        val sleep = assertNotNull(IosHealthKitPhysioMath.sleepFrom(staged, nowMs = 9 * hour))

        assertEquals(0, sleep.deepSleepMinutes)
        assertEquals(0, sleep.remSleepMinutes)
        assertEquals(0, sleep.lightSleepMinutes)
        assertEquals(0, sleep.awakeMinutes)
        assertEquals(0.0, sleep.fragmentationScore)
        assertEquals(IosHealthKitPhysioMath.SLEEP_EFFICIENCY, sleep.efficiency)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HRV - SDNN
    // ═══════════════════════════════════════════════════════════════════════

    private fun sdnn(timestampMs: Long, milliseconds: Double, sourceName: String = "Apple Watch") =
        SdnnSample(timestampMs, milliseconds, sourceName)

    /**
     * The whole point of the tag: HealthKit only has SDNN, so the sample must say so and must not
     * land in the RMSSD field, where code reading it by name would take it for the other metric.
     */
    @Test
    fun `every sample is tagged as SDNN and keeps the RMSSD field empty`() {
        val readings = listOf(sdnn(1 * hour, 42.0), sdnn(2 * hour, 55.0))

        val points = IosHealthKitPhysioMath.hrvFrom(readings)

        assertEquals(2, points.size)
        assertTrue(points.all { it.metric == HrvMetric.SDNN })
        assertTrue(points.all { it.rmssd == 0.0 }, "an SDNN must never be readable as an RMSSD")
        assertEquals(42.0, points.first().sdnn)
        assertEquals(42.0, points.first().value, "the tagged metric is what `value` answers")
        assertTrue(points.all { it.hasValidData() })
    }

    @Test
    fun `the source name says where the reading came from`() {
        val points = IosHealthKitPhysioMath.hrvFrom(listOf(sdnn(1 * hour, 42.0, "Oura")))

        assertEquals("HealthKit(Oura)", points.single().source)
    }

    /** A zero would read as a body under total stress, so it is absence, not a measurement. */
    @Test
    fun `a zero or negative reading is dropped rather than reported`() {
        val readings = listOf(
            sdnn(1 * hour, 0.0),
            sdnn(2 * hour, -5.0),
            sdnn(3 * hour, 40.0),
        )

        val points = IosHealthKitPhysioMath.hrvFrom(readings)

        assertEquals(1, points.size)
        assertEquals(40.0, points.single().sdnn)
    }

    @Test
    fun `a reading that is not a number is dropped`() {
        val readings = listOf(
            sdnn(1 * hour, Double.NaN),
            sdnn(2 * hour, Double.POSITIVE_INFINITY),
            sdnn(3 * hour, 40.0),
        )

        val points = IosHealthKitPhysioMath.hrvFrom(readings)

        assertEquals(listOf(40.0), points.map { it.sdnn })
    }

    @Test
    fun `points come back in time order`() {
        val shuffled = listOf(
            sdnn(5 * hour, 50.0),
            sdnn(1 * hour, 10.0),
            sdnn(3 * hour, 30.0),
        )

        val points = IosHealthKitPhysioMath.hrvFrom(shuffled)

        assertEquals(listOf(1 * hour, 3 * hour, 5 * hour), points.map { it.timestamp })
    }

    @Test
    fun `no sample gives no HRV at all`() {
        assertTrue(
            IosHealthKitPhysioMath.hrvFrom(emptyList()).isEmpty(),
            "absence stays absence; the dose path handles it",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MORNING WINDOWS
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    fun `one morning window is produced per day`() {
        val zone = TimeZone.of("Europe/Paris")
        val nowMs = 1_760_000_000_000L

        val windows = IosHealthKitPhysioMath.morningWindows(nowMs, daysBack = 7, zone = zone)

        assertEquals(7, windows.size)
        windows.forEach { window ->
            assertEquals(6 * hour, window.endMs - window.startMs, "04:00 to 10:00 is six hours")
            assertTrue(window.startMs <= nowMs)
        }
    }

    @Test
    fun `windows are one day apart and never repeat`() {
        val zone = TimeZone.of("Europe/Paris")
        val nowMs = 1_760_000_000_000L

        val starts = IosHealthKitPhysioMath.morningWindows(nowMs, daysBack = 7, zone = zone)
            .map { it.startMs }

        assertEquals(starts.size, starts.toSet().size)
    }

    /** Before 04:00 local the window of the current day has not opened yet and is skipped. */
    @Test
    fun `a window that has not opened yet is skipped`() {
        val zone = TimeZone.UTC
        // 1970-01-08T02:00Z - two in the morning.
        val nowMs = 7L * 24 * hour + 2 * hour

        val windows = IosHealthKitPhysioMath.morningWindows(nowMs, daysBack = 3, zone = zone)

        assertEquals(2, windows.size)
        assertTrue(windows.all { it.startMs < nowMs })
    }

    @Test
    fun `zero days back gives no window`() {
        val windows = IosHealthKitPhysioMath.morningWindows(
            nowMs = 1_760_000_000_000L,
            daysBack = 0,
            zone = TimeZone.UTC,
        )

        assertTrue(windows.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════════════
    // WRIST TEMPERATURE
    // ═══════════════════════════════════════════════════════════════════════

    private fun wrist(timestampMs: Long, celsius: Double) =
        WristTemperatureSample(timestampMs, celsius, dataOrigin = "Apple Watch")

    @Test
    fun `deltas are measured against the median of the baseline window`() {
        val baseline = listOf(
            wrist(1 * hour, 33.0),
            wrist(2 * hour, 33.2),
            wrist(3 * hour, 33.4),
            wrist(4 * hour, 33.6),
            wrist(5 * hour, 33.8),
        )

        val deltas = IosHealthKitPhysioMath.wristTemperatureDeltas(baseline, windowStartMs = 0)

        assertEquals(5, deltas.size)
        // Median of the five values is 33.4.
        assertTrue(abs(deltas[2].deltaCelsius) < 1e-9)
        assertTrue(abs(deltas.first().deltaCelsius + 0.4) < 1e-9)
        assertTrue(abs(deltas.last().deltaCelsius - 0.4) < 1e-9)
    }

    @Test
    fun `only samples inside the reported window come back`() {
        val baseline = (1..10).map { wrist(it * hour, 33.0 + it * 0.1) }

        val deltas = IosHealthKitPhysioMath.wristTemperatureDeltas(baseline, windowStartMs = 8 * hour)

        assertEquals(3, deltas.size)
        assertTrue(deltas.all { it.timestampMs >= 8 * hour })
    }

    @Test
    fun `too few nights give no thermal data at all`() {
        val tooFew = (1..4).map { wrist(it * hour, 33.0) }

        assertTrue(
            IosHealthKitPhysioMath.wristTemperatureDeltas(tooFew, windowStartMs = 0).isEmpty(),
            "a baseline from four nights is a guess so the caller should fall through",
        )
    }

    @Test
    fun `every sample is tagged as a wrist reading`() {
        val baseline = (1..6).map { wrist(it * hour, 33.0 + it * 0.1) }

        val deltas = IosHealthKitPhysioMath.wristTemperatureDeltas(baseline, windowStartMs = 0)

        assertTrue(deltas.all { it.measurementLocation == "WRIST" })
        assertTrue(deltas.all { it.dataOrigin == "Apple Watch" })
    }

    @Test
    fun `samples come back in time order`() {
        val shuffled = listOf(
            wrist(5 * hour, 33.5),
            wrist(1 * hour, 33.1),
            wrist(4 * hour, 33.4),
            wrist(2 * hour, 33.2),
            wrist(3 * hour, 33.3),
        )

        val deltas = IosHealthKitPhysioMath.wristTemperatureDeltas(shuffled, windowStartMs = 0)

        assertEquals(deltas.map { it.timestampMs }, deltas.map { it.timestampMs }.sorted())
    }
}
