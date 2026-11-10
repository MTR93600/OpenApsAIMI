package app.aaps.plugins.aps.openAPSAIMI.autodrive

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.controller.MpcController
import app.aaps.plugins.aps.openAPSAIMI.autodrive.estimator.ContinuousStateEstimator
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveAuditor
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataBackfiller
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.AutodriveDataLake
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.MechanismAttentionGate
import app.aaps.plugins.aps.openAPSAIMI.autodrive.learning.OnlineLearner
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.ControlBarrierShield
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * One training row per tick, labelled by the path that actually owned the dose.
 *
 * `AutodriveEngine.tick()` used to write to the dataset itself. It is reached from three places — the
 * engaged Autodrive branch, the T3C shadow tick, and `proposeBasalOnlyTbr` which delegates to
 * `tick()` — so a T3C tick with Autodrive engaged produced two or three rows carrying the same
 * timestamp, in a count that depended on which preferences were on.
 *
 * Worse, all of them were labelled `Engaged = 1`, including proposals whose SMB the caller strips and
 * which never reach the pump. The `Engaged` column exists so the classifier can condition on
 * engagement; feeding it mislabelled rows defeats the whole point of adding it.
 */
class AutodriveEngineTrainingRowTest {

    private val dataLake = mockk<AutodriveDataLake>(relaxed = true)
    private val estimator = mockk<ContinuousStateEstimator>(relaxed = true)
    private val mpc = mockk<MpcController>(relaxed = true)
    private val shield = mockk<ControlBarrierShield>(relaxed = true)
    private val attention = mockk<MechanismAttentionGate>(relaxed = true)
    private val auditor = mockk<AutodriveAuditor>(relaxed = true)

    private val state = AutoDriveState(
        bg = 140.0,
        bgVelocity = 1.0,
        iob = 1.0,
        estimatedSI = 45.0 / 10000.0,
        physiologicalStressMask = DoubleArray(3),
    )

    private val command = AutoDriveCommand(
        scheduledMicroBolus = 0.5,
        temporaryBasalRate = 1.2,
        isSafe = true,
        reason = "test",
    )

    private fun engine(): AutodriveEngine {
        every { attention.applyAttention(any()) } answers { firstArg() }
        every { estimator.getLastRa() } returns 0.3
        every { estimator.updateAndPredict(any(), any(), any()) } answers { firstArg() }
        every { mpc.calculateOptimalDose(any(), any(), any()) } returns command
        every { shield.enforce(any(), any(), any(), any()) } returns command
        every { auditor.generateHumanReadableReason(any(), any(), any(), any()) } returns "audited"

        return AutodriveEngine(
            aapsLogger = mockk<AAPSLogger>(relaxed = true),
            stateEstimator = estimator,
            mpcController = mpc,
            safetyShield = shield,
            onlineLearner = mockk<OnlineLearner>(relaxed = true),
            autodriveAuditor = auditor,
            dataLake = dataLake,
            dataBackfiller = mockk<AutodriveDataBackfiller>(relaxed = true),
            attentionGate = attention,
        )
    }

    private fun AutodriveEngine.runTick(tickId: Long, engaged: Boolean) = tick(
        currentState = state,
        profileBasal = 1.0,
        profileIsf = 45.0,
        lgsThreshold = 70.0,
        hour = 12,
        steps = 0,
        hr = 70,
        rhr = 60,
        currentEpochMs = tickId,
        tickId = tickId,
        engaged = engaged,
    )

    @Test
    fun `nothing is written until the tick is flushed`() {
        val engine = engine()
        engine.setShadowMode(false)
        engine.setIsActive(true)

        engine.runTick(tickId = 100L, engaged = true)

        verify(exactly = 0) { dataLake.recordSnapshot(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `three calls in one tick produce exactly one row`() {
        val engine = engine()
        engine.setShadowMode(false)
        engine.setIsActive(true)

        engine.runTick(tickId = 100L, engaged = false)   // T3C shadow
        engine.runTick(tickId = 100L, engaged = true)    // engaged branch
        engine.runTick(tickId = 100L, engaged = false)   // basal-only proposal
        engine.flushTickRow(100L)

        verify(exactly = 1) { dataLake.recordSnapshot(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `the engaged row wins whatever the order`() {
        val engagedFlag = slot<Boolean>()
        val engine = engine()
        engine.setShadowMode(false)
        engine.setIsActive(true)

        // Engaged first, then two shadow paths that must not overwrite it.
        engine.runTick(tickId = 200L, engaged = true)
        engine.runTick(tickId = 200L, engaged = false)
        engine.flushTickRow(200L)

        verify { dataLake.recordSnapshot(any(), any(), any(), capture(engagedFlag), any()) }
        assertThat(engagedFlag.captured).isTrue()
    }

    @Test
    fun `a shadow-only tick is labelled disengaged`() {
        val engagedFlag = slot<Boolean>()
        val engine = engine()
        engine.setShadowMode(true)
        engine.setIsActive(false)

        engine.runTick(tickId = 300L, engaged = false)
        engine.flushTickRow(300L)

        verify { dataLake.recordSnapshot(any(), any(), any(), capture(engagedFlag), any()) }
        assertThat(engagedFlag.captured).isFalse()
    }

    @Test
    fun `an observation-only tick stages a disengaged row`() {
        val engagedFlag = slot<Boolean>()
        val engine = engine()

        engine.stageDisengagedSnapshot(state, tickId = 400L, currentEpochMs = 400L)
        engine.flushTickRow(400L)

        verify { dataLake.recordSnapshot(any(), any(), any(), capture(engagedFlag), any()) }
        assertThat(engagedFlag.captured).isFalse()
    }

    @Test
    fun `a tick that staged nothing writes nothing`() {
        engine().flushTickRow(500L)

        verify(exactly = 0) { dataLake.recordSnapshot(any(), any(), any(), any(), any()) }
    }

    /**
     * If a tick throws before its flush, the stale row must not be attributed to the next tick — its
     * decision columns describe five minutes that have passed.
     */
    @Test
    fun `a row left over from an earlier tick is dropped, not misattributed`() {
        val engine = engine()

        engine.stageDisengagedSnapshot(state, tickId = 600L, currentEpochMs = 600L)
        engine.flushTickRow(601L)

        verify(exactly = 0) { dataLake.recordSnapshot(any(), any(), any(), any(), any()) }
    }
}
