package app.aaps.plugins.aps.openAPSAIMI.recursive

import app.aaps.plugins.aps.openAPSAIMI.aimiFmt1
import app.aaps.plugins.aps.openAPSAIMI.aimiFmt2
import app.aaps.plugins.aps.openAPSAIMI.physio.MealAbsorptionPhase
import app.aaps.plugins.aps.openAPSAIMI.physio.SleepLiveDetector
import app.aaps.plugins.aps.openAPSAIMI.release.HyperSeverityTier
import app.aaps.plugins.aps.openAPSAIMI.safety.InsulinStackingStance
import app.aaps.plugins.aps.openAPSAIMI.scenario.ScenarioContributorId
import app.aaps.plugins.aps.openAPSAIMI.trajectory.TrajectoryType
import kotlin.math.max

/**
 * Full §15 adapter registry — one [BeliefLeafAdapter] entry per [BeliefLeafId].
 */
object BeliefLeafAdapterRegistry {

    private const val EXPORT_MIN_CRED = 0.05

    private val adapters: List<BeliefLeafAdapter> = buildList {
        for (id in BeliefLeafId.entries) {
            add(object : BeliefLeafAdapter {
                override val id: BeliefLeafId = id
                override val scales: Set<Int> = scalesFor(id)
                override fun read(ctx: RecursiveBeliefTickContext): BeliefLeafReading? =
                    readLeaf(id, ctx)
            })
        }
    }

    fun collect(tauMin: Int, ctx: RecursiveBeliefTickContext, includeShadow: Boolean): List<BeliefLeafReading> {
        val out = mutableListOf<BeliefLeafReading>()
        for (adapter in adapters) {
            if (tauMin !in adapter.scales) continue
            if (!includeShadow && adapter.id in BeliefLeafId.SHADOW) continue
            adapter.read(ctx)?.let { reading ->
                if (out.none { it.id == reading.id }) out += reading
            }
        }
        if (ctx.waveletBands != null && tauMin in setOf(15, 60, 180)) {
            WaveletBeliefLeafAdapters.read(tauMin, ctx)?.let { w ->
                if (out.none { it.id == w.id }) out += applyPatternScale(ctx, w)
            }
        }
        return out.map { applyPatternScale(ctx, it) }.filter { it.credibility >= EXPORT_MIN_CRED }
    }

    private fun applyPatternScale(ctx: RecursiveBeliefTickContext, reading: BeliefLeafReading): BeliefLeafReading {
        val patterns = ctx.physiologicalPatterns ?: return reading
        return reading.copy(
            credibility = (reading.credibility * patterns.credibilityScaleFor(reading.id)).coerceIn(0.0, 1.0),
            weight = reading.weight * patterns.weightScaleFor(reading.id),
        )
    }

    private fun scalesFor(id: BeliefLeafId): Set<Int> = when (id) {
        in BeliefLeafId.MICRO -> setOf(15)
        in BeliefLeafId.MESO -> setOf(60)
        in BeliefLeafId.MACRO -> setOf(180)
        in BeliefLeafId.META -> setOf(480)
        in BeliefLeafId.SHADOW -> setOf(60)
        else -> emptySet()
    }

