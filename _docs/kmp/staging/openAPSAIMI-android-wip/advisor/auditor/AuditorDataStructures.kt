package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import app.aaps.core.data.json.OrgJsonCompat.optJsonArrayCompat

import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaHarmonizer
import app.aaps.plugins.aps.openAPSAIMI.patient.HarmoniaProductionDecision
import app.aaps.plugins.aps.openAPSAIMI.patient.MealCertainty
import app.aaps.plugins.aps.openAPSAIMI.patient.PhysiologicalTreeSnapshot
import app.aaps.plugins.aps.openAPSAIMI.model.VerdictType
import app.aaps.plugins.aps.openAPSAIMI.model.AdvisorSeverity

/**
 * ============================================================================
 * AIMI AI Decision Auditor - Data Structures
 * ============================================================================
 * 
 * Structures for the "Second Brain" AI auditor that challenges and modulates
 * AIMI decisions with bounded, safe adjustments.
 * 
 * Architecture: Cognitive Audit + Bounded Modulator
 * Mode: NEVER direct command - only bounded modulation
 */

/**
 * 📊 AuditorUIState
 * 
 * Represents the operational state of the Auditor UI and clinical verification loop.
 * Enforces strict transition rules to maintain safety and visibility.
 */
sealed class AuditorUIState {
    
    /** Standard reset state. Loop is waiting for next tick. */
    object Idle : AuditorUIState()

    /** AI is currently processing the decision snapshot. */
    object Processing : AuditorUIState()

    /** Verdict received and validated. Applied to the loop. */
    data class Ready(val lastVerdict: String) : AuditorUIState()

    /** Clinical warning detected. Requires attention or Ready state to clear. */
    data class Warning(val severity: AdvisorSeverity.Warning) : AuditorUIState()

    /** System error or timeout. Must recover to Ready for reliable resumption. */
    data class Error(val message: String) : AuditorUIState()

    /**
     * Validates if a transition to [nextState] is allowed according to business rules.
     * 
     * Rules:
     * - IDLE → PROCESSING → READY (Linear loop flow)
     * - WARNING cannot go directly to IDLE (Must be acknowledged via loop result)
     * - ERROR requires a transition to READY/IDLE after recovery logic
     */
    fun canTransitionTo(nextState: AuditorUIState): Boolean = when (this) {
        is Idle -> nextState is Processing || nextState is Error
        is Processing -> nextState is Ready || nextState is Warning || nextState is Error
        is Ready -> nextState is Idle || nextState is Processing || nextState is Warning || nextState is Error
        is Warning -> nextState is Ready || nextState is Error // WARNING cannot go to IDLE directly
        is Error -> nextState is Ready || nextState is Processing // ERROR must resolve before IDLE
    }
}

// ============================================================================
// INPUT: Data sent to LLM
// ============================================================================

/**
 * Complete snapshot sent to AI auditor
 * Contains: snapshot + history + stats
 */
data class AuditorInput(
    val snapshot: Snapshot,
    val history: History,
    val stats: Stats7d,
    val trajectory: TrajectorySnapshot?,
    val physiologicalTree: PhysiologicalTreeSnapshot? = null,
    val harmoniaDecision: HarmoniaDecision? = null,
    val mealCertainty: MealCertainty? = null,
    val harmoniaProduction: HarmoniaProductionDecision? = null,
    val harmonizerOutcome: HarmoniaHarmonizer.Outcome? = null,
    /** Pattern catalog soft/hard proposals for this tick (advisory context). */
    val physiologicalPatterns: JsonObject? = null,
    /** Harmonia SMB authority decision — Auditor CONFIRM/SOFTEN only, never lift. */
    val harmoniaSmbAuthority: JsonObject? = null,
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("snapshot", snapshot.toJSON())
        put("history", history.toJSON())
        put("stats", stats.toJSON())
        if (trajectory != null) put("trajectory", trajectory.toJSON())
        if (physiologicalTree != null) put("physiological_tree", physiologicalTree.toJsonObject())
        if (harmoniaDecision != null) put("harmonia_simulation", harmoniaDecision.toJsonObject())
        if (mealCertainty != null) put("meal_certainty", mealCertainty.toJsonObject())
        if (harmoniaProduction != null) put("harmonia_production", harmoniaProduction.toJsonObject())
        if (physiologicalPatterns != null) put("physiological_patterns", physiologicalPatterns)
        if (harmoniaSmbAuthority != null) put("harmonia_smb_authority", harmoniaSmbAuthority)
        if (harmonizerOutcome != null) {
            put(
                "harmonia_harmonizer",
                buildJsonObject {
                    put("posture", harmonizerOutcome.posture.name)
                    put("tbr_factor", harmonizerOutcome.tbrFactor)
                    put("smb_factor", harmonizerOutcome.smbFactor)
                    put("reasons", JsonArray(harmonizerOutcome.reasons.map { JsonPrimitive(it) }))
                },
            )
        }
        harmoniaDecision?.decisionBasis?.let { put("decision_basis", it.toJsonObject()) }
    }
}

/**
 * A) Snapshot: Current state "here and now"
 */
