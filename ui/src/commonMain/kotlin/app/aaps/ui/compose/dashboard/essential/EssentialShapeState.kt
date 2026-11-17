package app.aaps.ui.compose.dashboard.essential

import app.aaps.core.data.model.TrendArrow

/** What the living shape says about the glucose. */
enum class EssentialMood {
    STABLE,
    RISING,
    FALLING,
    HYPER,
    HYPO,

    /** In range, but the prediction at 60 min is above the high mark. */
    TRAJECTORY_HYPER,

    /** In range, but the prediction at 60 min is under the low mark. */
    TRAJECTORY_HYPO,

    /** No glucose younger than 10 minutes. */
    NO_DATA,
}

/**
 * The inputs of the shape, all in mg/dL.
 *
 * @property lowMgdl the user's low mark, never a fixed 70
 * @property highMgdl the user's high mark, never a fixed 180
 */
data class EssentialInputs(
    val bgMgdl: Double?,
    val stale: Boolean,
    val trend: TrendArrow?,
    val prediction60Mgdl: Double?,
    val lowMgdl: Double,
    val highMgdl: Double,
)

/**
 * Picks the shape's mood. Pure, so it is tested without a screen.
 *
 * Hysteresis: once in HYPO or HYPER, the shape stays there until glucose is back past the mark by
 * [MARGIN_MGDL], and a trajectory stays until the prediction is back past its mark by the same margin.
 * So a value sitting on a mark does not make the shape flicker between two states.
 */
object EssentialShapeState {

    const val MARGIN_MGDL = 5.0

    fun classify(inputs: EssentialInputs, previous: EssentialMood?): EssentialMood {
        val bg = inputs.bgMgdl
        if (bg == null || inputs.stale || !bg.isFinite()) return EssentialMood.NO_DATA
        val low = inputs.lowMgdl
        val high = inputs.highMgdl

        val hypoLimit = if (previous == EssentialMood.HYPO) low + MARGIN_MGDL else low
        if (bg < hypoLimit) return EssentialMood.HYPO
        val hyperLimit = if (previous == EssentialMood.HYPER) high - MARGIN_MGDL else high
        if (bg > hyperLimit) return EssentialMood.HYPER

        val prediction = inputs.prediction60Mgdl?.takeIf { it.isFinite() }
        if (prediction != null) {
            val trajectoryHigh = if (previous == EssentialMood.TRAJECTORY_HYPER) high - MARGIN_MGDL else high
            if (prediction > trajectoryHigh) return EssentialMood.TRAJECTORY_HYPER
            val trajectoryLow = if (previous == EssentialMood.TRAJECTORY_HYPO) low + MARGIN_MGDL else low
            if (prediction < trajectoryLow) return EssentialMood.TRAJECTORY_HYPO
        }
        return when (inputs.trend) {
            TrendArrow.TRIPLE_UP, TrendArrow.DOUBLE_UP, TrendArrow.SINGLE_UP, TrendArrow.FORTY_FIVE_UP           -> EssentialMood.RISING
            TrendArrow.TRIPLE_DOWN, TrendArrow.DOUBLE_DOWN, TrendArrow.SINGLE_DOWN, TrendArrow.FORTY_FIVE_DOWN -> EssentialMood.FALLING
            else                                                                                              -> EssentialMood.STABLE
        }
    }
}

/** The moods in which pulling the shape up may start a Boost: glucose is going, or is, too high. */
fun EssentialMood.offersBoost(): Boolean =
    this == EssentialMood.TRAJECTORY_HYPER || this == EssentialMood.RISING || this == EssentialMood.HYPER

/**
 * The geometry and rhythm of a mood. Every mood is one set of numbers, and a change of mood animates
 * from one set to the other.
 *
 * @property stretchY above 1 stretches the shape up and down
 * @property lean -1 leans it down (drop), 1 leans it up (egg), 0 none
 * @property angularity 0 round, 1 corners
 * @property scale size against the normal shape
 * @property breathPeriodMs one breath; 0 is still
 * @property breathAmplitude how much one breath changes the size
 * @property pulses the only pulsing mood is HYPO
 */
data class ShapeParams(
    val stretchY: Float,
    val lean: Float,
    val angularity: Float,
    val scale: Float,
    val breathPeriodMs: Int,
    val breathAmplitude: Float,
    val pulses: Boolean,
)

fun EssentialMood.shapeParams(): ShapeParams = when (this) {
    EssentialMood.STABLE           -> ShapeParams(stretchY = 1.00f, lean = 0f, angularity = 0f, scale = 1.00f, breathPeriodMs = 4000, breathAmplitude = 0.02f, pulses = false)
    EssentialMood.RISING           -> ShapeParams(stretchY = 1.12f, lean = 0.6f, angularity = 0f, scale = 1.00f, breathPeriodMs = 2600, breathAmplitude = 0.025f, pulses = false)
    EssentialMood.FALLING          -> ShapeParams(stretchY = 1.10f, lean = -0.6f, angularity = 0f, scale = 1.00f, breathPeriodMs = 2600, breathAmplitude = 0.025f, pulses = false)
    EssentialMood.HYPER            -> ShapeParams(stretchY = 1.00f, lean = 0f, angularity = 0.05f, scale = 1.08f, breathPeriodMs = 5500, breathAmplitude = 0.015f, pulses = false)
    EssentialMood.HYPO             -> ShapeParams(stretchY = 0.96f, lean = 0f, angularity = 0.55f, scale = 0.86f, breathPeriodMs = 1200, breathAmplitude = 0.06f, pulses = true)
    EssentialMood.TRAJECTORY_HYPER -> ShapeParams(stretchY = 1.16f, lean = 1f, angularity = 0f, scale = 1.00f, breathPeriodMs = 2200, breathAmplitude = 0.03f, pulses = false)
    EssentialMood.TRAJECTORY_HYPO  -> ShapeParams(stretchY = 1.14f, lean = -1f, angularity = 0.1f, scale = 0.98f, breathPeriodMs = 2200, breathAmplitude = 0.03f, pulses = false)
    EssentialMood.NO_DATA          -> ShapeParams(stretchY = 1.00f, lean = 0f, angularity = 0f, scale = 0.94f, breathPeriodMs = 0, breathAmplitude = 0f, pulses = false)
}
