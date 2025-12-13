package app.aaps.plugins.aps.openAPSAIMI.wcycle

enum class CyclePhase { MENSTRUATION, FOLLICULAR, OVULATION, LUTEAL, UNKNOWN }
enum class CycleTrackingMode { FIXED_28, CALENDAR_VARIABLE, NO_MENSES_LARC, PERIMENOPAUSE, MENOPAUSE }
enum class ContraceptiveType { NONE, COC_PILL, POP_PILL, HORMONAL_IUD, COPPER_IUD, IMPLANT, INJECTION, RING, PATCH }
enum class ThyroidStatus { EUTHYROID, HYPOTHYROID_TREATED, HASHIMOTO, THYROIDECTOMY }
enum class VerneuilStatus { NONE, QUIESCENT, ACTIVE, FLARE }

data class WCycleProfile(
    val trackingMode: CycleTrackingMode,
    val contraceptive: ContraceptiveType,
    val thyroid: ThyroidStatus,
    val verneuil: VerneuilStatus,
    val startDom: Int?,
    val cycleAvgLength: Int,
    val shadowMode: Boolean,
    val requireUserConfirm: Boolean,
    val clampMin: Double = 0.7,
    val clampMax: Double = 1.3
)

data class WCycleInfo(
    val enabled: Boolean,
    val dayInCycle: Int,
    val phase: CyclePhase,
    val baseBasalMultiplier: Double,
    val baseSmbMultiplier: Double,
    val learnedBasalMultiplier: Double,
    val learnedSmbMultiplier: Double,
    val basalMultiplier: Double,
    val smbMultiplier: Double,
    val applied: Boolean,
    val reason: String
)

object WCycleDefaults {
    fun baseMultipliers(phase: CyclePhase): Pair<Double, Double> = when (phase) {
        CyclePhase.MENSTRUATION -> 1.0 - 0.08 to 1.0
        CyclePhase.FOLLICULAR   -> 1.0 to 1.0
        // 🔮 FCL 11.0: Endoc. Update - LH Surge Resistance
        CyclePhase.OVULATION    -> 1.0 + 0.05 to 1.0 + 0.05 
        // 🔮 FCL 11.0: Endoc. Update - Progesterone Resistance (Stronger)
        CyclePhase.LUTEAL       -> 1.0 + 0.25 to 1.0 + 0.12
        else                    -> 1.0 to 1.0
    }
    fun amplitudeScale(c: ContraceptiveType): Double = when (c) {
        ContraceptiveType.NONE, ContraceptiveType.COPPER_IUD -> 1.0
        ContraceptiveType.HORMONAL_IUD, ContraceptiveType.IMPLANT, ContraceptiveType.INJECTION -> 0.5
        ContraceptiveType.COC_PILL, ContraceptiveType.POP_PILL, ContraceptiveType.RING, ContraceptiveType.PATCH -> 0.4
    }
    fun verneuilBump(v: VerneuilStatus): Pair<Double, Double> = when (v) {
        VerneuilStatus.FLARE  -> 1.10 to 1.07
        VerneuilStatus.ACTIVE -> 1.05 to 1.03
        else                  -> 1.0  to 1.0
    }
}
