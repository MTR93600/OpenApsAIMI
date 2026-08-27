package app.aaps.plugins.aps.openAPSAIMI.wcycle

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Full endocrine belief for the tick — Lot A context for tree / Harmonia / JSONL.
 *
 * Does **not** own pump doses in Lot A. [legacyDoseBasalAmp] mirrors what [WCycleAdjuster]
 * still applies on the dose path; [effectiveBasalAmp] is hypo-dampened for posture/export.
 */
data class WCycleBelief(
    val enabled: Boolean,
    val phase: CyclePhase,
    val dayInCycle: Int,
    val trackingMode: CycleTrackingMode,
    val contraceptive: ContraceptiveType,
    val thyroid: ThyroidStatus,
    val verneuil: VerneuilStatus,
    val applicationMode: EndocrineApplicationMode,
    val ampContraceptive: Double,
    val ampTrackingMode: Double,
    val ampCombined: Double,
    val dawnBias: Double,
    val intendedBasalAmp: Double,
    val intendedSmbAmp: Double,
    val intendedIcAmp: Double,
    val hypoLoad: Double,
    val hypoLoadDampen: Double,
    val hypoGuardActive: Boolean,
    val inflamSharedBudgetHint: Double,
    val effectiveBasalAmp: Double,
    val effectiveSmbAmp: Double,
    val effectiveIcAmp: Double,
    val legacyDoseBasalAmp: Double,
    val legacyDoseSmbAmp: Double,
    val legacyDoseIcAmp: Double,
    val dosePathOwner: EndocrineDosePathOwner,
    val confidence: Double,
    val reasons: List<String>,
) {
    fun toJsonObject(): JsonObject =
        buildJsonObject {
            put("version", 1)
            put("enabled", enabled)
            put("phase", phase.name)
            put("day_in_cycle", dayInCycle)
            put("tracking_mode", trackingMode.name)
            put("contraceptive", contraceptive.name)
            put("thyroid", thyroid.name)
            put("verneuil", verneuil.name)
            put("application_mode", applicationMode.name)
            put("amp_contraceptive", ampContraceptive)
            put("amp_tracking_mode", ampTrackingMode)
            put("amp_combined", ampCombined)
            put("dawn_bias", dawnBias)
            put("intended_basal_amp", intendedBasalAmp)
            put("intended_smb_amp", intendedSmbAmp)
            put("intended_ic_amp", intendedIcAmp)
            put("hypo_load", hypoLoad)
            put("hypo_load_dampen", hypoLoadDampen)
            put("hypo_guard_active", hypoGuardActive)
            put("inflam_shared_budget_hint", inflamSharedBudgetHint)
            put("effective_basal_amp", effectiveBasalAmp)
            put("effective_smb_amp", effectiveSmbAmp)
            put("effective_ic_amp", effectiveIcAmp)
            put("legacy_dose_basal_amp", legacyDoseBasalAmp)
            put("legacy_dose_smb_amp", legacyDoseSmbAmp)
            put("legacy_dose_ic_amp", legacyDoseIcAmp)
            put("dose_path_owner", dosePathOwner.name)
            put("confidence", confidence)
            put("reasons", JsonArray(reasons.map { JsonPrimitive(it) }))
        }

    companion object {
        val DISABLED: WCycleBelief =
            WCycleBelief(
                enabled = false,
                phase = CyclePhase.UNKNOWN,
                dayInCycle = 0,
                trackingMode = CycleTrackingMode.FIXED_28,
                contraceptive = ContraceptiveType.NONE,
                thyroid = ThyroidStatus.EUTHYROID,
                verneuil = VerneuilStatus.NONE,
                applicationMode = EndocrineApplicationMode.DISABLED,
                ampContraceptive = 1.0,
                ampTrackingMode = 1.0,
                ampCombined = 1.0,
                dawnBias = 1.0,
                intendedBasalAmp = 1.0,
                intendedSmbAmp = 1.0,
                intendedIcAmp = 1.0,
                hypoLoad = 0.0,
                hypoLoadDampen = 1.0,
                hypoGuardActive = false,
                inflamSharedBudgetHint = 1.0,
                effectiveBasalAmp = 1.0,
                effectiveSmbAmp = 1.0,
                effectiveIcAmp = 1.0,
                legacyDoseBasalAmp = 1.0,
                legacyDoseSmbAmp = 1.0,
                legacyDoseIcAmp = 1.0,
                dosePathOwner = EndocrineDosePathOwner.PRODUCTION_GOVERNOR_DIRECT,
                confidence = 0.0,
                reasons = listOf("wcycle_disabled"),
            )
    }
}

enum class EndocrineApplicationMode {
    DISABLED,
    SHADOW,
    CONFIRM_PENDING,
    APPLIED,
}

/**
 * Lot A: dose path still owned by legacy direct scale.
 * Lots C–D migrate hormonal basal uplift to Harmonia production.
 */
enum class EndocrineDosePathOwner {
    /** @deprecated Lot A transitional — do not use on dose path. */
    LEGACY_DIRECT_SCALE,
    /** Production: governor effective amps applied once on basal/SMB/IC. */
    PRODUCTION_GOVERNOR_DIRECT,
    /** Production tick where Harmonia basal-first already embedded endocrine amp in rate. */
    HARMONIA_PRODUCTION_BASAL_FIRST,
}
