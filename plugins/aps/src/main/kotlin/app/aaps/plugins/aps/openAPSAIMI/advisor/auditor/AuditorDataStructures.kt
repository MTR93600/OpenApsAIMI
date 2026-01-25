package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import org.json.JSONArray
import org.json.JSONObject

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
    val trajectory: TrajectorySnapshot?
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("snapshot", snapshot.toJSON())
        put("history", history.toJSON())
        put("stats", stats.toJSON())
        if (trajectory != null) put("trajectory", trajectory.toJSON())
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
    fun toJSON(): JSONObject = JSONObject().apply {
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
    fun toJSON(): JSONObject = JSONObject().apply {
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
    fun toJSON(): JSONObject = JSONObject().apply {
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
    fun toJSON(): JSONObject = JSONObject().apply {
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
    fun toJSON(): JSONObject = JSONObject().apply {
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
    fun toJSON(): JSONObject = JSONObject().apply {
        put("smbU", smbU)
        put("tbrUph", tbrUph)
        put("tbrMin", tbrMin)
        put("intervalMin", intervalMin)
        put("reasonTags", JSONArray(reasonTags))
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
    fun toJSON(): JSONObject = JSONObject().apply {
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
    fun toJSON(): JSONObject = JSONObject().apply {
        put("bgSeries", JSONArray(bgSeries))
        put("deltaSeries", JSONArray(deltaSeries))
        put("iobSeries", JSONArray(iobSeries))
        put("tbrSeries", JSONArray(tbrSeries))
        put("smbSeries", JSONArray(smbSeries))
        put("hrSeries", JSONArray(hrSeries))
        put("stepsSeries", JSONArray(stepsSeries))
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
    fun toJSON(): JSONObject = JSONObject().apply {
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
    val type: String,          // STABLE_ORBIT, TIGHT_SPIRAL...
    val curvature: Double,     // 0.0 - 1.0
    val convergence: Double,   // mg/dL/min
    val coherence: Double,     // -1.0 to 1.0
    val energyBalance: Double  // U
) {
    fun toJSON(): JSONObject = JSONObject().apply {
        put("type", type)
        put("curvature", curvature)
        put("convergence", convergence)
        put("coherence", coherence)
        put("energyBalance", energyBalance)
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
        fun fromJSON(json: JSONObject): AuditorVerdict {
            val adjustments = json.getJSONObject("boundedAdjustments")
            
            return AuditorVerdict(
                verdict = VerdictType.valueOf(json.getString("verdict")),
                confidence = json.getDouble("confidence"),
                degradedMode = json.getBoolean("degradedMode"),
                riskFlags = json.getJSONArray("riskFlags").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                evidence = json.getJSONArray("evidence").let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                },
                boundedAdjustments = BoundedAdjustments(
                    smbFactorClamp = adjustments.getDouble("smbFactorClamp"),
                    intervalAddMin = adjustments.getInt("intervalAddMin"),
                    preferTbr = adjustments.getBoolean("preferTbr"),
                    tbrFactorClamp = adjustments.getDouble("tbrFactorClamp")
                ),
                debugChecks = json.optJSONArray("debugChecks")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()
            )
        }
    }
}

enum class VerdictType {
    CONFIRM,        // Keep decision as-is
    SOFTEN,         // Reduce SMB + optionally increase interval
    SHIFT_TO_TBR    // Minimal SMB + moderate TBR
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
    fun toJSON(): JSONObject = JSONObject().apply {
        put("state", state)
        put("snsDominance", snsDominance)
        put("sleepQualityZ", sleepQualityZ)
        put("rhrZ", rhrZ)
        put("hrvZ", hrvZ)
    }
}
