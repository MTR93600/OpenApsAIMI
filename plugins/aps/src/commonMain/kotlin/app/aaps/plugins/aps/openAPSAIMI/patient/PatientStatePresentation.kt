package app.aaps.plugins.aps.openAPSAIMI.patient

import app.aaps.plugins.aps.openAPSAIMI.aimiFmtSigned1
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalBeliefDigest
import app.aaps.plugins.aps.openAPSAIMI.physio.thermal.ThermalHypothesis
import kotlin.math.roundToInt

data class PatientSignalGauge(
    val label: String,
    val percent: Int,
)

data class PatientStatePresentation(
    val updatedSummary: String,
    val modeHeadline: String,
    val narrative: String,
    val physiologySummary: String,
    val physioLiveSummary: String,
    val thermalSummary: String,
    val intentSummary: String,
    val signalSummary: String,
    val signalGauges: List<PatientSignalGauge>,
    val deliverySummary: String,
    val reasonSummary: String,
)

internal object PatientStatePresentationBuilder {

    fun build(snapshot: PatientRuntimeSnapshot, nowMs: Long): PatientStatePresentation {
        val patientState = snapshot.patientState
        val patientMode = snapshot.patientModeDecision
        val modeLabel = humanize(patientMode.mode.name)
        val strategyLabel = humanize(patientMode.strategyHint.name)
        return PatientStatePresentation(
            updatedSummary = buildUpdatedSummary(snapshot.updatedAtMs, nowMs, snapshot.refreshSource),
            modeHeadline = "$modeLabel (${percent(patientMode.confidence)})",
            narrative = buildNarrative(patientMode),
            physiologySummary = buildPhysiologySummary(patientState, snapshot.physiologicalTree, snapshot.harmoniaDecision),
            physioLiveSummary = buildPhysioLiveSummary(snapshot.physioLive),
            thermalSummary = buildThermalSummary(snapshot.thermalBelief),
            intentSummary = buildIntentSummary(patientState),
            signalSummary = buildSignalSummary(patientState),
            signalGauges = buildSignalGauges(patientState, snapshot.thermalBelief),
            deliverySummary = "$strategyLabel · meal bias ${percent(patientMode.mealBias)} · protection ${percent(patientMode.protectionBias)}",
            reasonSummary = patientMode.reasonCodes.joinToString(", ") { humanizeReason(it) },
        )
    }

    private fun buildUpdatedSummary(updatedAtMs: Long, nowMs: Long, refreshSource: PatientRefreshSource): String {
        val ageMinutes = ((nowMs - updatedAtMs).coerceAtLeast(0L)) / 60_000L
        val ageLabel = when {
            ageMinutes <= 0L -> "Updated just now"
            ageMinutes == 1L -> "Updated 1 minute ago"
            ageMinutes < 60L -> "Updated ${ageMinutes} minutes ago"
            else -> {
                val hours = ageMinutes / 60L
                val minutes = ageMinutes % 60L
                if (minutes == 0L) {
                    "Updated ${hours}h ago"
                } else {
                    "Updated ${hours}h ${minutes}m ago"
                }
            }
        }
        val sourceSuffix = when (refreshSource) {
            PatientRefreshSource.LOOP_TICK -> ""
            PatientRefreshSource.PHYSIO_SIGNAL -> " · live body signals"
            PatientRefreshSource.CONTEXT_INTENT -> " · user context"
        }
        return ageLabel + sourceSuffix
    }

