package app.aaps.plugins.aps.openAPSAIMI.orchestration

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

object IntelligenceSnapshotJson {

    fun toJsonObject(snapshot: AimiIntelligenceSnapshot): JsonObject =
        buildJsonObject {
            put("version", snapshot.meta.version)
            put("build_tag", snapshot.meta.buildTag)
            put("timestamp_ms", snapshot.meta.timestampMs)
            put("causal", buildJsonObject {
                put("learning_quality", snapshot.causal.learningQuality)
                put("learning_gate_pass", snapshot.causal.learningGatePass)
                put("dominant", snapshot.causal.dominant)
                put("dominant_confidence", snapshot.causal.dominantConfidence)
                put("modulation_reason", snapshot.causal.causalModulationReason)
            })
            put("kinetics", buildJsonObject {
                put("structural_dia_h", snapshot.kinetics.structural.diaHrs)
                put("structural_peak_min", snapshot.kinetics.structural.peakMin)
                put("effective_dia_h", snapshot.kinetics.effective.diaHours)
                put("effective_peak_min", snapshot.kinetics.effective.peakMinutes)
                put("profile_dia_h", snapshot.kinetics.effective.profileDiaHours)
                put("profile_peak_min", snapshot.kinetics.effective.profilePeakMinutes)
                put("prediction_uses_learned_kinetics", snapshot.kinetics.predictionUsesLearnedKinetics)
                put("learning", buildJsonObject {
                    put("quality", snapshot.kinetics.learning.learningQuality)
                    put("gate_pass", snapshot.kinetics.learning.learningGatePass)
                    put("attempted", snapshot.kinetics.learning.learningAttempted)
                    put("dominant", snapshot.kinetics.learning.dominant)
                    put("skip_reason", snapshot.kinetics.learning.skipReason)
                })
                put("activity", buildJsonObject {
                    put("tail_fraction", snapshot.kinetics.activity.tailFraction)
                    put("stage", snapshot.kinetics.activity.stage)
                    put("relative_activity", snapshot.kinetics.activity.relativeActivity)
                })
                snapshot.kinetics.peakGovernor?.let { gov ->
                    put("peak_governor", buildJsonObject {
                        put("effective_peak_min", gov.effectivePeakMinutes)
                        put("dominant_branch", gov.dominantBranch)
                        put("applied", gov.appliedGovernor)
                        gov.logLine?.let { put("log", it) }
                    })
                }
                snapshot.kinetics.diaGovernor?.let { gov ->
                    put("dia_governor", buildJsonObject {
                        put("effective_dia_h", gov.effectiveDiaHours)
                        put("dominant_branch", gov.dominantBranch)
                        put("applied", gov.appliedGovernor)
                        gov.logLine?.let { put("log", it) }
                    })
                }
                snapshot.kinetics.learningTrace?.let { trace ->
                    put("dia_at_floor", trace.diaAtFloor)
                    put("dia_reg_pull_h", trace.diaRegPullH)
                    put("dia_learn_step_h", trace.diaLearnStepH)
                    put("iob_residual_120min", trace.iobResidual120Min)
                    // A Long and a String, so no non-finite value can reach org.json here.
                    // A flat counter means the two step values above were kept from an older tick.
                    put("dia_accepted_updates", trace.diaAcceptedUpdates)
                    put("dia_learn_blocked_by", trace.diaLearnBlockedBy)
                }
            })
            put("isf", buildJsonObject {
                put("fused_mgdl_per_u", snapshot.isf.fusedMgdlPerU)
                put("profile_mgdl_per_u", snapshot.isf.profileMgdlPerU)
                put("fusion_factor", snapshot.isf.fusionFactor)
                put("pkpd_scale", snapshot.isf.pkpdScale)
            })
            put("predictions", buildJsonObject {
                put("pred_terminal_mgdl", snapshot.predictions.predTerminalMgdl)
                put("eventual_terminal_mgdl", snapshot.predictions.eventualTerminalMgdl)
                snapshot.predictions.pkpdEventualMgdl?.let { put("pkpd_eventual_mgdl", it) }
                snapshot.predictions.scenarioFloorMgdl?.let { put("scenario_floor_mgdl", it) }
                snapshot.predictions.scenarioBestMgdl?.let { put("scenario_best_mgdl", it) }
                snapshot.predictions.source?.let { put("authority_source", it) }
                put("scenario_uplift_applied", snapshot.predictions.scenarioUpliftApplied)
                put("false_meal_suppression", snapshot.predictions.falseMealSuppression)
                snapshot.predictions.reason?.let { put("reason", it) }
            })
            put("smb_policy", buildJsonObject {
                put("tail_fraction", snapshot.smbPolicy.tailFraction)
                put("activity_stage", snapshot.smbPolicy.activityStage)
                put("tail_damping_enabled", snapshot.smbPolicy.tailDampingEnabled)
            })
        }
}
