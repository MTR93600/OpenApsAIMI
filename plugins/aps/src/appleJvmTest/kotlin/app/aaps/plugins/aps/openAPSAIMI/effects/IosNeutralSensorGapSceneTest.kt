package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aimiengine.AimiCommonEngineSwitch
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimitestkit.AimiTestSnapshots
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the sensor-gap scene: glucose 110 mg/dL stamped 25 min old aborts the tick
 * on stale data, bit-for-bit like the Android `sensor-gap` fixture
 * (`tbrRate=absent`, `smb=absent`, `eventual=110.0`).
 */
class IosNeutralSensorGapSceneTest {

    @AfterTest
    fun switchOff() {
        AimiCommonEngineSwitch.enabled = false
        iosNeutralResetHysteresisForTest(mutableListOf())
    }

    @Test
    fun sensorGapSignalAbortsOnStaleData() {
        val outcome = iosNeutralSensorGapSignal(mutableListOf())
        assertTrue(
            outcome is AimiSignalPrepPkpd.StaleAbort,
            "expected StaleAbort, got $outcome",
        )
        // Locked by the Android fixture: eventual=405b800000000000 (110.0).
        assertEquals(110.0, outcome.rT.eventualBG)
    }

    @Test
    fun sensorGapSceneHoldsWithStaleDataReason() {
        AimiCommonEngineSwitch.enabled = true
        val (hold, _) = holdAimiEngineWired(IosNeutralScene.SENSOR_GAP)
        val result = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val command = result.command
        assertTrue(command is AimiTherapyCommand.Hold)
        assertEquals("STALE_DATA", command.reasonCode)
        assertEquals("STALE_DATA", result.telemetry.reasonCode)
        assertEquals("STALE_DATA", result.safety.holdReasonCode)
    }
}
