package app.aaps.plugins.aps.openAPSAIMI.recursive

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2

internal object UnfoldExporter {

    fun toExport(
        snapshot: RecursiveBeliefSnapshot,
        shadowOnly: Boolean,
        authorityApplied: Boolean,
        waveletBands: WaveletBelief.Bands? = null,
        authorityGate: RecursiveBeliefAuthorityGate.Decision? = null,
    ): RecursiveBeliefExport =
        RecursiveBeliefExport(
            version = 1,
            shadowOnly = shadowOnly,
            authorityApplied = authorityApplied,
            authorityGate = authorityGate?.let {
                AuthorityGateExport(
                    requestedAuthority = it.requestedAuthority.name,
                    maxAllowedAuthority = it.maxAllowedAuthority.name,
                    effectiveAuthority = it.effectiveAuthority.name,
                    readinessScore = it.readinessScore,
                    liftBlend = it.liftBlend,
                    shadowOnly = it.shadowOnly,
                    softLimited = it.softLimited,
                    reasonCodes = it.reasonCodes,
                )
            },
            waveletBands = waveletBands?.let { WaveletExport(it.high, it.mid, it.low) },
            scales = snapshot.scales.map { scale ->
                ScaleExport(
                    tauMin = scale.horizonMinutes,
                    belief = scale.belief,
                    terminalMgdl = scale.terminalMgdl,
                    urgency = scale.urgency,
                    leaves = scale.leaves.map { leaf ->
                        LeafExport(
                            id = leaf.id.name,
                            signal = leaf.signal,
                            weight = leaf.weight,
                            credibility = leaf.credibility,
                            summary = leaf.rawSummary,
                        )
                    },
                )
            },
            tensions = snapshot.tensions.map { t ->
                TensionExport(
                    parentTau = t.parentTauMin,
                    childTau = t.childTauMin,
                    magnitude = t.magnitude,
                    dominant = t.dominantParadoxId?.name,
                )
            },
            paradoxes = snapshot.paradoxes.map { p ->
                ParadoxExport(
                    id = p.id.name,
                    suppressed = p.suppressed,
                    resolution = p.resolution,
                )
            },
            resolution = ResolutionExport(
                smbDemandU = snapshot.resolutions.smbDemandU,
                tbrDemandFraction = snapshot.resolutions.tbrDemandFraction,
                waitBias = snapshot.resolutions.waitBias,
                dominantScaleMin = snapshot.resolutions.dominantScaleMinutes,
                releaseAuthority = snapshot.resolutions.releaseAuthority.name,
                hypoGuardMode = snapshot.resolutions.hypoGuardMode.name,
                suppressTrajBasalShift = snapshot.resolutions.suppressTrajBasalShift,
                hypoMinPredIgnored = snapshot.resolutions.hypoMinPredIgnored,
                reasonCodes = snapshot.resolutions.reasonCodes,
                basalFirstChannel = snapshot.resolutions.basalFirstChannel.name,
                t3cBasalFirst = snapshot.resolutions.t3cBasalFirst?.let {
                    T3cBasalFirstExport(
                        active = it.active,
                        eligible = it.eligible,
                        basalDemandRateUph = it.basalDemandRateUph,
                        boundedRateUph = it.boundedRateUph,
                        maxBasalCapUph = it.maxBasalCapUph,
                        anticipationStrength = it.anticipationStrength,
                        mealConflict = it.mealConflict,
                        postHypoBlock = it.postHypoBlock,
                        exerciseBlock = it.exerciseBlock,
                        hardSafetyBlock = it.hardSafetyBlock,
                        dominantBlocker = it.dominantBlocker,
                        governanceBasalFloorUph = it.governanceBasalFloorUph,
                        governanceAggressivenessFloor = it.governanceAggressivenessFloor,
                        reasonCodes = it.reasonCodes,
                        selectedForProduction = it.selectedForProduction,
                        historicalBypassNeutralized = it.historicalBypassNeutralized,
                        appliedRateUph = it.appliedRateUph,
                        appliedDurationMin = it.appliedDurationMin,
                        runtimeBlocker = it.runtimeBlocker,
                    )
                },
                harmoniaBasalFirst = snapshot.resolutions.harmoniaBasalFirst?.let {
                    HarmoniaBasalFirstExport(
                        active = it.active,
                        eligible = it.eligible,
                        sourceAction = it.sourceAction,
                        branch = it.branch,
                        basalDemandRateUph = it.basalDemandRateUph,
                        boundedRateUph = it.boundedRateUph,
                        maxBasalCapUph = it.maxBasalCapUph,
                        mealConflict = it.mealConflict,
                        postHypoBlock = it.postHypoBlock,
                        exerciseBlock = it.exerciseBlock,
                        hardSafetyBlock = it.hardSafetyBlock,
                        dominantBlocker = it.dominantBlocker,
                        reasonCodes = it.reasonCodes,
                        selectedForProduction = it.selectedForProduction,
                        appliedRateUph = it.appliedRateUph,
                        appliedDurationMin = it.appliedDurationMin,
                        runtimeBlocker = it.runtimeBlocker,
                    )
                },
                harmoniaSmb = snapshot.resolutions.harmoniaSmb?.let {
                    HarmoniaSmbExport(
                        active = it.active,
                        eligible = it.eligible,
                        sourceAction = it.sourceAction,
                        branch = it.branch,
                        targetSmbU = it.targetSmbU,
                        boundedSmbU = it.boundedSmbU,
                        maxSmbCapU = it.maxSmbCapU,
                        demandBeforeU = it.demandBeforeU,
                        demandAfterU = it.demandAfterU,
                        mealConflict = it.mealConflict,
                        postHypoBlock = it.postHypoBlock,
                        exerciseBlock = it.exerciseBlock,
                        hardSafetyBlock = it.hardSafetyBlock,
                        dominantBlocker = it.dominantBlocker,
                        reasonCodes = it.reasonCodes,
                        appliedToRbtDemand = it.appliedToRbtDemand,
                        reducesRbtDemand = it.reducesRbtDemand,
                        authorityMode = it.authorityMode,
                        addsSmbAuthority = it.addsSmbAuthority,
                        insulinIntent = it.insulinIntent,
                    )
                },
            ),
            loadGovernor = snapshot.loadGovernor,
            mr7Trace = snapshot.mr7Trace,
        )

