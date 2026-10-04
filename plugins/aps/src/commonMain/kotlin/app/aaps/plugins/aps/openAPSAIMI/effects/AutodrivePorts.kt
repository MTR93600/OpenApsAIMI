package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.SourceSensor
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.aps.RT
import app.aaps.plugins.aps.openAPSAIMI.autodrive.safety.AutoDriveGater
import app.aaps.plugins.aps.openAPSAIMI.orchestration.AimiTickContext
import app.aaps.plugins.aps.openAPSAIMI.patient.PatientRefreshSource
import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioLatentState
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioMultipliersMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkPdRuntime
import app.aaps.plugins.aps.openAPSAIMI.quality.SmbBindingTrace
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityClassifier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryReleaseResult
import app.aaps.plugins.aps.openAPSAIMI.safety.MealSafetyContext

/** `autodriveGater.shouldEngageV3`. L'instance n'est lue qu'à cet appel. */
internal fun interface AimiAutodriveGater {
    fun shouldEngageV3(
        bg: Double,
        combinedDelta: Double,
        cob: Double,
        uamConfidence: Double,
        explicitMealMode: Boolean,
        hasRecentMealEstimate: Boolean,
        minBgLookback75m: Double,
        estimatedRa: Double,
        mealChannelHint: app.aaps.plugins.aps.openAPSAIMI.recursive.MealChannelHint?,
    ): AutoDriveGater.GatingResult
}

/** `continuousStateEstimator.getLastRa`. L'estimateur n'est lu qu'à cet appel. */
internal fun interface AimiEstimatedRa {
    fun getLastRa(): Double
}

/** `aapsLogger.debug` sur le tag APS, seulement dans la branche engagée. */
internal fun interface AimiAutodriveDebug {
    fun debug(message: String)
}

/** `AimiUamHandler.confidenceOrZero`, au moment où la référence le lit. */
internal fun interface AimiUamConfidence {
    fun confidenceOrZero(): Double
}

/** `fclDeclaredThisTick`. */
internal fun interface AimiFclDeclared {
    fun fclDeclaredThisTick(profile: OapsProfileAimi): Boolean
}

/** `minBgInLastMinutes`. */
internal fun interface AimiMinBgLookback {
    fun minBgInLastMinutes(lookbackMinutes: Int): Double
}

/** `postHypoRecoveryActive`. */
internal fun interface AimiPostHypoRecovery {
    fun postHypoRecoveryActive(): Boolean
}

/** `resolveTdd24hForExport`. Le calcul d'export reste dans la coquille. */
internal fun interface AimiTdd24h {
    fun resolveTdd24hForExport(): Double?
}

/** `observeRaIfNotAlreadyRun`. */
internal fun interface AimiRaObservation {
    fun observeRaIfNotAlreadyRun(
        ctx: AimiTickContext,
        combinedDelta: Float,
        shortAvgDeltaAdj: Float,
        pkpdRuntime: PkPdRuntime?,
        hasRecentMealEstimate: Boolean,
        reason: String,
    )
}

/** `refreshPhysiologicalPhase`. Retourne l'état que la référence relit juste après. */
internal fun interface AimiPhysiologicalPhase {
    fun refreshPhysiologicalPhase(
        rT: RT,
        combinedDelta: Float,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        basePhysioMultipliers: PhysioMultipliersMTR,
    ): PhysiologicalPhaseClassifier.Output?
}

/** `buildMealSafetyContext`. */
internal fun interface AimiMealSafety {
    fun buildMealSafetyContext(isExplicitAdvisorRun: Boolean, iobData: IobTotal): MealSafetyContext
}

/** `refreshMealAbsorptionPhase`. */
internal fun interface AimiMealAbsorption {
    fun refreshMealAbsorptionPhase(
        combinedDelta: Float,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        mealContext: MealSafetyContext,
        lastBolusTimeMs: Long?,
        nowMs: Long,
    )
}

/** `updatePhysioLatentState`. */
internal fun interface AimiPhysioLatentUpdate {
    fun updatePhysioLatentState(
        snapshot: HealthContextSnapshot,
        sourceSensor: SourceSensor?,
    ): PhysioLatentState
}