    private fun buildNarrative(decision: PatientModeOrchestrator.Decision): String =
        when (decision.strategyHint) {
            PatientStrategyHint.BASELINE_BALANCE ->
                "AIMI sees a balanced baseline and keeps a steady insulin posture."
            PatientStrategyHint.SMB_PRIORITY ->
                "AIMI sees a fast meal-like rise and keeps SMB support available."
            PatientStrategyHint.MEAL_SUPPORT ->
                "AIMI sees a prolonged meal pattern and favors sustained meal support."
            PatientStrategyHint.BASAL_BRIDGE ->
                "AIMI sees more endogenous drive than meal evidence and favors basal bridging."
            PatientStrategyHint.CONSERVATIVE_OBSERVE ->
                "AIMI sees a protective context and limits escalation while reassessing the body state."
            PatientStrategyHint.HYPO_RECOVERY ->
                "AIMI is protecting recovery after a low or alcohol-related rebound risk."
            PatientStrategyHint.PKPD_REASSESS ->
                "AIMI has low absorption confidence and reassesses PKPD before escalating."
        }

    private fun buildPhysiologySummary(
        state: PatientStateSnapshot,
        physiologicalTree: PhysiologicalTreeSnapshot?,
        harmoniaDecision: HarmoniaDecision?,
    ): String {
        val base = "Phase ${humanize(state.phase.name)} · Absorption ${humanize(state.mealAbsorptionPhase.name)} · " +
            "UAM ${humanize(state.uamDominant.name)} · Cause ${humanize(state.causalPosterior.dominant.name)}"
        return listOfNotNull(
            base,
            physiologicalTree?.compactSummary,
            harmoniaDecision?.compactSummary,
        ).joinToString("\n")
    }

    private fun buildPhysioLiveSummary(digest: PhysioLiveDigest): String {
        if (digest.stepsLast15m == 0 && digest.hrNowBpm == 0 && digest.hrAvg15mBpm == 0) {
            return "Body signals pending · waiting for steps or heart-rate data"
        }
        val activity = humanize(digest.activityState)
        val hrLabel = when {
            digest.hrNowBpm > 0 -> "${digest.hrNowBpm} bpm"
            digest.hrAvg15mBpm > 0 -> "${digest.hrAvg15mBpm} bpm avg"
            else -> "HR n/a"
        }
        val sleepLabel = if (digest.sleepDebtMinutes > 0) {
            " · sleep debt ${digest.sleepDebtMinutes} min"
        } else {
            ""
        }
        return "$activity · ${digest.stepsLast15m} steps/15m · $hrLabel$sleepLabel"
    }

    private fun buildIntentSummary(state: PatientStateSnapshot): String {
        if (!state.userIntent.hasAnyIntent()) {
            return "No active user intent"
        }
        val countLabel = if (state.userIntent.intentCount == 1) "context" else "contexts"
        return "Dominant ${humanize(state.userIntent.dominantIntent)} · ${percent(state.userIntent.avgConfidence)} confidence across ${state.userIntent.intentCount} ${countLabel}"
    }

    private fun buildThermalSummary(thermal: ThermalBeliefDigest): String {
        if (!thermal.hasUsableData()) {
            return thermal.narrative.ifBlank {
                "Thermal rhythm pending · sync sleep and RHR via Health Connect, or add an Oura API token"
            }
        }
        val deltaLabel = aimiFmtSigned1(thermal.deltaVsBaselineC)
        val wCycleLabel = thermal.wCycleHint?.let { hint ->
            " · ${humanizeWCycleHint(hint)}"
        } ?: ""
        return "${thermal.narrative} ($deltaLabel°C vs baseline$wCycleLabel)"
    }

    private fun humanizeWCycleHint(hint: String): String =
        when (hint) {
            "LUTEAL_BBT_RISE" -> "luteal BBT pattern"
            "OVULATION_THERMAL_SHIFT" -> "ovulation thermal shift"
            "MENSTRUAL_THERMAL_DIP" -> "menstrual thermal dip"
            else -> humanize(hint)
        }

    private fun buildSignalSummary(state: PatientStateSnapshot): String =
        "Meal ${percent(state.mealProb)} · Endogenous ${percent(state.endogenousGlucoseDrive)} · " +
        "Resistance ${percent(state.transientResistanceProb)} · Thermal ${percent(state.thermalInflammationIndex)} · " +
            "Protect ${percent(state.causalPosterior.protectiveConfidence)} · Sensor ${percent(state.sensorConfidence)}"

