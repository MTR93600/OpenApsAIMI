package app.aaps.plugins.aps.openAPSAIMI.model

data class BgSnapshot(
    val mgdl: Double,
    val delta5: Double,
    val shortAvgDelta: Double?,
    val longAvgDelta: Double?,
    val accel: Double? = null,
    val r2: Double? = null,
    val parabolaMinutes: Double? = null,
    val combinedDelta: Double? = null,
    val epochMillis: Long
)

data class ModeState(
    val autodrive: Boolean = false,
    val meal: Boolean = false,
    val breakfast: Boolean = false,
    val lunch: Boolean = false,
    val dinner: Boolean = false,
    val highCarb: Boolean = false,
    val snack: Boolean = false,
    val sleep: Boolean = false
)

data class PumpCaps(
    val basalStep: Double,
    val bolusStep: Double,
    val minDurationMin: Int,
    val maxBasal: Double,
    val maxSmb: Double
)

data class LoopProfile(
    val targetMgdl: Double,
    val isfMgdlPerU: Double,
    val basalProfileUph: Double,
    val lgsThreshold: Double, // Added for Hypo safety respecting user prefs
    val minBg: Double? = null  // Min BG from profile for LGS fallback calculation
)

data class AimiSettings(
    val smbIntervalMin: Int,
    val wCycleEnabled: Boolean
)

data class LoopContext(
    val bg: BgSnapshot,
    val iobU: Double,
    val cobG: Double,
    val profile: LoopProfile,
    val pump: PumpCaps,
    val modes: ModeState,
    val settings: AimiSettings,
    val tdd24hU: Double,
    val eventualBg: Double,
    val nowEpochMillis: Long
)

data class BasalPlan(val rateUph: Double, val durationMin: Int, val reason: String)
data class SmbPlan(val units: Double, val deliverAtMillis: Long, val reason: String)

data class SafetyReport(val hypoBlocked: Boolean, val notes: List<String> = emptyList())
data class Decision(val basal: BasalPlan?, val smb: SmbPlan?, val safety: SafetyReport)

enum class ActionKind {
    MANUAL_MODE,
    MEAL_ADVISOR,
    AUTODRIVE,
    GLOBAL_AIMI,
    SAFETY_HALT
}

sealed class DecisionResult {
    abstract val reason: String
    /** Decision confirmed and applied to the pump. Includes rollback context. */
    data class Applied(
        val source: String,
        val bolusU: Double? = null,
        val tbrUph: Double? = null,
        val tbrMin: Int? = null,
        override val reason: String,
        val originalBasalRate: Double? = null,
        val originalBasalDuration: Int? = null,
        val timestampMs: Long = System.currentTimeMillis()
    ) : DecisionResult() {
        fun undo(undoReason: String = "Undo Applied Action"): Applied = copy(
            tbrUph = originalBasalRate,
            tbrMin = originalBasalDuration,
            reason = "ROLLBACK ($source): $undoReason"
        )
    }

    /** Decision rejected by the Auditor or safety shield. Triggers optional rollback. */
    data class Rejected(
        val source: String,
        override val reason: String,
        val severity: AdvisorSeverity,
        val timestampMs: Long = System.currentTimeMillis()
    ) : DecisionResult()

    /** Decision skipped as unnecessary or redundant (e.g., small correction below threshold). */
    data class Skipped(
        val source: String,
        override val reason: String,
        val timestampMs: Long = System.currentTimeMillis()
    ) : DecisionResult()

    /** Legacy or internal signal to continue without AIMI intervention. */
    data class Fallthrough(override val reason: String) : DecisionResult()
}

/**
 * Global processor for exhaustive DecisionResult handling.
 * Used at the end of the AI loop to translate internal verdicts into system actions.
 */
fun processDecision(result: DecisionResult) {
    when (result) {
        is DecisionResult.Applied -> {
            // Logic for applying bolus and TBR (delegated to pump controller)
            android.util.Log.i("AIMI_LOG", "✅ Decision APPLIED (${result.source}): ${result.reason}")
        }
        is DecisionResult.Rejected -> {
            // Logic for rolling back or stopping active actions
            android.util.Log.w("AIMI_LOG", "🛑 Decision REJECTED (${result.source}): ${result.reason}")
        }
        is DecisionResult.Skipped -> {
            // Log skipping (no action needed)
            android.util.Log.d("AIMI_LOG", "⏸ Decision SKIPPED (${result.source}): ${result.reason}")
        }
        is DecisionResult.Fallthrough -> {
            // Continue as-is
            android.util.Log.d("AIMI_LOG", "💨 Decision FALLTHROUGH: ${result.reason}")
        }
    }
}
