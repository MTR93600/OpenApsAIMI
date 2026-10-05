package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.activity.EffortActivityBelief
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The meal scene has no exertion. Protection off leaves the assessment null, so the advisor SMB
 * stays 3.30 U and the paired TBR stays 2.00 U/h. A snapshot failure is logged and does not reduce.
 */
class DecideRefreshEffortActivityBeliefTest {

    @Test
    fun protectionOffLeavesTheMealFactorAtOne() {
        val refresh = decideRefreshEffortActivityBelief(
            protectionEnabled = false,
            t3cEnabled = false,
            snapshot = exertion(),
            nowMs = 1_700_000_000_000L,
            stressResistanceProb = 0.0,
            prior = EffortActivityBelief.Memory(),
        )
        assertNull(refresh.assessment)
        assertNull(refresh.logLine)
        assertEquals(EffortActivityBelief.Memory(), refresh.memory)
        assertEquals(1.0, refresh.smbFactor)
        assertTrue(refresh.smbFactor <= 1.0)
    }

    @Test
    fun invalidSnapshotDoesNotReduce() {
        val prior = EffortActivityBelief.Memory(lastEffortMs = 1L)
        val refresh = decideRefreshEffortActivityBelief(
            protectionEnabled = true,
            t3cEnabled = false,
            snapshot = HealthContextSnapshot(),
            nowMs = 1_700_000_000_000L,
            stressResistanceProb = 0.0,
            prior = prior,
        )
        assertNull(refresh.assessment)
        assertNull(refresh.logLine)
        assertEquals(prior, refresh.memory)
        assertEquals(1.0, refresh.smbFactor)
    }

    @Test
    fun snapshotFailureIsLoggedAndTheAssessmentStaysNull() {
        val log = mutableListOf<String>()
        val signal = readRbtOptional<HealthContextSnapshot>(
            source = "wearableSnapshot",
            consoleLog = log,
            failureLine = { errorType, message ->
                "WEARABLE snapshot failed ($errorType): ${message.orEmpty()} — snapshot empty"
            },
        ) { throw IllegalStateException("watch down") }
        val refresh = decideRefreshEffortActivityBelief(
            protectionEnabled = true,
            t3cEnabled = false,
            snapshot = signal.valueOrNull(),
            nowMs = 1_700_000_000_000L,
            stressResistanceProb = 0.0,
            prior = EffortActivityBelief.Memory(),
        )
        assertNull(refresh.assessment)
        assertEquals(1.0, refresh.smbFactor)
        assertEquals(
            listOf("WEARABLE snapshot failed (IllegalStateException): watch down — snapshot empty"),
            log,
        )
    }

    @Test
    fun corroboratedWalkLogsTheAndroidLineAndDoesNotRaiseTheFactor() {
        val refresh = decideRefreshEffortActivityBelief(
            protectionEnabled = true,
            t3cEnabled = false,
            snapshot = exertion(),
            nowMs = 1_700_000_000_000L,
            stressResistanceProb = 0.0,
            prior = EffortActivityBelief.Memory(),
        )
        val assessment = refresh.assessment ?: error("walk produced no assessment")
        assertTrue(assessment.smbFactor < 1.0)
        assertTrue(assessment.smbFactor <= 1.0)
        assertTrue(assessment.basalFactor <= 1.0)
        assertEquals(assessment.smbFactor, refresh.smbFactor)
        assertEquals(
            "🏃 EFFORT_BELIEF[ACTIVE/EXERTION] SMB ×0.67 (applied at SMB finalize) " +
                "steps=40/min(5=40,15=40),ACTIVE/EXERTION since=0m",
            refresh.logLine,
        )
    }

    private fun exertion() = HealthContextSnapshot(
        stepsLast5m = 200,
        stepsLast15m = 600,
        isValid = true,
    )
}
