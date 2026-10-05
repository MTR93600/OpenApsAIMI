package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.HR
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HealthKit fills the same snapshot fields Android reads from the watch: steps over 5, 15 and 60
 * minutes, the current heart rate, and the resting heart rate. The 10-minute and 60-minute windows
 * are the ones `HR_TREND_ISF` uses. The four samples of
 * `autosensHalfDoublesScheduledBasalAndRestingHeartRateStrengthensIsf` are 80, 80, 80, then 110 bpm.
 */
class SnapshotFromWearableWindowsTest {

    @Test
    fun fourWatchSamplesFillTheTenAndSixtyMinuteWindows() {
        val now = 1_700_000_000_000L
        val windows = snapshotFromWearableWindows(
            stepsLast5m = 0,
            stepsLast15m = 0,
            stepsLast60m = 0,
            heartRates = listOf(
                hr(now - 40 * 60_000L, 80.0),
                hr(now - 30 * 60_000L, 80.0),
                hr(now - 20 * 60_000L, 80.0),
                hr(now - 2 * 60_000L, 110.0),
            ),
            restingBpm = 60,
            nowMs = now,
        )
        assertEquals(110, windows.hrAvg10)
        assertEquals(88, windows.hrAvg60)
        assertEquals(110, windows.snapshot.hrNow)
        assertEquals(110, windows.snapshot.hrAvg15m)
        assertEquals(now - 2 * 60_000L, windows.snapshot.hrMeasuredAtMs)
        assertEquals(60, windows.snapshot.rhrResting)
        assertEquals(0, windows.snapshot.stepsLast5m)
        assertEquals(0, windows.snapshot.stepsLast15m)
        assertEquals(0, windows.snapshot.stepsLast60m)
        assertEquals("IDLE", windows.snapshot.activityState)
        assertEquals("HealthKit", windows.snapshot.source)
        assertFalse(windows.snapshot.isValid)
    }

    @Test
    fun twoHundredStepsInFiveMinutesIsActive() {
        val now = 1_700_000_000_000L
        val windows = snapshotFromWearableWindows(
            stepsLast5m = 200,
            stepsLast15m = 200,
            stepsLast60m = 200,
            heartRates = emptyList(),
            restingBpm = 0,
            nowMs = now,
        )
        assertEquals("ACTIVE", windows.snapshot.activityState)
        assertEquals(200, windows.snapshot.stepsLast5m)
        assertEquals(60, windows.snapshot.rhrResting)
        assertEquals(0, windows.hrAvg10)
        assertEquals(0, windows.hrAvg60)
    }

    @Test
    fun aReadFailureLogsTheWearableLineAndReturnsAnEmptySnapshot() {
        val log = mutableListOf<String>()
        val windows = wearableWindowsOrEmpty(log) { throw IllegalStateException("watch down") }
        assertEquals(0, windows.hrAvg10)
        assertEquals(0, windows.hrAvg60)
        assertEquals(0, windows.snapshot.hrNow)
        assertEquals(0, windows.snapshot.stepsLast5m)
        assertFalse(windows.snapshot.isValid)
        assertEquals(HealthContextSnapshot.EMPTY.hrNow, windows.snapshot.hrNow)
        assertEquals(
            listOf("WEARABLE snapshot failed (IllegalStateException): watch down — snapshot empty"),
            log,
        )
    }

    @Test
    fun anErrorFromTheReadPropagates() {
        assertFailsWith<OutOfMemoryError> {
            wearableWindowsOrEmpty(mutableListOf()) { throw OutOfMemoryError("disk") }
        }
    }

    private fun hr(timestamp: Long, bpm: Double) = HR(
        duration = 60_000L,
        timestamp = timestamp,
        beatsPerMinute = bpm,
        device = "watch",
    )
}