    @Suppress("CyclomaticComplexMethod")
    internal fun readLeaf(id: BeliefLeafId, ctx: RecursiveBeliefTickContext): BeliefLeafReading? {
        val ext = ctx.extended
        return when (id) {
            // τ = 15
            BeliefLeafId.DELTA_NOW -> leaf(id, ctx.deltaMgdlPer5, 1.2, 1.0, "Δ=${fmt1(ctx.deltaMgdlPer5)}")
            BeliefLeafId.DELTA_SHORT -> leaf(id, ctx.shortAvgDeltaMgdlPer5, 1.0, 0.95, "short=${fmt1(ctx.shortAvgDeltaMgdlPer5)}")
            BeliefLeafId.DELTA_COMBINED -> leaf(id, ctx.combinedDeltaMgdlPer5, 1.0, 0.9, "comb=${fmt1(ctx.combinedDeltaMgdlPer5)}")
            BeliefLeafId.ACCEL -> ctx.mealAbsorption?.let {
                leaf(id, it.deltaMgdlPer5 - (it.gapMgdl / 10.0), 0.8, 0.7, "phase Δ")
            }
            BeliefLeafId.BG_DERIV -> ctx.bgDerivShort?.let {
                leaf(id, it, 0.85, 0.7, "dBG/dt=${fmt2(it)}")
            }
            BeliefLeafId.INSULIN_STAGE -> (ext.insulinActivityStageOrdinal ?: ctx.insulinActivityStageOrdinal)?.let {
                leaf(id, it.toDouble(), 0.8, 0.75, "stage=$it")
            }
            BeliefLeafId.PKPD_TAIL -> (ext.pkpdTailFactor ?: ext.pkpdTailDamping)?.let {
                leaf(id, it, 0.9, 0.8, "tail=${fmt2(it)}")
            }
            BeliefLeafId.STACK_SURV -> ctx.stackingStance?.let { s ->
                leaf(id, s.smbMultiplier, 1.0, stackCred(s), s.kind.name)
            }
            BeliefLeafId.STACK_SIGNALS -> ctx.stackingStance?.let { s ->
                val drop = ext.stackingEventualDrop ?: s.tbrBoostFloor
                leaf(id, drop, 0.8, 0.85, "stackSig=${fmt2(drop)}")
            }
            BeliefLeafId.HYPO_GUARD -> leaf(
                id,
                if (ctx.hypoMinPredIgnored) 1.0 else 0.0,
                0.9,
                0.9,
                "minPredIgnored=${ctx.hypoMinPredIgnored}",
            )
            BeliefLeafId.COMPRESSION -> leaf(
                id,
                if (ext.compressionImpossibleRise) 1.0 else 0.0,
                0.85,
                if (ext.compressionImpossibleRise) 0.9 else 0.5,
                "compression=${ext.compressionImpossibleRise}",
            )
            BeliefLeafId.MEAL_PHASE -> ctx.mealAbsorption?.let {
                leaf(id, it.belief, 1.3, it.belief.coerceIn(0.15, 1.0), "${it.phase.name} B=${fmt2(it.belief)}")
            }
            BeliefLeafId.MEAL_AGGR -> ctx.mealAbsorption?.let {
                leaf(id, it.kineticScore, 0.9, it.belief.coerceIn(0.15, 1.0), "kinetic=${fmt2(it.kineticScore)}")
            }
            BeliefLeafId.LOCAL_SENTINEL -> {
                val conf = ext.shadowAuditorConfidence ?: ctx.shadowAuditorConfidence
                conf?.let { leaf(id, 1.0 - it, 0.9, 0.7, "sentinel conf=${fmt2(it)}") }
            }
            BeliefLeafId.DECISION_MOD -> ext.decisionModulationFactor?.let {
                leaf(id, it, 0.7, 0.65, "decMod=${fmt2(it)}")
            }
            BeliefLeafId.HIGH_BG_OVR -> leaf(
                id,
                if (ext.highBgOverrideActive) 1.0 else 0.0,
                0.8,
                if (ext.highBgOverrideActive) 0.85 else 0.4,
                "highBgOvr=${ext.highBgOverrideActive}",
            )
            BeliefLeafId.TUBE_ADVISOR -> (ext.tubeAdvisorCapScale ?: ctx.tubeAdvisorCapScale)?.let {
                leaf(id, it, 0.7, 0.65, "tubeCap=${fmt2(it)}")
            }
            BeliefLeafId.IOB_CONSENSUS -> {
                val delta = ext.iobConsensusDelta ?: 0.0
                leaf(id, ctx.iobU + delta, 0.8, if (ext.iobConsensusDelta != null) 0.85 else 0.6, "consensus=${fmt2(ctx.iobU + delta)}U")
            }
            BeliefLeafId.REALTIME_IOB -> {
                val units = ext.realtimeIobUnits ?: ctx.iobU
                leaf(id, units / ctx.maxIobU.coerceAtLeast(0.1), 0.7, 0.8, "rtIOB ratio")
            }
            BeliefLeafId.STEPS_15M -> if (ctx.stepsLast15m > 0) {
                leaf(id, ctx.stepsLast15m.toDouble(), 0.5, 0.6, "steps=${ctx.stepsLast15m}")
            } else null
            BeliefLeafId.ACTIVITY_INT -> if (ctx.heartRateBpm > 0) {
                leaf(id, ctx.heartRateBpm.toDouble(), 0.5, 0.55, "hr=${ctx.heartRateBpm}")
            } else null
            BeliefLeafId.UAM_CONF -> leaf(id, ctx.uamConfidence, 0.9, ctx.uamConfidence.coerceIn(0.15, 1.0), "UAM conf")

            // τ = 60
            BeliefLeafId.PKPD_IOB -> leaf(id, ctx.scenario.clinicalFloor.terminalMgdl, 1.0, 1.0, "floorT=${ctx.scenario.clinicalFloor.terminalMgdl.toInt()}")
            BeliefLeafId.PKPD_BEST -> leaf(id, ctx.scenario.scenarioBest.terminalMgdl, 1.2, mealCred(ctx), "bestT=${ctx.scenario.scenarioBest.terminalMgdl.toInt()}")
            BeliefLeafId.PKPD_UAM -> leaf(id, ctx.curves.uam.lastOrNull() ?: ctx.bgMgdl, 1.0, ctx.uamConfidence.coerceIn(0.15, 1.0), "UAM terminal")
            BeliefLeafId.PKPD_COB -> leaf(id, ctx.curves.cob.lastOrNull() ?: ctx.bgMgdl, 0.8, if (ctx.curves.cob.lastOrNull() != null) 0.75 else 0.2, "COB path")
            BeliefLeafId.PKPD_ZT -> ctx.curves.zt.lastOrNull()?.let { leaf(id, it, 0.75, 0.7, "ZT path") }
            BeliefLeafId.PKPD_HYBRID -> leaf(id, ctx.curves.hybrid.lastOrNull() ?: ctx.bgMgdl, 0.9, 0.85, "hybrid")
            BeliefLeafId.SCEN_TRAJ_RISE,
            BeliefLeafId.SCEN_TRAJ_SPIRAL,
            BeliefLeafId.SCEN_TRAJ_CONV,
            BeliefLeafId.SCEN_MEAL_CTX,
            BeliefLeafId.SCEN_ADVISOR_COB,
            BeliefLeafId.SCEN_ACTIVITY,
            BeliefLeafId.SCEN_PHYSIO,
            BeliefLeafId.SCEN_PHASE_CAP,
            BeliefLeafId.SCEN_CONTEXT,
            BeliefLeafId.SCEN_TARGET_BLEND,
            -> contributorLeaf(id, ctx)
            BeliefLeafId.TRAJ_GEOM -> ctx.trajectoryAnalysis?.let { t ->
                leaf(id, urgencyFromTrajectory(t.classification, t.metrics.energyBalance), 1.1, ctx.trajectoryRelevanceScore.coerceIn(0.15, 1.0), t.classification.name)
            }
            BeliefLeafId.TRAJ_MOD -> ctx.trajectoryAnalysis?.modulation?.let { mod ->
                leaf(id, mod.smbDamping, 0.9, 0.85, "smbDamp=${fmt2(mod.smbDamping)}")
            }
            BeliefLeafId.TRAJ_BRIDGE -> if (ctx.trajBridgePending) {
                leaf(id, 0.7, 1.0, 0.8, "Traj-Bridge pending")
            } else null
            BeliefLeafId.TRAJ_SPIRAL_CAP -> ext.trajSpiralCapMaxSmb?.let {
                leaf(id, it, 0.85, 0.75, "spiralCap=${fmt2(it)}U")
            }
            BeliefLeafId.COSINE_GATE -> leaf(id, ctx.trajectoryRelevanceScore, 0.85, 0.8, "cosGate=${fmt2(ctx.trajectoryRelevanceScore)}")
            BeliefLeafId.HTR_TIER -> ctx.htrClassification?.let { c ->
                leaf(id, c.tier.ordinal.toDouble(), 1.0, if (c.tier.isReleaseEligible) 1.0 else 0.3, c.tier.name)
            }
            BeliefLeafId.HTR_RELEASE -> {
                val floor = ext.htrLeafSmbFloorU ?: ctx.htrResult?.smbFloorU
                floor?.let { leaf(id, it, 1.2, if ((ctx.htrResult?.active == true) || ctx.replaceHtrRelease) 1.0 else 0.4, ctx.htrResult?.reason?.take(80) ?: "RBT floor") }
            }
            BeliefLeafId.HTC_HYPO -> leaf(id, if (ctx.hypoMinPredIgnored) 1.0 else 0.0, 0.9, 0.9, "minPredIgnored=${ctx.hypoMinPredIgnored}")
            BeliefLeafId.RISK_ENVELOPE -> ctx.riskEnvelope?.let { leaf(id, it.compositeMinMgdl, 0.9, 0.8, "envelope min") }
            BeliefLeafId.SAFETY_TERMINALS -> ctx.safetyTerminals?.let { s ->
                leaf(id, s.compositeMinMgdl, 1.0, if (ctx.hypoMinPredIgnored) 0.2 else 0.85, "compositeMin=${s.compositeMinMgdl.toInt()}")
            }
            BeliefLeafId.MPC_IMPLIED -> ctx.v3SmbU?.let { leaf(id, it, 1.0, 0.85, "V3 optimal ${fmt2(it)}U") }
            BeliefLeafId.MPC_FEEDFWD -> (ext.mpcFeedForwardRa ?: ctx.v3SmbU?.let { it * 0.85 })?.let {
                leaf(id, it, 0.8, 0.8, "feedfwdRa=${fmt2(it)}")
            }
            BeliefLeafId.AUTODRIVE_GATE -> ctx.autodriveV3GateOpen?.let {
                leaf(id, if (it) 1.0 else 0.0, 0.9, 0.85, "V3 gate open=$it")
            }
            BeliefLeafId.CBF_SHIELD -> ext.cbfShieldDeltaU?.let {
                leaf(id, it, 0.85, 0.75, "cbfΔ=${fmt2(it)}U")
            }
            BeliefLeafId.T3C_ACTIVE -> if (ext.t3cActive) {
                leaf(id, 1.0, 0.35, 0.14, "t3c active")
            } else null
            BeliefLeafId.T3C_BASAL_DEMAND -> ext.t3cBasalDemandRateUph?.let { demand ->
                val cap = (ext.t3cBasalMaxRateUph ?: demand.coerceAtLeast(0.1)).coerceAtLeast(0.1)
                leaf(id, (demand / cap).coerceIn(0.0, 1.0), 0.40, 0.14, "rate=${fmt2(demand)} cap=${fmt2(cap)}")
            }
            BeliefLeafId.MEAL_MEMORY -> ctx.mealAbsorption?.let { m ->
                leaf(id, m.waveCount.toDouble(), 0.8, if (m.memoryActive) 0.9 else 0.3, "waves=${m.waveCount}")
            }
            BeliefLeafId.ENDOG_BRIDGE -> if (ctx.endogenousCounterRegulatory) {
                leaf(id, 1.0, 0.9, 0.85, "endog counter-reg")
            } else null
            BeliefLeafId.T3C_ANTICIP -> ext.t3cAnticipationStrength?.let {
                leaf(id, it, 0.8, 0.7, "t3c=${fmt2(it)}")
            }
            BeliefLeafId.T3C_POST_HYPO_BLOCK -> if (ext.t3cPostHypoBlock) {
                leaf(id, 1.0, 0.35, 0.14, "postHypo=${ext.postHypoOrdinal ?: 1}")
            } else null
            BeliefLeafId.T3C_MEAL_CONFLICT -> if (ext.t3cMealConflict) {
                leaf(id, 1.0, 0.35, 0.14, "meal conflict")
            } else null
            BeliefLeafId.T3C_GOVERNANCE_FLOOR -> ext.t3cGovernanceBasalFloorUph?.let { floor ->
                val cap = (ext.t3cBasalMaxRateUph ?: floor.coerceAtLeast(0.1)).coerceAtLeast(0.1)
                leaf(id, (floor / cap).coerceIn(0.0, 1.0), 0.35, 0.14, "govFloor=${fmt2(floor)}")
            }
            BeliefLeafId.HARMONIA_ACTIVE -> if (ext.harmoniaActive) {
                leaf(id, 1.0, 0.45, 0.18, "harmonia active")
            } else null
            BeliefLeafId.HARMONIA_BASAL_DEMAND -> ext.harmoniaBasalDemandRateUph?.let { demand ->
                val cap = (ext.harmoniaBasalMaxRateUph ?: demand.coerceAtLeast(0.1)).coerceAtLeast(0.1)
                leaf(id, (demand / cap).coerceIn(0.0, 1.0), 0.45, 0.18, "rate=${fmt2(demand)} cap=${fmt2(cap)}")
            }
            BeliefLeafId.HARMONIA_MEAL_SUPPORT -> if (ext.harmoniaAction == "MEAL_SUPPORT") {
                leaf(id, 1.0, 0.40, 0.18, "meal support basal-only")
            } else null
            BeliefLeafId.HARMONIA_PROTECTIVE_REDUCTION -> if (ext.harmoniaAction == "PROTECTIVE_REDUCTION") {
                leaf(id, 1.0, 0.40, 0.18, "protective reduction")
            } else null
            BeliefLeafId.HARMONIA_SAFETY_BLOCK -> if (
                ext.harmoniaPostHypoBlock ||
                ext.harmoniaExerciseBlock ||
                ext.harmoniaMealConflict ||
                ext.harmoniaHardSafetyBlock
            ) {
                leaf(id, 1.0, 0.45, 0.18, ext.harmoniaBlockReason ?: "harmonia blocked")
            } else null
            BeliefLeafId.POST_HYPO -> ext.postHypoOrdinal?.let {
                leaf(id, it.toDouble(), 0.85, 0.75, "postHypo=$it")
            }
            BeliefLeafId.MEAL_ADVISOR -> ext.mealAdvisorEstimateU?.let {
                leaf(id, it, 0.75, 0.7, "advisor=${fmt2(it)}U")
            }

            // τ = 180
            BeliefLeafId.MPC_HORIZON -> leaf(id, ctx.curves.iob.lastOrNull() ?: ctx.bgMgdl, 1.0, 0.85, "IOB path @180")
            BeliefLeafId.PKPD_LEARNED -> if (ext.pkpdLearnedDiaH != null || ext.pkpdLearnedPeakMin != null) {
                leaf(id, ext.pkpdLearnedPeakMin ?: ext.pkpdLearnedDiaH ?: 0.0, 0.8, 0.75, "DIA=${fmt2(ext.pkpdLearnedDiaH ?: 0.0)} peak=${fmt1(ext.pkpdLearnedPeakMin ?: 0.0)}")
            } else null
            BeliefLeafId.ISF_FUSION -> ext.isfFusionRatio?.let { leaf(id, it, 0.85, 0.75, "isfFusion=${fmt2(it)}") }
            BeliefLeafId.KALMAN_ISF -> ext.kalmanIsf?.let { leaf(id, it, 0.8, ext.kalmanIsfTrust ?: 0.7, "kalmanISF=${fmt1(it)}") }
            BeliefLeafId.DYN_ISF_TRAJ -> ext.dynIsfTrajFactor?.let { leaf(id, it, 0.75, 0.65, "dynISF=${fmt2(it)}") }
            BeliefLeafId.PKPD_TAIL_DAMP -> (ext.pkpdTailDamping ?: ext.pkpdTailFactor)?.let {
                leaf(id, it, 0.85, 0.75, "tailDamp=${fmt2(it)}")
            }
            BeliefLeafId.PEAK_BIAS -> ext.peakBiasMinutes?.let { leaf(id, it.toDouble(), 0.7, 0.65, "peakBias=${it}m") }
            BeliefLeafId.PEAK_MISMATCH -> ext.peakMismatchScore?.let { leaf(id, it, 0.75, 0.65, "peakMismatch=${fmt2(it)}") }
            BeliefLeafId.CORR_AGGRESS -> (ext.correctionAggressionLevel ?: ctx.correctionAggressionLevel)?.let {
                leaf(id, it, 1.0, 0.8, "corrAgg=${fmt2(it)}")
            }
            BeliefLeafId.PHYSIO_FUSION -> ctx.physioMultipliers?.let {
                leaf(id, it.reactivityFactor, 0.9, it.confidence.coerceIn(0.15, 1.0), "react×${fmt2(it.reactivityFactor)}")
            }
            BeliefLeafId.PHYSIO_MULT -> ctx.physioMultipliers?.let {
                leaf(id, it.smbFactor, 1.0, it.confidence.coerceIn(0.15, 1.0), "smb×${fmt2(it.smbFactor)}")
            }
            BeliefLeafId.ENDOG_DETECT -> if (ctx.endogenousCounterRegulatory) leaf(id, 1.0, 1.0, 0.9, "endog detect") else null
            BeliefLeafId.HORMONAL_CAP -> leaf(id, if (ext.hormonalCapApplied) 1.0 else 0.0, 0.8, 0.7, "hormCap=${ext.hormonalCapApplied}")
            BeliefLeafId.BEHAVIORAL -> ctx.behavioralRisk?.let { leaf(id, it.maxHtrTier.ordinal.toDouble(), 1.0, 0.85, it.phase.name) }
            BeliefLeafId.NGR -> (ext.ngrSmbMult ?: ctx.ngrSmbMult)?.let { leaf(id, it, 0.9, 0.8, "NGR smb×${fmt2(it)}") }
            BeliefLeafId.INFLAMMATION -> ext.inflammationSmbMult?.let { leaf(id, it, 0.8, 0.7, "inflam smb×${fmt2(it)}") }
            BeliefLeafId.BASAL_ADAPT -> ext.basalAdaptMult?.let { leaf(id, it, 0.75, 0.7, "basalAdapt×${fmt2(it)}") }
            BeliefLeafId.DYN_BASAL -> ext.dynBasalFactor?.let { leaf(id, it, 0.7, 0.65, "dynBasal×${fmt2(it)}") }

            // τ = 480
            BeliefLeafId.PHYSIO_PHASE -> ctx.physioPhase?.let { leaf(id, it.confidence, 1.0, it.confidence.coerceIn(0.15, 1.0), it.phase.name) }
            BeliefLeafId.CHRONO_PRIOR -> ctx.mealAbsorption?.let { leaf(id, it.chronoPrior, 0.8, 0.75, "π=${fmt2(it.chronoPrior)}") }
            BeliefLeafId.CTX_INTENTS -> if (ctx.contextActivityActive) {
                leaf(id, ctx.contextSmbFactor.toDouble(), 0.9, 0.7, "context smb×${ctx.contextSmbFactor}")
            } else null
            BeliefLeafId.CTX_MANAGER -> if (ext.ctxManagerIntentCount > 0) {
                leaf(id, ext.ctxManagerIntentCount.toDouble(), 0.75, 0.65, "intents=${ext.ctxManagerIntentCount}")
            } else null
            BeliefLeafId.WCYCLE -> (ctx.wCycleBasalMult ?: ctx.wCycleSmbMult)?.let {
                leaf(id, it, 0.8, 0.7, "WCycle×${fmt2(it)}")
            }
            BeliefLeafId.ENDOMETRIOSIS -> ext.endometriosisFactor?.let { leaf(id, it, 0.75, 0.65, "endo×${fmt2(it)}") }
            BeliefLeafId.THYROID -> ext.thyroidIsfMult?.let { leaf(id, it, 0.8, 0.7, "thyroid isf×${fmt2(it)}") }
            BeliefLeafId.THYROID_GUARD -> leaf(id, if (ext.thyroidGuardActive) 1.0 else 0.0, 0.85, 0.75, "thyroidGuard=${ext.thyroidGuardActive}")
            BeliefLeafId.GESTATION -> ext.gestationBasalMult?.let { leaf(id, it, 0.75, 0.65, "gest×${fmt2(it)}") }
            BeliefLeafId.BASAL_LEARNER -> ext.basalLearnerShortMult?.let {
                leaf(id, it, 0.7, 0.65, "learner s/m/l=${fmt2(it)}/${fmt2(ext.basalLearnerMedMult ?: 1.0)}/${fmt2(ext.basalLearnerLongMult ?: 1.0)}")
            }
            BeliefLeafId.REACTIVITY -> ctx.physioMultipliers?.let {
                leaf(id, it.reactivityFactor, 0.7, it.confidence.coerceIn(0.15, 1.0), "reactivity")
            }
            BeliefLeafId.ONLINE_LEARN -> ext.onlineLearnFactor?.let { leaf(id, it, 0.65, 0.6, "online×${fmt2(it)}") }
            BeliefLeafId.ATTENTION -> ext.attentionGateScore?.let { leaf(id, it, 0.7, 0.65, "attention=${fmt2(it)}") }
            BeliefLeafId.EXERCISE_LOCK -> if (ctx.exerciseLockout) leaf(id, 1.0, 1.0, 1.0, "exercise lockout") else null
            BeliefLeafId.SLEEP_QUALITY -> ctx.physioContext?.features?.sleepQualityScore?.takeIf { it > 0.0 }?.let {
                leaf(id, it, 0.9, ctx.physioContext.confidence.coerceIn(0.15, 1.0), "sleepQ=${fmt2(it)}")
            }
            BeliefLeafId.HRV_DEVIATION -> ctx.physioContext?.takeIf { it.features != null }?.let { pc ->
                val z = pc.hrvDeviationZ
                leaf(id, z, 0.85, pc.confidence.coerceIn(0.15, 1.0), "hrvZ=${fmt2(z)}")
            }
            BeliefLeafId.SLEEP_DEBT -> ctx.extended.sleepDebtMinutes?.takeIf { it > 0.0 }?.let {
                leaf(id, it, 0.85, 0.75, "sleepDebt=${it.toInt()}m")
            } ?: ctx.physioContext?.takeIf { it.poorSleepDetected }?.let {
                leaf(id, 60.0, 0.8, it.confidence.coerceIn(0.15, 1.0), "poorSleep")
            }
            BeliefLeafId.SLEEP_LIVE -> {
                val conf = when {
                    ctx.asleepLiveConfidence >= SleepLiveDetector.ASLEEP_THRESHOLD -> ctx.asleepLiveConfidence
                    ctx.extended.sleepLiveConfidence != null -> ctx.extended.sleepLiveConfidence
                    else -> null
                }
                conf?.takeIf { it >= SleepLiveDetector.ASLEEP_THRESHOLD }?.let { asleepConf ->
                    val src = ctx.extended.sleepLiveSource ?: "UNKNOWN"
                    leaf(id, asleepConf, 0.92, 0.85, "asleep conf=${fmt2(asleepConf)} src=$src")
                }
            }
            BeliefLeafId.PHYSIO_MTR_STATE -> ctx.physioContext?.let { pc ->
                leaf(id, pc.state.ordinal.toDouble(), 1.0, pc.confidence.coerceIn(0.15, 1.0), pc.state.name)
            }
            BeliefLeafId.PATTERN_RISK -> ctx.physiologicalPatterns?.takeIf { it.active.isNotEmpty() }?.let { snap ->
                val risk = when {
                    snap.suppressHyperRelease -> 0.85
                    snap.suppressMealInterpretation -> 0.65
                    else -> 0.35
                }
                leaf(id, risk, 1.0, snap.dominantConfidence.coerceIn(0.15, 1.0), snap.reasonSummary.take(80))
            }

            // Shadow @ τ = 60
            BeliefLeafId.SHADOW_COMPARATOR -> ext.shadowComparatorDeltaU?.let {
                leaf(id, it, 0.5, 0.5, "comparator Δ=${fmt2(it)}U")
            }
            BeliefLeafId.SHADOW_VIRTUAL_BG -> ext.shadowVirtualBgMgdl?.let {
                leaf(id, it, 0.45, 0.45, "virtualBG=${fmt1(it)}")
            }
            BeliefLeafId.SHADOW_AUDITOR -> (ext.shadowAuditorConfidence ?: ctx.shadowAuditorConfidence)?.let {
                leaf(id, it, 0.6, 0.6, "auditor conf=${fmt2(it)}")
            }
            BeliefLeafId.SHADOW_SENTINEL_VERDICT -> ext.shadowSentinelVerdictLabel?.let {
                leaf(id, 1.0, 0.55, 0.55, it.take(60))
            }
            BeliefLeafId.SHADOW_ORCH -> if (ext.shadowOrchestratorActive || ctx.shadowOrchestratorActive) {
                leaf(id, 1.0, 0.3, 0.3, "shadow orchestrator")
            } else null
            BeliefLeafId.SHADOW_TUNING -> (ext.tuningContextLabel ?: ctx.tuningContextLabel)?.let {
                leaf(id, 1.0, 0.4, 0.4, "tuning=$it")
            }
            BeliefLeafId.SHADOW_VISION -> if (ext.shadowVisionTriggered) {
                leaf(id, 1.0, 0.25, 0.25, "vision triggered")
            } else null
            BeliefLeafId.SHADOW_ML_TRAIN -> leaf(id, if (ext.shadowMlTrainActive) 1.0 else 0.0, 0.1, 0.1, "mlTrain=${ext.shadowMlTrainActive}")
        }
    }

