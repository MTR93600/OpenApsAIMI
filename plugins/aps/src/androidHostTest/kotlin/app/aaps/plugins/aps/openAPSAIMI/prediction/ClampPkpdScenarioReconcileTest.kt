package app.aaps.plugins.aps.openAPSAIMI.prediction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClampPkpdScenarioReconcileTest {

    private fun baseInput(
        bgMgdl: Double = 145.0,
        targetBgMgdl: Double = 100.0,
        deltaMgdl5m: Double = 2.0,
        pkpdEventualMgdl: Double = 39.0,
        scenarioTerminalMgdl: Double = 180.0,
        scenarioPathMinMgdl: Double = 95.0,
        scenarioPathMinHitFloor: Boolean = false,
        digestionOrMealActive: Boolean = false,
        sportTime: Boolean = false,
        postHypoDeliveryActive: Boolean = false,
    ) = ClampPkpdScenarioReconcile.Input(
        bgMgdl = bgMgdl,
        targetBgMgdl = targetBgMgdl,
        deltaMgdl5m = deltaMgdl5m,
        pkpdEventualMgdl = pkpdEventualMgdl,
        scenarioTerminalMgdl = scenarioTerminalMgdl,
        scenarioPathMinMgdl = scenarioPathMinMgdl,
        scenarioPathMinHitFloor = scenarioPathMinHitFloor,
        digestionOrMealActive = digestionOrMealActive,
        sportTime = sportTime,
        postHypoDeliveryActive = postHypoDeliveryActive,
    )

    @Test
    fun classicZone2FalseFloorIsLiftedTowardScenario() {
        val res = ClampPkpdScenarioReconcile.reconcile(baseInput())
        assertTrue(res.reconciled)
        assertEquals("ZONE2", res.reason)
        // Cap = max(zone2Upper-1, bg-5) = max(169, 140) = 169; terminal 180 → 169
        assertEquals(169.0, res.eventualMgdl, 0.001)
    }

    @Test
    fun digestionHighBgZone3LiftsFloorSoStackingCannotCrushOnPkpdAlone() {
        // BG 200 (zone-3), pkpd floor 39, scenario high — classic zone-2 arm false, digestion arm true.
        val res = ClampPkpdScenarioReconcile.reconcile(
            baseInput(
                bgMgdl = 200.0,
                pkpdEventualMgdl = 39.0,
                scenarioTerminalMgdl = 220.0,
                digestionOrMealActive = true,
            ),
        )
        assertTrue(res.reconciled)
        assertEquals("DIGESTION_HIGH_BG", res.reason)
        // Cap = max(169, 195) = 195; terminal 220 → 195 (≥ bg-6 so stacking crush gate clears)
        assertEquals(195.0, res.eventualMgdl, 0.001)
        assertTrue(res.eventualMgdl >= 200.0 - 6.0)
    }

    @Test
    fun zone3WithoutDigestionDoesNotReconcile() {
        val res = ClampPkpdScenarioReconcile.reconcile(
            baseInput(
                bgMgdl = 200.0,
                digestionOrMealActive = false,
            ),
        )
        assertFalse(res.reconciled)
        assertEquals(39.0, res.eventualMgdl, 0.001)
    }

    @Test
    fun fallingBgVetoesReconcile() {
        val res = ClampPkpdScenarioReconcile.reconcile(
            baseInput(deltaMgdl5m = -4.0, digestionOrMealActive = true),
        )
        assertFalse(res.reconciled)
    }

    @Test
    fun postHypoDeliveryVetoesReconcile() {
        val res = ClampPkpdScenarioReconcile.reconcile(
            baseInput(postHypoDeliveryActive = true, digestionOrMealActive = true),
        )
        assertFalse(res.reconciled)
    }

    @Test
    fun sportVetoesReconcile() {
        val res = ClampPkpdScenarioReconcile.reconcile(
            baseInput(sportTime = true),
        )
        assertFalse(res.reconciled)
    }

    @Test
    fun scenarioPathMinFloorBlocksReconcile() {
        val res = ClampPkpdScenarioReconcile.reconcile(
            baseInput(scenarioPathMinHitFloor = true),
        )
        assertFalse(res.reconciled)
    }

    @Test
    fun lowScenarioPathMinBlocksReconcile() {
        val res = ClampPkpdScenarioReconcile.reconcile(
            baseInput(scenarioPathMinMgdl = 70.0),
        )
        assertFalse(res.reconciled)
    }

    @Test
    fun insufficientDivergenceBlocksReconcile() {
        val res = ClampPkpdScenarioReconcile.reconcile(
            baseInput(pkpdEventualMgdl = 100.0, scenarioTerminalMgdl = 120.0),
        )
        assertFalse(res.reconciled)
    }

    @Test
    fun authorityUpliftedEventualAboveZone1_isThinNoOp() {
        // D4.3: when candidate eventual already clears zone-1, Clamp must not re-write.
        val res = ClampPkpdScenarioReconcile.reconcile(
            baseInput(pkpdEventualMgdl = 160.0, digestionOrMealActive = true),
        )
        assertFalse(res.reconciled)
        assertEquals(160.0, res.eventualMgdl, 0.001)
    }

    @Test
    fun zone2PlusDigestionUsesCombinedReason() {
        val res = ClampPkpdScenarioReconcile.reconcile(
            baseInput(digestionOrMealActive = true),
        )
        assertTrue(res.reconciled)
        assertEquals("ZONE2+DIGESTION", res.reason)
    }
}