data class Snapshot(
    // Glucose
    val bg: Double,
    val delta: Double,
    val shortAvgDelta: Double,
    val longAvgDelta: Double,
    val unit: String,
    val timestamp: Long,
    val cgmAgeMin: Int,
    val noise: String,
    
    // IOB/COB
    val iob: Double,
    val iobActivity: Double?,
    val cob: Double?,
    
    // Insulin sensitivity & targets
    val isfProfile: Double,
    val isfUsed: Double,
    val ic: Double,
    val target: Double,
    
    // PKPD
    val pkpd: PKPDSnapshot,
    
    // Activity
    val activity: ActivitySnapshot,
    val physio: PhysioSnapshot?,
    
    // States
    val states: StatesSnapshot,
    
    // Limits
    val limits: LimitsSnapshot,
    
    // AIMI Decision
    val decisionAimi: DecisionSnapshot,
    
    // Last delivery
    val lastDelivery: LastDeliverySnapshot
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("bg", bg)
        put("delta", delta)
        put("shortAvgDelta", shortAvgDelta)
        put("longAvgDelta", longAvgDelta)
        put("unit", unit)
        put("timestamp", timestamp)
        put("cgmAgeMin", cgmAgeMin)
        put("noise", noise)
        put("iob", iob)
        put("iobActivity", iobActivity)
        put("cob", cob)
        put("isfProfile", isfProfile)
        put("isfUsed", isfUsed)
        put("ic", ic)
        put("target", target)
        put("pkpd", pkpd.toJSON())
        put("activity", activity.toJSON())
        if (physio != null) put("physio", physio.toJSON())
        put("states", states.toJSON())
        put("limits", limits.toJSON())
        put("decisionAimi", decisionAimi.toJSON())
        put("lastDelivery", lastDelivery.toJSON())
    }
}

data class PKPDSnapshot(
    val diaMin: Int,
    val peakMin: Int,
    val tailFrac: Double,
    val onsetConfirmed: Boolean?,
    val residualEffect: Double?
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("diaMin", diaMin)
        put("peakMin", peakMin)
        put("tailFrac", tailFrac)
        put("onsetConfirmed", onsetConfirmed)
        put("residualEffect", residualEffect)
    }
}

data class ActivitySnapshot(
    val steps5min: Int,
    val steps30min: Int,
    val hrAvg5: Int?,
    val hrAvg15: Int?
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("steps5min", steps5min)
        put("steps30min", steps30min)
        put("hrAvg5", hrAvg5)
        put("hrAvg15", hrAvg15)
    }
}

data class StatesSnapshot(
    val modeType: String?,
    val modeRuntimeMin: Int?,
    val autodriveState: String,
    val wcyclePhase: String?,
    val wcycleFactor: Double?
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("modeType", modeType)
        put("modeRuntimeMin", modeRuntimeMin)
        put("autodriveState", autodriveState)
        put("wcyclePhase", wcyclePhase)
        put("wcycleFactor", wcycleFactor)
    }
}

data class LimitsSnapshot(
    val maxSMB: Double,
    val maxSMBHB: Double,
    val maxIOB: Double,
    val maxBasal: Double,
    val tbrMaxMode: Double?,
    val tbrMaxAutoDrive: Double?
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("maxSMB", maxSMB)
        put("maxSMBHB", maxSMBHB)
        put("maxIOB", maxIOB)
        put("maxBasal", maxBasal)
        put("tbrMaxMode", tbrMaxMode)
        put("tbrMaxAutoDrive", tbrMaxAutoDrive)
    }
}

data class DecisionSnapshot(
    val smbU: Double,
    val tbrUph: Double?,
    val tbrMin: Int?,
    val intervalMin: Double,
    val reasonTags: List<String>
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("smbU", smbU)
        put("tbrUph", tbrUph)
        put("tbrMin", tbrMin)
        put("intervalMin", intervalMin)
        put("reasonTags", JsonArray(reasonTags.map { JsonPrimitive(it) }))
    }
}

data class LastDeliverySnapshot(
    val lastBolusU: Double?,
    val lastBolusTime: Long?,
    val lastSmbU: Double?,
    val lastSmbTime: Long?,
    val lastTbrRate: Double?,
    val lastTbrTime: Long?
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("lastBolusU", lastBolusU)
        put("lastBolusTime", lastBolusTime)
        put("lastSmbU", lastSmbU)
        put("lastSmbTime", lastSmbTime)
        put("lastTbrRate", lastTbrRate)
        put("lastTbrTime", lastTbrTime)
    }
}

/**
 * B) History: Short-term trajectory (45-60 min, max 12 points)
 */
data class History(
    val bgSeries: List<Double>,
    val deltaSeries: List<Double>,
    val iobSeries: List<Double>,
    val tbrSeries: List<Double?>,
    val smbSeries: List<Double>,
    val hrSeries: List<Int?>,
    val stepsSeries: List<Int>
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("bgSeries", JsonArray(bgSeries.map { JsonPrimitive(it) }))
        put("deltaSeries", JsonArray(deltaSeries.map { JsonPrimitive(it) }))
        put("iobSeries", JsonArray(iobSeries.map { JsonPrimitive(it) }))
        put("tbrSeries", JsonArray(tbrSeries.map { JsonPrimitive(it) }))
        put("smbSeries", JsonArray(smbSeries.map { JsonPrimitive(it) }))
        put("hrSeries", JsonArray(hrSeries.map { JsonPrimitive(it) }))
        put("stepsSeries", JsonArray(stepsSeries.map { JsonPrimitive(it) }))
    }
}