    private fun contributorLeaf(id: BeliefLeafId, ctx: RecursiveBeliefTickContext): BeliefLeafReading? {
        val contributorId = when (id) {
            BeliefLeafId.SCEN_TRAJ_RISE -> ScenarioContributorId.TRAJECTORY_RISE
            BeliefLeafId.SCEN_TRAJ_SPIRAL -> ScenarioContributorId.TRAJECTORY_SPIRAL_DAMP
            BeliefLeafId.SCEN_TRAJ_CONV -> ScenarioContributorId.TRAJECTORY_CONVERGENCE
            BeliefLeafId.SCEN_MEAL_CTX -> ScenarioContributorId.MEAL_CONTEXT
            BeliefLeafId.SCEN_ADVISOR_COB -> ScenarioContributorId.MEAL_ADVISOR_COB
            BeliefLeafId.SCEN_ACTIVITY -> ScenarioContributorId.ACTIVITY_PROTECTION
            BeliefLeafId.SCEN_PHYSIO -> ScenarioContributorId.PHYSIO_REACTIVITY
            BeliefLeafId.SCEN_PHASE_CAP -> ScenarioContributorId.PHYSIOLOGICAL_PHASE
            BeliefLeafId.SCEN_CONTEXT -> ScenarioContributorId.CONTEXT_MODULE
            BeliefLeafId.SCEN_TARGET_BLEND -> ScenarioContributorId.TARGET_BLEND
            else -> return null
        }
        val c = ctx.scenario.contributors.firstOrNull { it.id == contributorId } ?: return null
        return leaf(id, c.terminalDeltaMgdl ?: 0.0, 0.7, 0.75, c.summary.take(60))
    }