    fun toJsonObject(export: RecursiveBeliefExport): JsonObject = buildJsonObject {
        put("version", export.version)
        put("shadow_only", export.shadowOnly)
        put("authority_applied", export.authorityApplied)
        export.authorityGate?.let { gate ->
            put("authority_gate", buildJsonObject {
                put("requested_authority", gate.requestedAuthority)
                put("max_allowed_authority", gate.maxAllowedAuthority)
                put("effective_authority", gate.effectiveAuthority)
                put("readiness_score", gate.readinessScore)
                put("lift_blend", gate.liftBlend)
                put("shadow_only", gate.shadowOnly)
                put("soft_limited", gate.softLimited)
                put("reason_codes", JsonArray(gate.reasonCodes.map { JsonPrimitive(it) }))
            })
        }
        export.waveletBands?.let { bands ->
            put("wavelet_bands", buildJsonObject {
                put("high", bands.high)
                put("mid", bands.mid)
                put("low", bands.low)
            })
        }
        put("scales", JsonArray(export.scales.map { scale ->
            buildJsonObject {
                put("tau_min", scale.tauMin)
                put("belief", scale.belief)
                put("terminal_mgdl", scale.terminalMgdl)
                put("urgency", scale.urgency)
                put("leaves", JsonArray(scale.leaves.map { leaf ->
                    buildJsonObject {
                        put("id", leaf.id)
                        put("signal", leaf.signal)
                        put("weight", leaf.weight)
                        put("credibility", leaf.credibility)
                        put("summary", leaf.summary)
                    }
                }))
            }
        }))
        put("tensions", JsonArray(export.tensions.map { t ->
            buildJsonObject {
                put("parent_tau", t.parentTau)
                put("child_tau", t.childTau)
                put("magnitude", t.magnitude)
                put("dominant", t.dominant)
            }
        }))
        put("paradoxes", JsonArray(export.paradoxes.map { p ->
            buildJsonObject {
                put("id", p.id)
                put("suppressed", p.suppressed)
                put("resolution", p.resolution)
            }
        }))
        put("resolution", buildJsonObject {
            put("smb_demand_u", export.resolution.smbDemandU)
            put("tbr_demand_fraction", export.resolution.tbrDemandFraction)
            put("wait_bias", export.resolution.waitBias)
            put("dominant_scale_min", export.resolution.dominantScaleMin)
            put("release_authority", export.resolution.releaseAuthority)
            put("hypo_guard_mode", export.resolution.hypoGuardMode)
            put("suppress_traj_basal_shift", export.resolution.suppressTrajBasalShift)
            put("hypo_min_pred_ignored", export.resolution.hypoMinPredIgnored)
            put("reason_codes", JsonArray(export.resolution.reasonCodes.map { JsonPrimitive(it) }))
            put("basal_first_channel", export.resolution.basalFirstChannel)
            export.resolution.t3cBasalFirst?.let { t3c ->
                put("t3c_basal_first", buildJsonObject {
                    put("active", t3c.active)
                    put("eligible", t3c.eligible)
                    put("basal_demand_rate_uph", t3c.basalDemandRateUph)
                    put("bounded_rate_uph", t3c.boundedRateUph)
                    put("max_basal_cap_uph", t3c.maxBasalCapUph)
                    put("anticipation_strength", t3c.anticipationStrength)
                    put("meal_conflict", t3c.mealConflict)
                    put("post_hypo_block", t3c.postHypoBlock)
                    put("exercise_block", t3c.exerciseBlock)
                    put("hard_safety_block", t3c.hardSafetyBlock)
                    put("dominant_blocker", t3c.dominantBlocker)
                    put("governance_basal_floor_uph", t3c.governanceBasalFloorUph)
                    put("governance_aggressiveness_floor", t3c.governanceAggressivenessFloor)
                    put("reason_codes", JsonArray(t3c.reasonCodes.map { JsonPrimitive(it) }))
                    put("selected_for_production", t3c.selectedForProduction)
                    put("historical_bypass_neutralized", t3c.historicalBypassNeutralized)
                    put("applied_rate_uph", t3c.appliedRateUph)
                    put("applied_duration_min", t3c.appliedDurationMin)
                    put("runtime_blocker", t3c.runtimeBlocker)
                })
            }
            export.resolution.harmoniaBasalFirst?.let { harmonia ->
                put("harmonia_basal_first", buildJsonObject {
                    put("active", harmonia.active)
                    put("eligible", harmonia.eligible)
                    put("source_action", harmonia.sourceAction)
                    put("branch", harmonia.branch)
                    put("basal_demand_rate_uph", harmonia.basalDemandRateUph)
                    put("bounded_rate_uph", harmonia.boundedRateUph)
                    put("max_basal_cap_uph", harmonia.maxBasalCapUph)
                    put("meal_conflict", harmonia.mealConflict)
                    put("post_hypo_block", harmonia.postHypoBlock)
                    put("exercise_block", harmonia.exerciseBlock)
                    put("hard_safety_block", harmonia.hardSafetyBlock)
                    put("dominant_blocker", harmonia.dominantBlocker)
                    put("reason_codes", JsonArray(harmonia.reasonCodes.map { JsonPrimitive(it) }))
                    put("selected_for_production", harmonia.selectedForProduction)
                    put("applied_rate_uph", harmonia.appliedRateUph)
                    put("applied_duration_min", harmonia.appliedDurationMin)
                    put("runtime_blocker", harmonia.runtimeBlocker)
                })
            }
            export.resolution.harmoniaSmb?.let { harmonia ->
                put("harmonia_smb", buildJsonObject {
                    put("active", harmonia.active)
                    put("eligible", harmonia.eligible)
                    put("source_action", harmonia.sourceAction)
                    put("branch", harmonia.branch)
                    put("simulated_smb_u", harmonia.targetSmbU)
                    put("bounded_smb_u", harmonia.boundedSmbU)
                    put("max_smb_cap_u", harmonia.maxSmbCapU)
                    put("demand_before_u", harmonia.demandBeforeU)
                    put("demand_after_u", harmonia.demandAfterU)
                    put("meal_conflict", harmonia.mealConflict)
                    put("post_hypo_block", harmonia.postHypoBlock)
                    put("exercise_block", harmonia.exerciseBlock)
                    put("hard_safety_block", harmonia.hardSafetyBlock)
                    put("dominant_blocker", harmonia.dominantBlocker)
                    put("reason_codes", JsonArray(harmonia.reasonCodes.map { JsonPrimitive(it) }))
                    put("applied_to_rbt_demand", harmonia.appliedToRbtDemand)
                    put("reduces_rbt_demand", harmonia.reducesRbtDemand)
                    put("authority_mode", harmonia.authorityMode)
                    put("adds_smb_authority", harmonia.addsSmbAuthority)
                    put("insulin_intent", harmonia.insulinIntent)
                })
            }
        })
        export.loadGovernor?.let { lg ->
            put("load_governor", buildJsonObject {
                put("tier", lg.tier)
                put("multiplier_g", lg.multiplierG)
                put("raw_multiplier_g", lg.rawMultiplierG)
                put("smb_tick_cap_u", lg.smbTickCapU)
                put("phys_budget_u", lg.physBudgetU)
                put("stack_score", lg.stackScore)
                put("rise_score", lg.riseScore)
                put("delta_decel_score", lg.deltaDecelScore)
                put("smb_demand_before_u", lg.smbDemandBeforeU)
                put("smb_demand_after_u", lg.smbDemandAfterU)
                put("applied", lg.applied)
                put("reason_codes", JsonArray(lg.reasonCodes.map { JsonPrimitive(it) }))
                put("summary", lg.summary)
                put("tuning_reference", "InsulinLoadGovernor.kt; applied when RBT authority ON")
            })
        }
        put("mr7_trace", JsonArray(export.mr7Trace.map { JsonPrimitive(it) }))
    }

