package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveCommand
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import com.google.common.truth.Truth.assertThat
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The barrier must be able to say *why* it permitted nothing.
 *
 * `cbf_permitted_u` is the dose that came out, already bounded by what the solver asked for, so a
 * zero there cannot distinguish "the solver wanted nothing" from "the barrier suspended everything".
 * On the 2026-08-14 lunch that ambiguity was read the first way and recorded in
 * `docs/AIMI_NEXT_SESSION.md` §AQ6-4 as "the controller asks for nothing at all". It was the second
 * way: the barrier was suspending the dose on every tick of the plateau.
 *
 * The state below is that plateau, 13:52 — BG 220.4, IOB 10.19, Ra 2.81, no declared carbs.
 */
class ControlBarrierShieldDiagnosticsTest {

    /** Fresh instance per call: `lastBgVelocity` is instance state and would change `activeGamma`. */
    private fun shield() = ControlBarrierShield(mockk<AAPSLogger>(relaxed = true))

    private fun plateauState() = AutoDriveState(
        bg = 220.4,
        bgVelocity = -1.8,
        iob = 10.19,
        estimatedSI = 45.0 / 10000.0,
        estimatedRa = 2.81,
        physiologicalStressMask = DoubleArray(0),
        maxIOB = 20.0,
    )

    private fun request() = AutoDriveCommand(
        scheduledMicroBolus = 2.0,
        temporaryBasalRate = 1.2,
        isSafe = true,
        reason = "test",
    )

    @Test
    fun `the plateau tick is a barrier suspension, and the diagnostics say so`() {
        val out = shield().let { s ->
            val c = s.enforce(request(), plateauState(), profileBasal = 0.6, safetySi = 45.0 / 10000.0)
            c to s.lastDiagnostics
        }
        val command = out.first
        val d = out.second

        // Nothing reached the pump...
        assertThat(command.scheduledMicroBolus).isEqualTo(0.0)
        // ...and the diagnostics attribute it to the barrier rather than to the solver.
        assertThat(d).isNotNull()
        assertThat(d!!.fullySuspended).isTrue()
        assertThat(d.safeU).isNotNull()
        assertThat(d.safeU!!).isLessThan(0.0)
    }

    @Test
    fun `the insulin term is the reason, and it is far larger than the observed fall`() {
        val s = shield()
        s.enforce(request(), plateauState(), profileBasal = 0.6, safetySi = 45.0 / 10000.0)
        val d = s.lastDiagnostics!!

        // -siMetabolic * iob * bg at ISF 45 and MPC_TAU_MIN 75 = -0.005 * 10.19 * 220.4.
        assertThat(d.insulinTermMgdlPerMin).isWithin(1e-6).of(-0.005 * 10.19 * 220.4)
        // Which is a predicted fall of about 56 mg/dL per 5 minutes. The measured fall over that same
        // 5 minutes was 220.4 -> 211.5, i.e. about 9. The term dominates lfh and drives safeU negative.
        assertThat(d.insulinTermMgdlPerMin * 5.0).isLessThan(-50.0)
        assertThat(d.lfhMgdlPerMin).isLessThan(d.safetyBoundary)
    }

    @Test
    fun `a tick with real headroom is not reported as suspended`() {
        val s = shield()
        val calm = AutoDriveState(
            bg = 150.0,
            bgVelocity = 0.0,
            iob = 1.0,
            estimatedSI = 45.0 / 10000.0,
            estimatedRa = 1.0,
            physiologicalStressMask = DoubleArray(0),
            maxIOB = 20.0,
        )
        s.enforce(request(), calm, profileBasal = 0.6, safetySi = 45.0 / 10000.0)
        val d = s.lastDiagnostics!!

        assertThat(d.fullySuspended).isFalse()
    }
}