    private fun mealCred(ctx: RecursiveBeliefTickContext): Double =
        (ctx.mealAbsorption?.belief ?: 0.5).coerceIn(0.15, 1.0)

    private fun stackCred(s: InsulinStackingStance.Evaluation): Double =
        if (s.kind == InsulinStackingStance.Kind.SURVEILLANCE_IOB) 1.0 else 0.85

    private fun leaf(id: BeliefLeafId, signal: Double, weight: Double, credibility: Double, summary: String) =
        BeliefLeafReading(id, signal, weight, credibility.coerceIn(0.0, 1.0), summary)

    private fun fmt1(v: Double) = aimiFmt1(v)
    private fun fmt2(v: Double) = aimiFmt2(v)

    private fun urgencyFromTrajectory(type: TrajectoryType, energy: Double): Double =
        when (type) {
            TrajectoryType.OPEN_DIVERGING,
            TrajectoryType.SLOW_DRIFT -> 1.5 + energy * 0.05
            TrajectoryType.TIGHT_SPIRAL -> 0.8
            TrajectoryType.CLOSING_CONVERGING -> -0.5
            TrajectoryType.STABLE_ORBIT,
            TrajectoryType.HOVERING -> 0.0
            TrajectoryType.UNCERTAIN -> 0.3
        }

