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
 * End of the wire for the unfloored counterfactual, with a real barrier in place.
 *
 * `ControlBarrierShieldUnflooredShadowTest` proves the shield can now be asked for an unfloored
 * answer. This one proves the engine actually asks for it, and that asking costs the production
 * decision nothing.
 */
class AutodriveEngineCoefficientShadowTest {

    private val estimator = mockk<ContinuousStateEstimator>(relaxed = true)
    private val mpc = mockk<MpcController>(relaxed = true)
    private val attention = mockk<MechanismAttentionGate>(relaxed = true)
    private val auditor = mockk<AutodriveAuditor>(relaxed = true)

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
        every { attention.applyAttention(any()) } answers { firstArg() }
        every { estimator.getLastRa() } returns state.estimatedRa
        every { estimator.updateAndPredict(any(), any(), any()) } answers { firstArg() }
        every { mpc.calculateOptimalDose(any(), any(), any()) } returns request
        every { auditor.generateHumanReadableReason(any(), any(), any(), any()) } returns "audited"

        return AutodriveEngine(
            aapsLogger = mockk<AAPSLogger>(relaxed = true),
            stateEstimator = estimator,
            mpcController = mpc,
            // The real shield: the whole point is what its arithmetic does with the two coefficients.
            safetyShield = ControlBarrierShield(mockk<AAPSLogger>(relaxed = true)),
            onlineLearner = mockk<OnlineLearner>(relaxed = true),
            autodriveAuditor = auditor,
            dataLake = mockk<AutodriveDataLake>(relaxed = true),
            dataBackfiller = mockk<AutodriveDataBackfiller>(relaxed = true),
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
