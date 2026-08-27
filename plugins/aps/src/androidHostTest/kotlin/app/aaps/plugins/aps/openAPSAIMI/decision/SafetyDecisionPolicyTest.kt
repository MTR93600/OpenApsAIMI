package app.aaps.plugins.aps.openAPSAIMI.decision

import app.aaps.plugins.aps.openAPSAIMI.model.AimiSettings
import app.aaps.plugins.aps.openAPSAIMI.model.BgSnapshot
import app.aaps.plugins.aps.openAPSAIMI.model.DecisionResult
import app.aaps.plugins.aps.openAPSAIMI.model.LoopContext
import app.aaps.plugins.aps.openAPSAIMI.model.LoopProfile
import app.aaps.plugins.aps.openAPSAIMI.model.ModeState
import app.aaps.plugins.aps.openAPSAIMI.model.PumpCaps
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class SafetyDecisionPolicyTest : TestBase() {

    private fun context(bg: Double, lgs: Double): LoopContext {
        return LoopContext(
            bg = BgSnapshot(
                mgdl = bg,
                delta5 = 0.0,
                shortAvgDelta = 0.0,
                longAvgDelta = 0.0,
                epochMillis = 1L
            ),
            iobU = 0.0,
            cobG = 0.0,
            profile = LoopProfile(
                targetMgdl = 100.0,
                isfMgdlPerU = 40.0,
                basalProfileUph = 1.0,
                lgsThreshold = lgs
            ),
            pump = PumpCaps(
                basalStep = 0.05,
                bolusStep = 0.05,
                minDurationMin = 30,
                maxBasal = 2.0,
                maxSmb = 1.0
            ),
            modes = ModeState(),
            settings = AimiSettings(smbIntervalMin = 5, wCycleEnabled = false),
            tdd24hU = 40.0,
            eventualBg = bg,
            nowEpochMillis = 1L
        )
    }

    @Test
    fun lgs_applies_zero_temp_when_bg_below_threshold() {
        val result = SafetyDecisionPolicy().applyDecision(context(bg = 60.0, lgs = 70.0))
        assertThat(result).isInstanceOf(DecisionResult.Applied::class.java)
        val applied = result as DecisionResult.Applied
        assertThat(applied.tbrUph).isEqualTo(0.0)
        assertThat(applied.tbrMin).isEqualTo(30)
    }

    @Test
    fun lgs_falls_through_when_bg_at_or_above_threshold() {
        val result = SafetyDecisionPolicy().applyDecision(context(bg = 80.0, lgs = 70.0))
        assertThat(result).isInstanceOf(DecisionResult.Fallthrough::class.java)
    }
}
