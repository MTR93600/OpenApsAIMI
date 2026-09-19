package app.aaps.plugins.aps.openAPSAIMI.autodrive

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
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * A tick that leaves early must not hand the previous tick's barrier to the export.
 *
 * Every field the `control_barrier` export reads is written late inside [AutodriveEngine.tick], and
 * `tick` has two exits that come before all of them. Nothing used to clear those fields, so a tick
 * that left early published the last tick that did run. Measured on the night of 2026-09-05 the
 * export carried a barrier block on 281 ticks out of 712, and nothing in it said which tick it
 * described.
 *
 * Matching test from `origin/dev_OAPSAIMI` @ `db21308e6c` (unchanged on freeze `c5db5a0333`).
 * Study source set: [AutodriveEngine] is androidMain, so this lives in `androidHostTest` + mockito
 * (not mockk). Production calls `updateAndPredict(state, tickId = observationId)` — two named args;
 * `nowMs` is the Kotlin default. Mockito stubs the JVM 3-arg form so that named-arg call still matches.
 */
class AutodriveEngineStaleObservationTest : TestBase() {

    @Mock private lateinit var estimator: ContinuousStateEstimator
    @Mock private lateinit var mpc: MpcController
    @Mock private lateinit var attention: MechanismAttentionGate
    @Mock private lateinit var dataLake: AutodriveDataLake
    @Mock private lateinit var dataBackfiller: AutodriveDataBackfiller

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
        whenever(attention.applyAttention(any())).thenAnswer { it.getArgument(0) }
        whenever(estimator.getLastRa()).thenReturn(state.estimatedRa)
        whenever(estimator.updateAndPredict(any(), any(), any())).thenAnswer { it.getArgument(0) }
        whenever(mpc.calculateOptimalDose(any(), any(), any())).thenReturn(request)
        return AutodriveEngine(
            aapsLogger = aapsLogger,
            stateEstimator = estimator,
            mpcController = mpc,
            safetyShield = ControlBarrierShield(aapsLogger),
            onlineLearner = OnlineLearner(aapsLogger),
            autodriveAuditor = AutodriveAuditor(),
            dataLake = dataLake,
            dataBackfiller = dataBackfiller,
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
