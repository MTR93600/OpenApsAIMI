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
 * End of the wire for the unfloored counterfactual, with a real barrier in place.
 *
 * `ControlBarrierShieldUnflooredShadowTest` proves the shield can now be asked for an unfloored
 * answer. This one proves the engine actually asks for it, and that asking costs the production
 * decision nothing.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `db21308e6c` (unchanged on freeze `c5db5a0333`).
 * Study source set: [AutodriveEngine] is androidMain, so this lives in `androidHostTest` + mockito
 * (not mockk). Production calls `updateAndPredict(state, tickId = observationId)` — two named args;
 * Mockito stubs the JVM 3-arg form so that named-arg call still matches.
 */
class AutodriveEngineCoefficientShadowTest : TestBase() {

    @Mock private lateinit var estimator: ContinuousStateEstimator
    @Mock private lateinit var mpc: MpcController
    @Mock private lateinit var attention: MechanismAttentionGate
    @Mock private lateinit var dataLake: AutodriveDataLake
    @Mock private lateinit var dataBackfiller: AutodriveDataBackfiller

    /** Median profile ISF on this deployment, and well under the reference 45. */
    private val resistantIsf = 30.0

    private val state = AutoDriveState(
        bg = 150.0,
        bgVelocity = -1.0,
        iob = 2.0,
        estimatedSI = resistantIsf / 10000.0,
        estimatedRa = 1.0,
        physiologicalStressMask = DoubleArray(0),
        maxIOB = 60.0,
    )

    /** More than the barrier can permit here, so the barrier is what limits the result. */
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
            // The real shield: the whole point is what its arithmetic does with the two coefficients.
            safetyShield = ControlBarrierShield(aapsLogger),
            onlineLearner = OnlineLearner(aapsLogger),
            autodriveAuditor = AutodriveAuditor(),
            dataLake = dataLake,
            dataBackfiller = dataBackfiller,
            attentionGate = attention,
        )
    }

    private fun AutodriveEngine.runTick(): AutoDriveCommand? {
        setShadowMode(false)
        setIsActive(true)
        return tick(
            currentState = state,
            profileBasal = 1.0,
            profileIsf = resistantIsf,
            lgsThreshold = 70.0,
            hour = 3,
            steps = 0,
            hr = 60,
            rhr = 58,
            currentEpochMs = 1_000L,
            tickId = 1_000L,
            observationId = 1L,
        )
    }

    /**
     * The lock for the shadow. Before the fix these two were equal on every tick, because the
     * "unfloored sensitivity" the shadow built reduced to the sensitivity production already used.
     */
    @Test
    fun `the unfloored shadow permits more than the floored barrier at ISF under 45`() {
        val engine = engine()
        engine.runTick()

        assertThat(engine.lastControlCoefficientUsed)
            .isWithin(1e-12).of(InsulinActionModel.LEGACY_CONTROL_COEFFICIENT)
        assertThat(engine.lastControlCoefficientUnfloored)
            .isLessThan(engine.lastControlCoefficientUsed)
        assertThat(engine.lastCbfPermittedUnflooredU).isGreaterThan(engine.lastCbfPermittedU)
    }

    /**
     * The shadow runs `enforce` a second time on the same tick. What the loop reads afterwards must
     * still describe the production call: the returned command, and the barrier terms.
     */
    @Test
    fun `the shadow leaves the production command and diagnostics untouched`() {
        val engine = engine()
        val command = engine.runTick()

        assertThat(command).isNotNull()
        val permitted = command!!.scheduledMicroBolus + command.temporaryBasalRate / 12.0
        assertThat(permitted).isWithin(1e-12).of(engine.lastCbfPermittedU)

        val diagnostics = engine.lastBarrierDiagnostics
        assertThat(diagnostics).isNotNull()
        // The floored coefficient, i.e. the production call, not the shadow's unfloored one.
        assertThat(diagnostics!!.siMetabolic)
            .isWithin(1e-12).of(InsulinActionModel.LEGACY_CONTROL_COEFFICIENT)
    }

    /**
     * FIX F: the anchor the barrier is handed is the dynamic ISF, and the export must say so.
     *
     * The engine defaults `profileIsfIsDynamic` to `true` because the only production caller passes
     * `profile.sens`. This is a diagnostic, not a behaviour: nothing in the dose reads it.
     */
    @Test
    fun `the barrier records that its anchor is the dynamic ISF`() {
        val engine = engine()
        engine.runTick()

        assertThat(engine.lastBarrierDiagnostics!!.anchorIsDynamicIsf).isTrue()
    }
}
