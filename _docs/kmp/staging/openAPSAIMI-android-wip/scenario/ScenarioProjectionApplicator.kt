package app.aaps.plugins.aps.openAPSAIMI.scenario

import app.aaps.core.interfaces.aps.Predictions
import app.aaps.core.interfaces.aps.RT

/**
 * Maps [ScenarioProjectionPair] onto loop [RT.predBGs] for UI and downstream consumers.
 *
 * Graph convention (Phase 1 proto):
 * - **IOB** → [ScenarioProjectionKind.CLINICAL_FLOOR] (pessimistic safety anchor)
 * - **UAM** → [ScenarioProjectionKind.SCENARIO_BEST] (authoritative scenario)
 * - **COB / ZT** → PKPD reference curves (informational)
 */
object ScenarioProjectionApplicator {

    fun applyToRt(
        rT: RT,
        projection: ScenarioProjectionPair,
    ) {
        rT.predBGs = Predictions().apply {
            IOB = projection.clinicalFloor.pointsMgdl
            UAM = projection.scenarioBest.pointsMgdl
            COB = projection.cobPointsMgdl
            ZT = projection.ztPointsMgdl
        }
        rT.eventualBG = projection.scenarioBest.terminalMgdl
    }
}
