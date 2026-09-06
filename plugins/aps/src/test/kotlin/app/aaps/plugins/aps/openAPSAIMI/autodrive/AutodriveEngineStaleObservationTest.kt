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
import org.junit.jupiter.api.Test

/**
 * A tick that leaves early must not hand the previous tick's barrier to the export.
 *
 * Every field the `control_barrier` export reads is written late inside [AutodriveEngine.tick], and
 * `tick` has two exits that come before all of them. Nothing used to clear those fields, so a tick
 * that left early published the last tick that did run. Measured on the night of 2026-09-05 the
 * export carried a barrier block on 281 ticks out of 712, and nothing in it said which tick it
 * described.
 */
class AutodriveEngineStaleObservationTest {

    private val estimator = mockk<ContinuousStateEstimator>(relaxed = true)
    private val mpc = mockk<MpcController>(relaxed = true)
    private val attention = mockk<MechanismAttentionGate>(relaxed = true)
    private val auditor = mockk<AutodriveAuditor>(relaxed = true)

    private val state = AutoDriveState(
        bg = 150.0,
        bgVelocity = -1.0,
        iob = 2.0,
        estimatedSI = 30.0 / 10000.0,
        estimatedRa = 1.0,
        physiologicalStressMask = DoubleArray(0),
        maxIOB = 60.0,
    )

    private val request = AutoDriveCommand(
        scheduledMicroBolus = 6.0,
        temporaryBasalRate = 1.2,
        isSafe = true,
        reason = "test",
    )

    private fun engine(): AutodriveEngine {
        every { attention.applyAttention(any()) } answers { firstArg() }
        every { estimator.getLastRa() } returns state.estimatedRa
        every { estimator.updateAndPredict(any(), any(), any()) } answers { firstArg() }
        every { mpc.calculateOptimalDose(any(), any(), any()) } returns request
        every { auditor.generateHumanReadableReason(any(), any(), any(), any()) } returns "audited"
        return AutodriveEngine(
            aapsLogger = mockk<AAPSLogger>(relaxed = true),
            stateEstimator = estimator,
            mpcController = mpc,
            safetyShield = ControlBarrierShield(mockk<AAPSLogger>(relaxed = true)),
            onlineLearner = mockk<OnlineLearner>(relaxed = true),
            autodriveAuditor = auditor,
            dataLake = mockk<AutodriveDataLake>(relaxed = true),
            dataBackfiller = mockk<AutodriveDataBackfiller>(relaxed = true),
            attentionGate = attention,
        )
    }

    private fun AutodriveEngine.runTick(observationId: Long): AutoDriveCommand? = tick(
        currentState = state,
        profileBasal = 1.0,
        profileIsf = 30.0,
        lgsThreshold = 70.0,
        hour = 3,
        steps = 0,
        hr = 60,
        rhr = 58,
        currentEpochMs = 1_000L * observationId,
        tickId = 1_000L * observationId,
        observationId = observationId,
    )

    @Test
    fun `an early exit leaves no barrier observation behind`() {
        val engine = engine()

        // A tick that really runs, so every observation field holds a value.
        engine.setShadowMode(false)
        engine.setIsActive(true)
        engine.runTick(observationId = 1L)
        assertThat(engine.lastBarrierDiagnostics).isNotNull()
        assertThat(engine.lastCbfPermittedU.isFinite()).isTrue()
        assertThat(engine.lastProfileIsfSeen).isGreaterThan(0.0)

        // The next tick leaves at the "neither active nor shadow" exit, before any of them is written.
        engine.setIsActive(false)
        engine.setShadowMode(false)
        assertThat(engine.runTick(observationId = 2L)).isNull()

        assertThat(engine.lastBarrierDiagnostics).isNull()
        assertThat(engine.lastMpcRawSmbU.isFinite()).isFalse()
        assertThat(engine.lastMpcRawTbrUph.isFinite()).isFalse()
        assertThat(engine.lastCbfPermittedU.isFinite()).isFalse()
        assertThat(engine.lastCbfPermittedUnflooredU.isFinite()).isFalse()
        assertThat(engine.lastControlCoefficientUsed).isEqualTo(0.0)
        assertThat(engine.lastControlCoefficientUnfloored).isEqualTo(0.0)
        assertThat(engine.lastProfileIsfSeen).isEqualTo(0.0)
    }
}