    fun pointAt(curves: List<Double>, tauMin: Int, bgNow: Double): Double {
        val step = 5
        val index = max(0, tauMin / step).coerceAtMost((curves.size - 1).coerceAtLeast(0))
        return curves.getOrElse(index) { bgNow }
    }
}

private object WaveletBeliefLeafAdapters {
    fun read(tauMin: Int, ctx: RecursiveBeliefTickContext): BeliefLeafReading? {
        val bands = ctx.waveletBands ?: return null
        val physioGated = ctx.behavioralRisk?.capsHtrRelease() == true ||
            ctx.physiologicalPatterns?.suppressWaveletBoost == true
        val credScale = if (physioGated) 0.2 else 1.0
        val weightScale = if (physioGated) 0.35 else 1.0
        val tag = if (physioGated) "wavelet-gated" else "wavelet"
        return when (tauMin) {
            15 -> BeliefLeafReading(
                BeliefLeafId.BG_DERIV,
                bands.high,
                0.9 * weightScale,
                0.75 * credScale,
                "$tag-H=${aimiFmt2(bands.high)}",
            )
            60 -> BeliefLeafReading(
                BeliefLeafId.TRAJ_GEOM,
                bands.mid,
                0.85 * weightScale,
                0.7 * credScale,
                "$tag-M=${aimiFmt2(bands.mid)}",
            )
            180 -> BeliefLeafReading(
                BeliefLeafId.MPC_HORIZON,
                bands.low,
                0.8 * weightScale,
                0.65 * credScale,
                "$tag-L=${aimiFmt2(bands.low)}",
            )
            else -> null
        }
    }
}
