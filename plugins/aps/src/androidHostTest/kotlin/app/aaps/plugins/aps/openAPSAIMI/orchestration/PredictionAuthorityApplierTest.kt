package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionAuthority
import app.aaps.plugins.aps.openAPSAIMI.risk.DecisionPredictionSource
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionCurve
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionKind
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioProjectionPair
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PredictionAuthorityApplierTest {

    @Test
    fun applyEnabledRemapsEventualAndCurves() {
        val rT = RT(runningDynamicIsf = false)
        rT.predBGs = Predictions().apply { IOB = listOf(39, 40) }
        val authority = DecisionPredictionAuthority(
            predTerminalMgdl = 85.0,
            eventualTerminalMgdl = 150.0,
            pkpdEventualMgdl = 39.0,
            scenarioFloorTerminalMgdl = 85.0,
            scenarioBestTerminalMgdl = 150.0,
            source = DecisionPredictionSource.SCENARIO_CONSENSUS,
            scenarioUpliftApplied = true,
            falseMealSuppression = false,
            reason = "test",
        )
        val projection = ScenarioProjectionPair(
            clinicalFloor = ScenarioProjectionCurve.fromRawPoints(
                ScenarioProjectionKind.CLINICAL_FLOOR,
                listOf(90.0, 85.0),
            ),
            scenarioBest = ScenarioProjectionCurve.fromRawPoints(
                ScenarioProjectionKind.SCENARIO_BEST,
                listOf(120.0, 150.0),
            ),
            cobPointsMgdl = listOf(100),
            ztPointsMgdl = listOf(95),
            contributors = emptyList(),
        )
        val result = PredictionAuthorityApplier.apply(
            rT = rT,
            authority = authority,
            scenarioProjection = projection,
            enabled = true,
            shadowOnly = false,
            pkpdEventualBeforeApply = 39.0,
            pkpdPredTerminalBeforeApply = 39.0,
        )
        assertTrue(result.applied)
        assertEquals(150.0, result.eventualMgdl, 0.01)
        assertEquals(150.0, rT.eventualBG!!, 0.01)
        assertTrue(result.predBGsRemapped)
        assertEquals(85, rT.predBGs!!.IOB!!.last())
        assertEquals(150, rT.predBGs!!.UAM!!.last())
    }

    @Test
    fun failSafeRejectsOutOfRangeAuthorityEventual() {
        val rT = RT(runningDynamicIsf = false)
        rT.eventualBG = 39.0
        val projection = ScenarioProjectionPair(
            clinicalFloor = ScenarioProjectionCurve.fromRawPoints(ScenarioProjectionKind.CLINICAL_FLOOR, listOf(90.0, 85.0)),
            scenarioBest = ScenarioProjectionCurve.fromRawPoints(ScenarioProjectionKind.SCENARIO_BEST, listOf(120.0, 150.0)),
            cobPointsMgdl = listOf(100),
            ztPointsMgdl = listOf(95),
            contributors = emptyList(),
        )
        val authority = DecisionPredictionAuthority(
            predTerminalMgdl = 85.0,
            eventualTerminalMgdl = 999.0, // outside [39, 401] → must be rejected
            pkpdEventualMgdl = 120.0,
            scenarioFloorTerminalMgdl = 85.0,
            scenarioBestTerminalMgdl = 150.0,
            source = DecisionPredictionSource.SCENARIO_CONSENSUS,
            scenarioUpliftApplied = true,
            falseMealSuppression = false,
            reason = "test",
        )
        val result = PredictionAuthorityApplier.apply(
            rT = rT,
            authority = authority,
            scenarioProjection = projection,
            enabled = true,
            shadowOnly = false,
            pkpdEventualBeforeApply = 120.0,
            pkpdPredTerminalBeforeApply = 110.0,
        )
        // Fail-safe: the loop keeps the raw PKPD prediction, rT is untouched, curves are not remapped.
        assertFalse(result.applied)
        assertEquals(120.0, result.eventualMgdl, 0.01)
        assertEquals(39.0, rT.eventualBG!!, 0.01)
        assertFalse(result.predBGsRemapped)
    }

    @Test
    fun shadowDoesNotMutateRt() {
        val rT = RT(runningDynamicIsf = false)
        rT.eventualBG = 39.0
        val authority = DecisionPredictionAuthority(
            predTerminalMgdl = 80.0,
            eventualTerminalMgdl = 120.0,
            pkpdEventualMgdl = 39.0,
            scenarioFloorTerminalMgdl = null,
            scenarioBestTerminalMgdl = null,
            source = DecisionPredictionSource.PKPD_ONLY,
            scenarioUpliftApplied = false,
            falseMealSuppression = false,
            reason = "test",
        )
        val result = PredictionAuthorityApplier.apply(
            rT = rT,
            authority = authority,
            scenarioProjection = null,
            enabled = false,
            shadowOnly = true,
            pkpdEventualBeforeApply = 39.0,
            pkpdPredTerminalBeforeApply = 39.0,
        )
        assertEquals(false, result.applied)
        assertEquals(39.0, rT.eventualBG!!, 0.01)
        assertEquals(81.0, result.shadowDeltaEventualMgdl!!, 0.01)
    }
}
