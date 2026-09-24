package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.plugins.aps.openAPSAIMI.prediction.ClampPkpdScenarioReconcile
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthority
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DoseTerminalSnapshotTest {

    private fun authority(
        eventual: Double,
        predTerminal: Double = 100.0,
        pkpd: Double = 39.0,
        source: DecisionPredictionSource = DecisionPredictionSource.SCENARIO_MEAL_UPLIFT,
        uplift: Boolean = true,
    ) = DecisionPredictionAuthority(
        predTerminalMgdl = predTerminal,
        eventualTerminalMgdl = eventual,
        pkpdEventualMgdl = pkpd,
        scenarioFloorTerminalMgdl = predTerminal,
        scenarioBestTerminalMgdl = eventual,
        source = source,
        scenarioUpliftApplied = uplift,
        falseMealSuppression = false,
        reason = "test",
    )

    private fun applyResult(
        eventual: Double,
        predTerminal: Double = 100.0,
        source: String = "SCENARIO_MEAL_UPLIFT",
        applied: Boolean = true,
    ) = PredictionAuthorityApplyResult(
        applied = applied,
        shadowOnly = false,
        eventualMgdl = eventual,
        predTerminalMgdl = predTerminal,
        predBGsRemapped = true,
        shadowDeltaEventualMgdl = null,
        shadowDeltaPredTerminalMgdl = null,
        source = source,
        reason = "test",
    )

    private fun clampInput(pkpdEventual: Double = 39.0) = ClampPkpdScenarioReconcile.Input(
        bgMgdl = 145.0,
        targetBgMgdl = 100.0,
        deltaMgdl5m = 2.0,
        pkpdEventualMgdl = pkpdEventual,
        scenarioTerminalMgdl = 180.0,
        scenarioPathMinMgdl = 95.0,
        scenarioPathMinHitFloor = false,
        digestionOrMealActive = true,
        sportTime = false,
        postHypoDeliveryActive = false,
    )

    @Test
    fun authorityUplift_clampIsNoOp_snapshotKeepsAuthorityEventual() {
        val snap = DoseTerminalSnapshotBuilder.build(
            authority = authority(eventual = 170.0, predTerminal = 110.0),
            applyResult = applyResult(eventual = 170.0, predTerminal = 110.0),
            authorityEnabled = true,
            fallbackEventualMgdl = 39.0,
            fallbackMinPredMgdl = 39.0,
            clampInput = clampInput(pkpdEventual = 39.0),
        )
        assertEquals(170.0, snap.eventualMgdl, 0.001)
        assertEquals(110.0, snap.minPredMgdl, 0.001)
        assertTrue(snap.authorityApplied)
        assertFalse(snap.clampReconciled)
        assertEquals("SCENARIO_MEAL_UPLIFT", snap.source)
        assertTrue(snap.predBGsRemapped)
    }

    @Test
    fun authorityRetainsPkpdFloor_thinClampLiftsEventualAndMinPred() {
        val snap = DoseTerminalSnapshotBuilder.build(
            authority = authority(
                eventual = 39.0,
                predTerminal = 39.0,
                pkpd = 39.0,
                source = DecisionPredictionSource.PKPD_ONLY,
                uplift = false,
            ),
            applyResult = applyResult(
                eventual = 39.0,
                predTerminal = 39.0,
                source = "PKPD_ONLY",
            ),
            authorityEnabled = true,
            fallbackEventualMgdl = 39.0,
            fallbackMinPredMgdl = 39.0,
            clampInput = clampInput(pkpdEventual = 39.0),
        )
        assertTrue(snap.clampReconciled)
        assertTrue(snap.eventualMgdl > 39.5)
        // Safe scenario pathMin (95) lifts minPred so stacking is not floor-poisoned.
        assertEquals(95.0, snap.minPredMgdl, 0.001)
        assertTrue(snap.minPredMgdl <= snap.eventualMgdl)
        assertTrue(snap.source.contains("CLAMP_"))
    }

    @Test
    fun authorityMealUplift_withSafePathMin_liftsMinPredEvenWithoutClamp() {
        val snap = DoseTerminalSnapshotBuilder.build(
            authority = authority(eventual = 184.0, predTerminal = 39.0, pkpd = 39.0),
            applyResult = applyResult(eventual = 184.0, predTerminal = 39.0),
            authorityEnabled = true,
            fallbackEventualMgdl = 39.0,
            fallbackMinPredMgdl = 39.0,
            clampInput = clampInput(pkpdEventual = 39.0),
        )
        assertTrue(snap.authorityApplied)
        assertEquals(184.0, snap.eventualMgdl, 0.001)
        assertEquals(95.0, snap.minPredMgdl, 0.001)
    }

    @Test
    fun highFlatBg_floorArtefact_liftsMinPredWithoutScenario() {
        val snap = DoseTerminalSnapshotBuilder.build(
            authority = null,
            applyResult = null,
            authorityEnabled = false,
            fallbackEventualMgdl = 39.0,
            fallbackMinPredMgdl = 39.0,
            clampInput = ClampPkpdScenarioReconcile.Input(
                bgMgdl = 220.0,
                targetBgMgdl = 100.0,
                deltaMgdl5m = 0.5,
                pkpdEventualMgdl = 39.0,
                scenarioTerminalMgdl = null,
                scenarioPathMinMgdl = null,
                scenarioPathMinHitFloor = false,
                digestionOrMealActive = false,
                sportTime = false,
                postHypoDeliveryActive = false,
            ),
        )
        assertTrue(snap.plateauFloorLifted)
        assertTrue(snap.minPredMgdl >= ClampPkpdScenarioReconcile.SCN_PATHMIN_MGDL - 0.001)
        assertTrue(snap.minPredMgdl <= snap.eventualMgdl + 0.001)
        assertTrue(snap.source.contains("PLATEAU_FLOOR_LIFT"))
    }

    @Test
    fun fallingHard_doesNotPlateauLift() {
        val snap = DoseTerminalSnapshotBuilder.build(
            authority = null,
            applyResult = null,
            authorityEnabled = false,
            fallbackEventualMgdl = 39.0,
            fallbackMinPredMgdl = 39.0,
            clampInput = ClampPkpdScenarioReconcile.Input(
                bgMgdl = 220.0,
                targetBgMgdl = 100.0,
                deltaMgdl5m = -4.0,
                pkpdEventualMgdl = 39.0,
                scenarioTerminalMgdl = null,
                scenarioPathMinMgdl = null,
                scenarioPathMinHitFloor = false,
                digestionOrMealActive = false,
                sportTime = false,
                postHypoDeliveryActive = false,
            ),
        )
        assertFalse(snap.plateauFloorLifted)
        assertEquals(39.0, snap.minPredMgdl, 0.001)
    }

    @Test
    fun authorityDisabled_clampCanStillLiftRawPkpd() {
        val snap = DoseTerminalSnapshotBuilder.build(
            authority = null,
            applyResult = null,
            authorityEnabled = false,
            fallbackEventualMgdl = 39.0,
            fallbackMinPredMgdl = 40.0,
            clampInput = clampInput(),
        )
        assertFalse(snap.authorityApplied)
        assertTrue(snap.clampReconciled)
        assertTrue(snap.eventualMgdl > 39.5)
        // Clamp release also lifts minPred via safe scenario pathMin (95).
        assertEquals(95.0, snap.minPredMgdl, 0.001)
    }

    @Test
    fun jsonAndLogExposeCascadeMarkers() {
        val snap = DoseTerminalSnapshot(
            eventualMgdl = 160.0,
            minPredMgdl = 105.0,
            source = "SCENARIO_MEAL_UPLIFT",
            authorityApplied = true,
            clampReconciled = false,
            clampReason = null,
            predBGsRemapped = true,
        )
        val json = snap.toJsonObject()
        assertEquals(160.0, json.getDouble("eventual_mgdl"), 0.001)
        assertEquals(105.0, json.getDouble("min_pred_mgdl"), 0.001)
        assertTrue(DoseTerminalSnapshot.formatLogLine(snap).startsWith(DoseTerminalSnapshot.LOG_PREFIX))
    }
}
