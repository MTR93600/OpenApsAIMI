package app.aaps.plugins.aps.openAPSAIMI.physio.pattern

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

import app.aaps.plugins.aps.openAPSAIMI.context.ContextIntent
import app.aaps.plugins.aps.openAPSAIMI.context.ContextSnapshot
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhaseEngine
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysioContextMTR
import app.aaps.plugins.aps.openAPSAIMI.physio.PhysiologicalPhaseClassifier
import app.aaps.plugins.aps.openAPSAIMI.release.HyperTrajectoryHypoCredibility
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance

object PhysiologicalPatternInputBuilder {

    fun build(
        bgMgdl: Double,
        targetBgMgdl: Double,
        highBgPreferenceMgdl: Double,
        deltaMgdlPer5: Double,
        shortAvgDeltaMgdlPer5: Double,
        combinedDeltaMgdlPer5: Double,
        mealCobG: Double,
        hourOfDay: Int,
        stepsLast15m: Int,
        heartRateBpm: Int,
        restingHeartRateBpm: Int,
        iobU: Double,
        maxIobU: Double,
        bestTerminalMgdl: Double,
        floorTerminalMgdl: Double,
        phaseOutput: PhysiologicalPhaseClassifier.Output?,
        physioContext: PhysioContextMTR?,
        sleepDebtMinutes: Int,
        sleepEfficiency: Double,
        mealAbsorption: MealAbsorptionPhaseEngine.Output?,
        stackingEval: InsulinStackingStance.Evaluation?,
        endogenousCounterRegulatory: Boolean,
        postHypoOrdinal: Int?,
        exerciseLockout: Boolean,
        sportTime: Boolean,
        sleepTime: Boolean,
        contextSnapshot: ContextSnapshot?,
        compressionImpossibleRise: Boolean,
        dwellAboveHighBgMinutes: Int,
        trajectoryRelevanceScore: Double,
        nowMs: Long,
        /** User's high-BG SMB ceiling; catalogue caps are fractions of it. */
        maxSmbHbU: Double = LEGACY_REFERENCE_MAX_SMB_HB_U,
    ): PhysiologicalPatternInput {
        val highBand = HyperTrajectoryHypoCredibility.highBgBandMgdl(targetBgMgdl, highBgPreferenceMgdl)
        var illness = false
        var stress = false
        var activity = false
        contextSnapshot?.activeIntents?.forEach { intent ->
            when (intent) {
                is ContextIntent.Illness -> illness = true
                is ContextIntent.Stress -> stress = true
                is ContextIntent.Activity -> activity = true
                else -> Unit
            }
        }
        return PhysiologicalPatternInput(
            bgMgdl = bgMgdl,
            targetBgMgdl = targetBgMgdl,
            highBgBandMgdl = highBand,
            deltaMgdlPer5 = deltaMgdlPer5,
            shortAvgDeltaMgdlPer5 = shortAvgDeltaMgdlPer5,
            combinedDeltaMgdlPer5 = combinedDeltaMgdlPer5,
            mealCobG = mealCobG,
            hourOfDay = hourOfDay,
            stepsLast15m = stepsLast15m,
            heartRateBpm = heartRateBpm,
            restingHeartRateBpm = restingHeartRateBpm,
            iobU = iobU,
            maxIobU = maxIobU,
            bestTerminalMgdl = bestTerminalMgdl,
            floorTerminalMgdl = floorTerminalMgdl,
            phaseOutput = phaseOutput,
            physioContext = physioContext,
            sleepDebtMinutes = sleepDebtMinutes,
            sleepEfficiency = sleepEfficiency,
            mealAbsorptionPhase = mealAbsorption?.phase ?: MealAbsorptionPhase.NONE,
            mealDeliveryPriority = mealAbsorption?.mealDeliveryPriority == true,
            stackingSurveillance = stackingEval?.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB,
            endogenousCounterRegulatory = endogenousCounterRegulatory,
            postHypoOrdinal = postHypoOrdinal,
            exerciseLockout = exerciseLockout,
            sportTime = sportTime,
            sleepTime = sleepTime,
            contextIllness = illness,
            contextStress = stress,
            contextActivity = activity,
            compressionImpossibleRise = compressionImpossibleRise,
            dwellAboveHighBgMinutes = dwellAboveHighBgMinutes,
            trajectoryRelevanceScore = trajectoryRelevanceScore,
            nowMs = nowMs,
            maxSmbHbU = maxSmbHbU,
        )
    }
}

object PhysiologicalPatternExport {

    fun toJsonObject(snapshot: PhysiologicalPatternSnapshot): JsonObject =
        buildJsonObject {
            put("dominant", snapshot.dominant?.name)
            put("dominant_confidence", snapshot.dominantConfidence)
            put("suppress_meal", snapshot.suppressMealInterpretation)
            put("suppress_hyper_release", snapshot.suppressHyperRelease)
            put("suppress_wavelet", snapshot.suppressWaveletBoost)
            snapshot.smbCapU?.let { put("smb_cap_u", it) }
            snapshot.smbCapKind?.let { put("smb_cap_kind", it.name) }
            snapshot.mealPatternCap?.let { meal ->
                put(
                    "meal_pattern_cap",
                    buildJsonObject {
                        put("proposed_cap_u", meal.proposedCapU)
                        put("kind", meal.kind.name)
                        put("source_id", meal.sourceId?.name)
                    },
                )
            }
            put("max_smb_hb_u", snapshot.maxSmbHbU)
            snapshot.hardBindingCapU()?.let { put("hard_binding_cap_u", it) }
            snapshot.softProposedCapU()?.let { put("soft_proposed_cap_u", it) }
            put("summary", snapshot.reasonSummary)
            put("active", JsonArray(snapshot.active.map { reading ->
                val def = PhysiologicalPatternCatalog.definitionOf(reading.id)
                buildJsonObject {
                    put("id", reading.id.name)
                    put("category", PhysiologicalPatternCatalog.categoryOf(reading.id).name)
                    put("confidence", reading.confidence)
                    put("reason", reading.reason)
                    put("dominant_scale_min", def.dominantScaleMinutes)
                    put("cap_kind", def.capKind.name)
                    def.smbCapFraction?.let { put("smb_cap_fraction", it) }
                    def.capU(snapshot.maxSmbHbU)?.let { put("smb_cap_u", it) }
                }
            }))
        }
}
