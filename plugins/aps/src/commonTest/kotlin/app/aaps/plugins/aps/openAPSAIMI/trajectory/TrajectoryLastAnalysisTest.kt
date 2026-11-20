package app.aaps.plugins.aps.openAPSAIMI.trajectory

import app.aaps.core.interfaces.aps.APSResult
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.effects.AimiTrajectoryCalls
import app.aaps.plugins.aps.openAPSAIMI.effects.decideTrajectoryAnalysis
import app.aaps.plugins.aps.openAPSAIMI.pkpd.ActivityStage
import app.aaps.plugins.aps.openAPSAIMI.pkpd.InsulinActionState
import app.aaps.plugins.aps.openAPSAIMI.pkpd.zeroedAimiProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * R-T1: the last trajectory analysis is the one of the last loop run, never one from the Auditor or the
 * peak nudge, and never one from before the guard was switched off.
 *
 * Before the fix [TrajectoryGuard.analyzeTrajectory] stored every result, from any caller, and nothing
 * cleared it. With the Auditor on and the guard off, the tight spiral bridge could then cut the basal
 * from an analysis made by the Auditor.
 */
class TrajectoryLastAnalysisTest {

    private val analysis = TrajectoryAnalysis(
        classification = TrajectoryType.TIGHT_SPIRAL,
        metrics = TrajectoryMetrics(
            curvature = 0.40,
            convergenceVelocity = 0.0,
            coherence = 0.8,
            energyBalance = 4.0,
            openness = 0.2,
        ),
        modulation = TrajectoryModulation.NEUTRAL,
        warnings = emptyList(),
        stableOrbitDistance = 0.0,
        predictedConvergenceTime = null,
    )

    /** Six 5-minute states of a falling glucose with insulin on board: enough for a real analysis. */
    private fun history(): List<PhaseSpaceState> = (0 until 6).map { i ->
        PhaseSpaceState(
            timestamp = i * 300_000L,
            bg = 180.0 - i * 6.0,
            bgDelta = -6.0,
            bgAccel = 0.0,
            insulinActivity = 1.5,
            iob = 3.0 - i * 0.1,
            pkpdStage = ActivityStage.PEAK,
            timeSinceLastBolus = 30 + i * 5,
        )
    }

    private val orbit = StableOrbit.fromProfile(targetBg = 100.0, basalRate = 1.0)

    // --- TrajectoryGuard ---

    @Test
    fun analyzeDoesNotChangeTheLastAnalysis() {
        val guard = TrajectoryGuard(NoOpAapsLogger)
        assertNotNull(guard.analyzeTrajectory(history(), orbit))
        // The Auditor and the peak nudge call this; it must not become the loop's analysis.
        assertNull(guard.getLastAnalysis())
    }

    @Test
    fun analyzeDoesNotReplaceTheRecordedAnalysis() {
        val guard = TrajectoryGuard(NoOpAapsLogger)
        guard.recordTickAnalysis(analysis)
        guard.analyzeTrajectory(history(), orbit)
        assertSame(analysis, guard.getLastAnalysis())
    }

    @Test
    fun recordedAnalysisIsReadBack() {
        val guard = TrajectoryGuard(NoOpAapsLogger)
        guard.recordTickAnalysis(analysis)
        assertSame(analysis, guard.getLastAnalysis())
    }

    @Test
    fun recordingNullClearsTheOldAnalysis() {
        val guard = TrajectoryGuard(NoOpAapsLogger)
        guard.recordTickAnalysis(analysis)
        guard.recordTickAnalysis(null)
        assertNull(guard.getLastAnalysis())
    }

    // --- The loop run (decideTrajectoryAnalysis) ---

    private class FakeCalls(private val result: TrajectoryAnalysis?) : AimiTrajectoryCalls {
        val recorded = mutableListOf<TrajectoryAnalysis?>()
        var analyzeCalls = 0
        override fun refreshEffectiveProfile(currentTime: Long) = Unit
        override fun trajectoryHistory(
            currentTime: Long,
            bg: Double,
            delta: Double,
            bgacc: Double,
            iobActivityNow: Double,
            iob: Float,
            insulinActionState: InsulinActionState,
            lastBolusAgeMinutes: Double,
            cob: Float,
            profile: OapsProfileAimi,
        ): List<PhaseSpaceState> = emptyList()
        override fun analyze(history: List<PhaseSpaceState>, orbit: StableOrbit): TrajectoryAnalysis? {
            analyzeCalls++
            return result
        }
        override fun recordAnalysis(analysis: TrajectoryAnalysis?) {
            recorded += analysis
        }
        override fun uamConfidence() = 0.0
        override fun maxSmb() = 1.0
        override fun setMaxSmb(value: Double) = Unit
        override fun maxSmbHb() = 1.0
        override fun setMaxSmbHb(value: Double) = Unit
        override fun intervalSmb() = 3
        override fun setIntervalSmb(value: Int) = Unit
        override fun maxIob() = 5.0
        override fun setMaxIob(value: Double) = Unit
        override fun postTrajectoryWarning(message: String) = Unit
        override fun logTrajectoryFailure(error: Exception) = Unit
    }

    private fun runTick(guardOn: Boolean, calls: AimiTrajectoryCalls) {
        val preferences = MapPreferences()
        preferences.booleans[BooleanKey.OApsAIMITrajectoryGuardEnabled] = guardOn
        decideTrajectoryAnalysis(
            currentTime = 0L,
            bg = 120.0,
            delta = 0.0,
            bgacc = 0.0,
            iobActivityNow = 0.0,
            iob = 1.0f,
            insulinActionState = InsulinActionState.default(),
            lastBolusAgeMinutes = 60.0,
            cob = 0.0f,
            targetBg = 100.0,
            profile = zeroedAimiProfile(),
            rT = RT(algorithm = APSResult.Algorithm.AIMI, runningDynamicIsf = true),
            relevanceScore = 1.0,
            preferences = preferences,
            consoleLog = mutableListOf(),
            calls = calls,
        )
    }

    @Test
    fun guardOffClearsTheLastAnalysis() {
        val calls = FakeCalls(result = analysis)
        runTick(guardOn = false, calls = calls)
        assertEquals(0, calls.analyzeCalls)
        assertEquals(listOf<TrajectoryAnalysis?>(null), calls.recorded)
    }

    @Test
    fun guardOnRecordsTheAnalysisOfThisRun() {
        val calls = FakeCalls(result = analysis)
        runTick(guardOn = true, calls = calls)
        assertEquals(listOf<TrajectoryAnalysis?>(analysis), calls.recorded)
    }

    @Test
    fun warmingUpClearsTheLastAnalysis() {
        val calls = FakeCalls(result = null)
        runTick(guardOn = true, calls = calls)
        assertEquals(listOf<TrajectoryAnalysis?>(null), calls.recorded)
    }

    @Test
    fun aFailedAnalysisClearsTheLastAnalysis() {
        val calls = object : AimiTrajectoryCalls by FakeCalls(result = analysis) {
            val recorded = mutableListOf<TrajectoryAnalysis?>()
            override fun analyze(history: List<PhaseSpaceState>, orbit: StableOrbit): TrajectoryAnalysis? =
                error("analysis failed")
            override fun recordAnalysis(analysis: TrajectoryAnalysis?) {
                recorded += analysis
            }
        }
        runTick(guardOn = true, calls = calls)
        assertEquals(listOf<TrajectoryAnalysis?>(null), calls.recorded)
    }
}
