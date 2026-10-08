package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aimiengine.AimiCommonEngineSwitch
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimitestkit.AimiTestSnapshots
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locked fasting scene: the pure `decideMealHyperBasalBoost`, fed the same inputs as the
 * Android `ShellDecisionTraceTest.captureFasting`, must produce byte-for-byte what Android
 * produced: `tbrRate=4000000000000000` (2.0 U/h), no duration, no SMB.
 */
class IosNeutralFastingSceneTest {

    @AfterTest
    fun switchOff() {
        AimiCommonEngineSwitch.enabled = false
    }

    @Test
    fun fastingMealHyperReturnsTheLockedOptionalRate() {
        val outcome = iosNeutralFastingMealHyper(mutableListOf())
        assertTrue(
            outcome is AimiMealHyperBasalBoostOutcome.ContinueWithOptionalRate,
            "expected ContinueWithOptionalRate, got $outcome",
        )
        val rate = outcome.rate
        assertTrue(rate != null, "expected a rate, got null")
        assertEquals(
            0x4000000000000000L,
            rate.toRawBits(),
            "fasting TBR rate bits differ from the Android fixture",
        )
    }

    @Test
    fun fastingSceneThroughTheEngineCommandsTwoUnitsPerHour() {
        AimiCommonEngineSwitch.enabled = true
        val (hold, _) = holdAimiEngineWired(IosNeutralScene.FASTING)
        val result = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val tbr = result.command as? AimiTherapyCommand.TempBasal
            ?: error("expected TempBasal, got ${result.command}")
        assertEquals(0x4000000000000000L, tbr.rateUPerHour.toRawBits())
        assertEquals("FASTING_TBR", result.telemetry.reasonCode)
    }
}
