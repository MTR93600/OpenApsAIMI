package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Step S3b: the "use sleep data" and "use HRV data" switches. Off, the reading is treated as absent
 * everywhere a dose can see it: in the raw read of the physio run, in the features, in the stored
 * context and in the live health snapshot. On (the default), nothing changes.
 */
class PhysioDataSwitchesTest {

    private val sleep = SleepDataMTR(startTime = 1_000, endTime = 2_000, durationHours = 4.0, efficiency = 0.6)
    private val hrv = listOf(HRVDataMTR(timestamp = 1_500, rmssd = 22.0))
    private val rhr = listOf(RHRDataMTR(timestamp = 1_500, bpm = 70))
    private val raw = RawPhysioDataMTR(sleep = sleep, hrv = hrv, rhr = rhr, steps = 4000, fetchTimestamp = 3_000)

    private val sleepOff = PhysioDataSwitches(sleep = false, hrv = true)
    private val hrvOff = PhysioDataSwitches(sleep = true, hrv = false)

    // ---------------------------------------------------------------- the switches

    @Test
    fun `both switches are on by default`() {
        assertEquals(PhysioDataSwitches(sleep = true, hrv = true), PhysioDataSwitches.read(MapPreferences()))
    }

    @Test
    fun `the switches are read from the two settings`() {
        val preferences = MapPreferences().apply {
            booleans[BooleanKey.AimiPhysioSleepDataEnable] = false
            booleans[BooleanKey.AimiPhysioHRVDataEnable] = false
        }
        assertEquals(PhysioDataSwitches(sleep = false, hrv = false), PhysioDataSwitches.read(preferences))
    }

    // ---------------------------------------------------------------- raw read and features

    @Test
    fun `with both on the raw read is not touched`() {
        assertSame(raw, PhysioDataSwitches.ALL_ON.applyTo(raw))
    }

    @Test
    fun `sleep off removes the sleep reading and keeps the rest`() {
        val result = sleepOff.applyTo(raw)
        assertNull(result.sleep)
        assertEquals(hrv, result.hrv)
        assertEquals(rhr, result.rhr)
        assertEquals(4000, result.steps)
    }

    @Test
    fun `hrv off removes the hrv readings and keeps the rest`() {
        val result = hrvOff.applyTo(raw)
        assertEquals(emptyList(), result.hrv)
        assertEquals(sleep, result.sleep)
        assertEquals(rhr, result.rhr)
    }

    @Test
    fun `sleep off gives the features of a night with no sleep data`() {
        val extractor = AIMIPhysioFeatureExtractorMTR(NoOpAapsLogger)
        val features = extractor.extractFeatures(sleepOff.applyTo(raw))
        val withoutSleep = extractor.extractFeatures(raw.copy(sleep = null))
        assertEquals(0.0, features.sleepDurationHours)
        assertEquals(0.0, features.sleepQualityScore)
        assertEquals(withoutSleep.copy(timestamp = 0), features.copy(timestamp = 0))
    }

    @Test
    fun `hrv off gives the features of a day with no hrv data`() {
        val extractor = AIMIPhysioFeatureExtractorMTR(NoOpAapsLogger)
        val features = extractor.extractFeatures(hrvOff.applyTo(raw))
        assertEquals(0.0, features.hrvMeanRMSSD)
        assertEquals(0.0, features.hrvTrend)
    }

    @Test
    fun `stored features lose the sleep or hrv values of a switch that is off`() {
        val features = PhysioFeaturesMTR(
            sleepDurationHours = 4.0, sleepEfficiency = 0.6, sleepFragmentation = 0.4, sleepQualityScore = 0.5,
            deepSleepPercent = 0.1, hrvMeanRMSSD = 22.0, hrvTrend = -0.5, hrvVariability = 0.3, rhrMorning = 70,
        )
        val noSleep = sleepOff.applyTo(features)
        assertEquals(0.0, noSleep.sleepDurationHours)
        assertEquals(0.0, noSleep.sleepEfficiency)
        assertEquals(0.0, noSleep.sleepFragmentation)
        assertEquals(0.0, noSleep.sleepQualityScore)
        assertEquals(0.0, noSleep.deepSleepPercent)
        assertEquals(22.0, noSleep.hrvMeanRMSSD)
        val noHrv = hrvOff.applyTo(features)
        assertEquals(0.0, noHrv.hrvMeanRMSSD)
        assertEquals(0.0, noHrv.hrvTrend)
        assertEquals(0.0, noHrv.hrvVariability)
        assertEquals(4.0, noHrv.sleepDurationHours)
        assertEquals(70, noHrv.rhrMorning)
    }

    // ---------------------------------------------------------------- stored context