/** `classifyHyperSeverityForTick`. */
internal fun interface AimiHyperSeverity {
    fun classifyHyperSeverityForTick(
        rT: RT,
        combinedDelta: Float,
        tdd24hU: Double,
    ): HyperSeverityClassifier.Output
}

/** `resolveHtrScenarioTerminals`. */
internal fun interface AimiHtrTerminals {
    fun resolveHtrScenarioTerminals(rT: RT): Pair<Double, Double>
}

/** `capBasalRateForCorrectionAggression`. */
internal fun interface AimiBasalCap {
    fun capBasalRateForCorrectionAggression(
        requestedRateUph: Double,
        profileBasalUph: Double,
        source: String,
    ): Double
}

/** `aggressiveRiseSmbFloorU`. */
internal fun interface AimiAggressiveRiseFloor {
    fun aggressiveRiseSmbFloorU(bgMgdl: Double, riseSignal: Float, shortAvgDelta: Float): Double
}

/** `noteRiseFloorContribution`. */
internal fun interface AimiRiseFloorNote {
    fun noteRiseFloorContribution(contributedU: Double)
}

/** `refreshPatientStateRuntime`. */
internal fun interface AimiPatientStateRefresh {
    fun refreshPatientStateRuntime(
        nowMs: Long,
        healthSnapshot: HealthContextSnapshot,
        sourceSensor: SourceSensor?,
        refreshSource: PatientRefreshSource,
    )
}

/** `publishDoseTerminalAuthorityAndSnapshot`. */
internal fun interface AimiDoseTerminal {
    fun publishDoseTerminalAuthorityAndSnapshot(
        rT: RT,
        profile: OapsProfileAimi,
        mealData: MealData,
        pkpdEventualMgdl: Double,
        pkpdPredTerminalMgdl: Double,
        targetBgMgdl: Double,
        stageTag: String,
    )
}

/**
 * Ce que `resolveAndWireRbtLiveTick` rend à `deliverV3SmbFromRbt`.
 * Le câblage RBT lui-même reste dans la coquille.
 */
internal data class RbtLiveCommitResult(
    val baselineHtr: HyperTrajectoryReleaseResult,
    val effectiveHtr: HyperTrajectoryReleaseResult,
    val rbtAuthority: Boolean,
)

/** `resolveAndWireRbtLiveTick`. */
internal fun interface AimiRbtLive {
    fun resolveAndWireRbtLiveTick(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        combinedDelta: Float,
        tdd24hU: Double,
        v3SmbU: Double,
        stepsLast15m: Int,
        heartRateBpm: Int,
        autodriveGateOpen: Boolean,
        mpcFeedForwardRa: Double?,
        cbfShieldDeltaU: Double?,
    ): RbtLiveCommitResult?
}

/** `deliverV3SmbFromRbt`. */
internal fun interface AimiV3SmbDelivery {
    fun deliverV3SmbFromRbt(
        ctx: AimiTickContext,
        profile: OapsProfileAimi,
        rT: RT,
        hypoThresholdMgdl: Double,
        v3CommandSafe: Boolean,
        adCommandReason: String?,
        rbtCommit: RbtLiveCommitResult?,
    )
}

/** `markHtrRaFloorForExport`. L'export reste dans la coquille. */
internal fun interface AimiHtrExport {
    fun markHtrRaFloorForExport(floorMgdlPerMin: Double?, raUsedMgdlPerMin: Double)
}

/** `logDecisionFinal`. Les learners qu'il appelle restent dans la coquille. */
internal fun interface AimiDecisionLog {
    fun logDecisionFinal(tag: String, rT: RT, bg: Double?, delta: Float?)
}

/** Écritures d'état du tick, à la ligne où la référence les fait. */
internal interface AimiAutodriveTickWrites {
    fun setRaNetDeltas(combinedDelta: Float, shortAvgDeltaAdj: Float)
    fun noteAutodriveGate(engaged: Boolean, kindName: String, reason: String)
    fun setAutodriveEngaged()
    fun setLastHtrRaFloorMgdlPerMin(floor: Double?)
    fun setSmbBindingDraft(draft: SmbBindingTrace.Draft)
    fun setPostHypoSmbBeforeCapU(units: Double)
    fun setPostHypoSmbAfterCapU(units: Double)
}