    fun formatLogLine(snapshot: RecursiveBeliefSnapshot): String {
        val r = snapshot.resolutions
        val lg = snapshot.loadGovernor
        val lgNote = lg?.let {
            " LG=${it.tier} g=${aimiFmt2(it.multiplierG)}" +
                if (it.applied) "✓" else "shadow"
        } ?: ""
        val t3cNote = r.t3cBasalFirst?.let { t3c ->
            when {
                t3c.selectedForProduction -> " bf=T3C_APPLIED@${aimiFmt2(t3c.appliedRateUph ?: t3c.boundedRateUph)}U/h"
                t3c.eligible -> " bf=T3C_READY@${aimiFmt2(t3c.boundedRateUph)}U/h"
                t3c.active -> " bf=T3C_BLOCK(${t3c.runtimeBlocker ?: t3c.dominantBlocker ?: "blocked"})"
                else -> ""
            }
        } ?: ""
        val harmoniaNote = r.harmoniaBasalFirst?.let { harmonia ->
            when {
                harmonia.selectedForProduction -> " bf=HARMONIA_APPLIED@${aimiFmt2(harmonia.appliedRateUph ?: harmonia.boundedRateUph)}U/h"
                harmonia.eligible -> " bf=HARMONIA_READY@${aimiFmt2(harmonia.boundedRateUph)}U/h"
                harmonia.active -> " bf=HARMONIA_BLOCK(${harmonia.runtimeBlocker ?: harmonia.dominantBlocker ?: "blocked"})"
                else -> ""
            }
        } ?: ""
        val harmoniaSmbNote = r.harmoniaSmb?.let { harmonia ->
            when {
                harmonia.appliedToRbtDemand -> " hsmb=${aimiFmt2(harmonia.demandBeforeU)}→${aimiFmt2(harmonia.demandAfterU)}U"
                harmonia.eligible -> " hsmb=READY@${aimiFmt2(harmonia.boundedSmbU)}U"
                harmonia.active -> " hsmb=BLOCK(${harmonia.dominantBlocker ?: "blocked"})"
                else -> ""
            }
        } ?: ""
        return "🌳 RBT: auth=${r.releaseAuthority} smb=${aimiFmt2(r.smbDemandU)}U " +
            "tbr×${aimiFmt2(r.tbrDemandFraction)} paradoxes=${snapshot.paradoxes.size} " +
            "τ*=${r.dominantScaleMinutes}$lgNote$t3cNote$harmoniaNote$harmoniaSmbNote"
    }
}