    private val poorSleepContext = PhysioContextMTR(
        state = PhysioStateMTR.RECOVERY_NEEDED,
        confidence = 0.7,
        poorSleepDetected = true,
        sleepDeviationZ = -2.0,
        recommendReduceSMB = true,
        timestamp = 10_000,
        validUntil = 20_000,
        features = PhysioFeaturesMTR(sleepDurationHours = 4.0, sleepQualityScore = 0.5, timestamp = 10_000),
    )

    @Test
    fun `with both on the stored context is not touched`() {
        assertSame(poorSleepContext, PhysioDataSwitches.ALL_ON.applyTo(poorSleepContext))
    }

    @Test
    fun `sleep off clears a poor sleep verdict and what it recommended`() {
        val result = sleepOff.applyTo(poorSleepContext)
        assertFalse(result.poorSleepDetected)
        assertEquals(0.0, result.sleepDeviationZ)
        assertEquals(PhysioStateMTR.OPTIMAL, result.state)
        assertFalse(result.recommendReduceSMB)
        assertEquals(0.0, result.features?.sleepDurationHours)
        // Its age is kept: clearing a reading does not make an old context new.
        assertEquals(10_000, result.timestamp)
        assertEquals(20_000, result.validUntil)
    }

    @Test
    fun `hrv off clears low hrv and leaves the heart rate verdict alone`() {
        val stress = PhysioContextMTR(
            state = PhysioStateMTR.STRESS_DETECTED,
            confidence = 0.8,
            hrvDepressed = true,
            rhrElevated = true,
            hrvDeviationZ = -1.8,
            rhrDeviationZ = 1.7,
            recommendReduceSMB = true,
            recommendIncreaseISF = true,
        )
        val result = hrvOff.applyTo(stress)
        assertFalse(result.hrvDepressed)
        assertEquals(0.0, result.hrvDeviationZ)
        assertTrue(result.rhrElevated)
        assertEquals(1.7, result.rhrDeviationZ)
        // One anomaly left (the heart rate): the engine calls that UNKNOWN, with no recommendation.
        assertEquals(PhysioStateMTR.UNKNOWN, result.state)
        assertFalse(result.recommendReduceSMB)
        assertFalse(result.recommendIncreaseISF)
    }

    @Test
    fun `a context with no sleep verdict keeps its state when sleep is off`() {
        val unknown = PhysioContextMTR.NEUTRAL
        val result = sleepOff.applyTo(unknown)
        assertEquals(PhysioStateMTR.UNKNOWN, result.state)
    }

    // ---------------------------------------------------------------- live snapshot

    private val snapshot = HealthContextSnapshot(
        hrvRmssd = 22.0,
        hrvDepression = 0.6,
        sleepDebtMinutes = 150,
        sleepEfficiency = 0.6,
        hcSleepSessionActive = true,
        rhrResting = 70,
        timestamp = 5_000,
        confidence = 0.9,
        isValid = true,
    )

    @Test
    fun `with both on the snapshot is not touched`() {
        assertSame(snapshot, PhysioDataSwitches.ALL_ON.applyTo(snapshot))
    }

    @Test
    fun `sleep off gives a snapshot with no sleep debt`() {
        val result = sleepOff.applyTo(snapshot)
        assertEquals(0, result.sleepDebtMinutes)
        assertEquals(0.0, result.sleepEfficiency)
        assertFalse(result.hcSleepSessionActive)
        assertEquals(22.0, result.hrvRmssd)
        assertEquals(0.6, result.hrvDepression)
    }

    @Test
    fun `hrv off gives a snapshot with no hrv and no hrv depression`() {
        val result = hrvOff.applyTo(snapshot)
        assertEquals(0.0, result.hrvRmssd)
        assertEquals(0.0, result.hrvDepression)
        assertEquals(150, result.sleepDebtMinutes)
        assertEquals(70, result.rhrResting)
    }

    @Test
    fun `the latent sleep debt score is neutral with sleep off`() {
        fun sleepDebtScore(switches: PhysioDataSwitches): Double = PhysioLatentStateBuilder.build(
            snapshot = switches.applyTo(snapshot),
            sourceSensor = null,
            phaseOutput = null,
            mealAbsorptionOutput = null,
            hypothesisState = null,
            patternSnapshot = null,
            physioContext = switches.applyTo(poorSleepContext),
            physioTrace = null,
            correctionAggressionDecision = null,
            chronicInflammation = null,
            autonomicStress = 0.0,
            inflammationRecovery = 0.0,
            hormonalCircadian = 0.0,
        ).sleepDebtScore
        assertTrue(sleepDebtScore(PhysioDataSwitches.ALL_ON) > 0.5)
        assertEquals(0.0, sleepDebtScore(sleepOff))
    }
}
