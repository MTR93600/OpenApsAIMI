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
    val lgsThreshold: Double // Added for Hypo safety respecting user prefs
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


