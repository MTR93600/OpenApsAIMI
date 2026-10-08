package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aimiengine.AimiCommonEngineSwitch
import app.aaps.plugins.aimicontracts.AimiTherapyCommand
import app.aaps.plugins.aimitestkit.AimiTestSnapshots
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Locks the hypo-rebound scene: BG 180, delta 0, autodrive on, maxSMB 0.40, last bolus
 * 180 min ago → drift terminator fires with SMB 0.18000000715255737 U (float(0.18)),
 * bit-for-bit like the Android `hypo-rebound` fixture (`smb=3fc70a3d80000000`).
 */
class IosNeutralHypoReboundSceneTest {

    @AfterTest
    fun switchOff() {
        AimiCommonEngineSwitch.enabled = false
        iosNeutralResetHysteresisForTest(mutableListOf())
    }

    @Test
    fun hypoReboundDriftTerminatorDeliversLockedSmb() {
        val outcome = iosNeutralHypoReboundSmb(mutableListOf())
        assertEquals(0x3fc70a3d80000000L, outcome.smbU.toRawBits())
        assertEquals(180.0, outcome.rT.eventualBG)
    }

    @Test
    fun hypoReboundSceneDeliversSmbCommand() {
        AimiCommonEngineSwitch.enabled = true
        val (hold, _) = holdAimiEngineWired(IosNeutralScene.HYPO_REBOUND)
        val result = hold.evaluate(
            AimiTestSnapshots.emptyInput(),
            AimiTestSnapshots.emptyState(),
            AimiTestSnapshots.emptyModels(),
        )
        val command = result.command
        assertTrue(command is AimiTherapyCommand.Smb)
        assertEquals(0x3fc70a3d80000000L, command.insulinU.toRawBits())
        assertEquals("HYPO_REBOUND_SMB", result.telemetry.reasonCode)
    }
}