/**
 * C) Stats: 7-day summary (compressed)
 */
data class Stats7d(
    val tir: Double,
    val hypoPct: Double,
    val hyperPct: Double,
    val meanBG: Double,
    val cv: Double,
    val tdd7dAvg: Double,
    val basalPct: Double,
    val bolusPct: Double
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("tir", tir)
        put("hypoPct", hypoPct)
        put("hyperPct", hyperPct)
        put("meanBG", meanBG)
        put("cv", cv)
        put("tdd7dAvg", tdd7dAvg)
        put("basalPct", basalPct)
        put("bolusPct", bolusPct)
    }
}

/**
 * E) Trajectory: Phase-Space Geometric Analysis
 */
data class TrajectorySnapshot(
    val type: String,          // STABLE_ORBIT, TIGHT_SPIRAL, SLOW_DRIFT, HOVERING...
    val curvature: Double,     // 0.0 - 1.0
    val convergence: Double,   // mg/dL/min
    val coherence: Double,     // -1.0 to 1.0
    val energyBalance: Double, // U
    val modulation: String?    // Description of active modulation (e.g. "SMBx1.15 (Slow drift)")
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("type", type)
        put("curvature", curvature)
        put("convergence", convergence)
        put("coherence", coherence)
        put("energyBalance", energyBalance)
        if (modulation != null) put("modulation", modulation)
    }
}

// ============================================================================
// OUTPUT: AI Auditor Response
// ============================================================================

/**
 * AI Auditor verdict with bounded modulation
 */
data class AuditorVerdict(
    val verdict: VerdictType,
    val confidence: Double,
    val degradedMode: Boolean,
    val riskFlags: List<String>,
    val evidence: List<String>,
    val boundedAdjustments: BoundedAdjustments,
    val debugChecks: List<String>
) {
    companion object {
        /**
         * Parse from JSON response
         */
        fun fromJSON(json: JsonObject): AuditorVerdict {
            val adjustments = json.getValue("boundedAdjustments").jsonObject
            val verdictStr = json.getValue("verdict").jsonPrimitive.content.uppercase()
            
            val verdict = when (verdictStr) {
                "CONFIRM" -> VerdictType.Confirm
                "SOFTEN" -> VerdictType.Soften
                "SHIFT_TO_TBR" -> VerdictType.ShiftToTbr
                else -> VerdictType.Confirm // Default safety
            }
            
            return AuditorVerdict(
                verdict = verdict,
                confidence = json.getValue("confidence").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double."),
                degradedMode = json.getValue("degradedMode").jsonPrimitive.booleanOrNull ?: error("JSON field is not a boolean."),
                riskFlags = json.getValue("riskFlags").jsonArray.let { arr ->
                    (0 until arr.size).map { arr[it].jsonPrimitive.content }
                },
                evidence = json.getValue("evidence").jsonArray.let { arr ->
                    (0 until arr.size).map { arr[it].jsonPrimitive.content }
                },
                boundedAdjustments = BoundedAdjustments(
                    smbFactorClamp = adjustments.getValue("smbFactorClamp").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double."),
                    intervalAddMin = adjustments.getValue("intervalAddMin").jsonPrimitive.intOrNull ?: error("JSON field is not an int."),
                    preferTbr = adjustments.getValue("preferTbr").jsonPrimitive.booleanOrNull ?: error("JSON field is not a boolean."),
                    tbrFactorClamp = adjustments.getValue("tbrFactorClamp").jsonPrimitive.doubleOrNull ?: error("JSON field is not a double.")
                ),
                debugChecks = json.optJsonArrayCompat("debugChecks")?.let { arr ->
                    (0 until arr.size).map { arr[it].jsonPrimitive.content }
                } ?: emptyList()
            )
        }
    }
}

/**
 * Bounded adjustments - NEVER free dosing
 */
data class BoundedAdjustments(
    val smbFactorClamp: Double,     // 0.0 to 1.0 (multiply proposed SMB)
    val intervalAddMin: Int,        // 0 to +6 min (add to interval)
    val preferTbr: Boolean,         // switch to TBR preference
    val tbrFactorClamp: Double      // 0.8 to 1.2 (multiply TBR rate if applicable)
)

/**
 * D) Physio: Physiological Context (Stress, Sleep, Recovery)
 */
data class PhysioSnapshot(
    val state: String,
    val snsDominance: Double,
    val sleepQualityZ: Double,
    val rhrZ: Double,
    val hrvZ: Double
) {
    fun toJSON(): JsonObject = buildJsonObject {
        put("state", state)
        put("snsDominance", snsDominance)
        put("sleepQualityZ", sleepQualityZ)
        put("rhrZ", rhrZ)
        put("hrvZ", hrvZ)
    }
}