    private fun buildSignalGauges(
        state: PatientStateSnapshot,
        thermal: ThermalBeliefDigest,
    ): List<PatientSignalGauge> {
        val thermalPercent = when (thermal.hypothesis) {
            ThermalHypothesis.RECOVERY_COOLING,
            ThermalHypothesis.HYPO_SYMPATHETIC_COOLING,
            ThermalHypothesis.FATIGUE_DYSREGULATION,
            -> gaugePercent(state.thermalRecoveryBurden)
            else -> gaugePercent(state.thermalInflammationIndex)
        }
        return listOf(
            PatientSignalGauge("Meal", gaugePercent(state.mealProb)),
            PatientSignalGauge("Endogenous", gaugePercent(state.endogenousGlucoseDrive)),
            PatientSignalGauge("Resistance", gaugePercent(state.transientResistanceProb)),
            PatientSignalGauge("Thermal", thermalPercent),
            PatientSignalGauge("Sensor", gaugePercent(state.sensorConfidence)),
        )
    }

    private fun gaugePercent(value: Double): Int =
        (value.coerceIn(0.0, 1.0) * 100.0).roundToInt()

    private fun humanize(value: String): String =
        value.lowercase()
            .split('_')
            .joinToString(" ") { token -> token.replaceFirstChar { char -> char.titlecase() } }

    private fun humanizeReason(code: String): String =
        when (code) {
            "CAUSAL_POST_HYPO" -> "Causal post-hypo recovery"
            "CAUSAL_FAST_MEAL" -> "Causal fast meal"
            "CAUSAL_PROLONGED_MEAL" -> "Causal prolonged meal"
            "CAUSAL_DAWN_ENDOGENOUS" -> "Causal dawn endogenous drive"
            "CAUSAL_EXERCISE_AFTERBURN" -> "Causal exercise afterburn"
            "CAUSAL_INFLAMMATORY_DRIFT" -> "Causal inflammatory drift"
            "CAUSAL_STRESS_RESISTANCE" -> "Causal stress resistance"
            "CAUSAL_ABSORPTION_UNCERTAIN" -> "Causal absorption uncertainty"
            "LATENT_POST_HYPO" -> "Latent post-hypo rebound"
            "UAM_POST_HYPO" -> "UAM post-hypo signal"
            "CTX_ALCOHOL" -> "Alcohol context"
            "MEAL_FIRST_WAVE" -> "Meal first wave"
            "MEAL_PEAK_CORRECTION" -> "Meal peak correction"
            "UAM_MEAL" -> "UAM meal hypothesis"
            "CTX_MEAL_RISK" -> "Meal-risk context"
            "MEAL_EXTENDED_PHASE" -> "Extended meal phase"
            "UAM_LATE_FAT" -> "Late-fat meal hypothesis"
            "LATENT_ENDOGENOUS" -> "Endogenous glucose drive"
            "FALSE_MEAL_SUPPRESS" -> "False meal suppression"
            "CTX_ACTIVITY" -> "Activity context"
            "LATENT_RESISTANCE" -> "Transient resistance"
            "CTX_STRESS" -> "Stress context"
            "CTX_ILLNESS" -> "Illness context"
            "UAM_STRESS" -> "UAM stress hypothesis"
            "LATENT_SLEEP_DEBT" -> "Sleep debt"
            "SENSOR_LOW" -> "Low sensor confidence"
            "BASELINE" -> "Balanced baseline"
            "THERMAL_INFLAMMATORY_DRIFT" -> "Thermal inflammatory drift"
            "THERMAL_CYCLE_BBT" -> "Cycle basal temperature rise"
            "THERMAL_RECOVERY_COOLING" -> "Thermal recovery cooling"
            else -> humanize(code)
        }

    private fun percent(value: Double): String = "${gaugePercent(value)}%"
}
