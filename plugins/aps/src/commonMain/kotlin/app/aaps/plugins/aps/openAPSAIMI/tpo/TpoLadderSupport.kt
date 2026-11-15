package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import kotlin.math.abs
import kotlin.math.max

internal object TpoLadderSupport {

    val MAX_SMB_LADDER = listOf(0.80, 1.00, 1.30, 1.80, 2.40)
    val HIGH_BG_MAX_SMB_LADDER = listOf(1.00, 1.25, 1.60, 2.20, 3.00)
    val PRIORITY_MAX_IOB_FACTOR_LADDER = listOf(1.05, 1.10, 1.20, 1.35, 1.50)
    val PRIORITY_MAX_IOB_EXTRA_LADDER = listOf(0.50, 1.00, 2.00, 3.00, 4.00)
    val PKPD_RELIEF_MIN_LADDER = listOf(0.60, 0.68, 0.75, 0.82, 0.90)
    val RED_CARPET_RESTORE_LADDER = listOf(0.60, 0.68, 0.75, 0.82, 0.90)
    /** The meal factor keys are percent (100 = neutral), so this ladder is percent too. */
    val MEAL_FACTOR_LADDER = listOf(70.0, 85.0, 100.0, 115.0, 130.0)
    val TUBE_HYPO_FLOOR_LADDER = listOf(72.0, 76.0, 80.0, 84.0, 88.0)

    /**
     * One step down of a value below its ladder keeps this share of the value (a 15 % cut, the size
     * of the 100 -> 85 rung of the meal factor ladder). Before step T1 such a value was moved up to
     * the lowest rung, so a "step down" raised it (Max SMB 0.3 -> 0.8).
     */
    const val OUTSIDE_LADDER_STEP_DOWN_FACTOR = 0.85

    private const val EPS = 0.0001

    private val TAIL_DAMPING_BAND = listOf(
        PkpdSmbTailDamping.DAMPING_LIGHT,
        PkpdSmbTailDamping.DAMPING_NEUTRAL,
        PkpdSmbTailDamping.DAMPING_STRONG,
    )

    /**
     * Highest exercise SMB multiplier TPO leaves in place. The exercise and late fat damping keys
     * multiply the SMB, so a higher value gives more insulin. A protective pack only lowers a value
     * above this ceiling, and never raises one.
     */
    private const val EXERCISE_DAMPING_CEILING = 0.85

    /** Highest late fat SMB multiplier TPO leaves in place. See [EXERCISE_DAMPING_CEILING]. */
    private const val LATE_FAT_DAMPING_CEILING = 0.90

    /**
     * Lowers [key] by [steps] steps. Each step moves to the next rung below the value, so it moves
     * one rung at most. Above the top rung the next rung below is the top rung. Below the lowest
     * rung a step keeps [OUTSIDE_LADDER_STEP_DOWN_FACTOR] of the value. On the lowest rung there is
     * nothing below, so the value stays. [steps] counts per step, so a pack that asks for 2 steps
     * moves 2 rungs (Max SMB 1.80 -> 1.00), as before step T1.
     *
     * Dosing effect against the code before step T1: a value between two rungs now moves to the
     * next rung below (Max SMB 2.0 -> 1.80, was 1.30); a value above the ladder goes to the top rung
     * (Max SMB 3.0 -> 2.40, was 1.80 because it first snapped to the top rung); a value below the
     * ladder is cut by 15 % (0.30 -> 0.255, was raised to 0.80).
     *
     * The result is never higher than the stored value: a step down must never raise a dose limit.
     * Returns null when there is no change.
     */
    fun stepDownLadder(
        preferences: Preferences,
        key: DoublePreferenceKey,
        ladder: List<Double>,
        steps: Int,
    ): Double? {
        if (steps <= 0 || ladder.isEmpty()) return null
        val current = preferences.get(key)
        var value = current
        for (step in 1..steps) value = oneStepDown(value, ladder) ?: break
        val target = value.coerceIn(key.min, key.max)
        return target.takeIf { target < current - EPS }
    }

    /**
     * Raises [key] by [steps] steps. Each step moves to the next rung above the value, so it moves
     * one rung at most. A value below the lowest rung first goes to the lowest rung without using a
     * step, as before step T1 (hypo floor 70 with 2 steps -> 80). Above the top rung there is
     * nothing to go to, so the value stays (before step T1 a floor of 90 was lowered to 88).
     *
     * The result is never lower than the stored value. Returns null when there is no change.
     */
    fun stepUpLadder(
        preferences: Preferences,
        key: DoublePreferenceKey,
        ladder: List<Double>,
        steps: Int,
    ): Double? {
        if (steps <= 0 || ladder.isEmpty()) return null
        val current = preferences.get(key)
        var value = max(current, ladder.first())
        for (step in 1..steps) value = ladder.firstOrNull { rung -> rung > value + EPS } ?: break
        val target = value.coerceIn(key.min, key.max)
        return target.takeIf { target > current + EPS }
    }

    private fun oneStepDown(value: Double, ladder: List<Double>): Double? {
        val bottom = ladder.first()
        val top = ladder.last()
        return when {
            value < bottom - EPS -> value * OUTSIDE_LADDER_STEP_DOWN_FACTOR
            value > top + EPS    -> top
            else                 -> ladder.lastOrNull { rung -> rung < value - EPS }
        }
    }

    /**
     * Moves the tail damping [steps] steps toward stronger damping (a lower value). A value that is
     * already stronger than the band (for example 0.60) is left alone: a "strengthen" never weakens.
     */
    fun strengthenTailDamping(preferences: Preferences, steps: Int): Double? {
        if (steps <= 0) return null
        val stored = preferences.get(DoubleKey.OApsAIMISmbTailDamping)
        val effective = PkpdSmbTailDamping.effectiveStoredValue(stored)
        val currentIndex = nearestIndex(TAIL_DAMPING_BAND, effective)
        val targetIndex = (currentIndex + steps).coerceAtMost(TAIL_DAMPING_BAND.lastIndex)
        val proposed = PkpdSmbTailDamping.clampForAdvisor(TAIL_DAMPING_BAND[targetIndex])
        return proposed.takeIf { proposed < effective - EPS }
    }

    /**
     * Lowers the exercise SMB multiplier to [EXERCISE_DAMPING_CEILING] when it is above it, else
     * returns null. Before step T1 this moved up a band and raised the default 0.60 to 0.72 (20 %
     * more SMB while exercise is detected) in the poor-sleep pack.
     */
    fun strengthenExerciseDamping(preferences: Preferences, steps: Int): Double? =
        capSmbMultiplier(preferences.get(DoubleKey.OApsAIMISmbExerciseDamping), EXERCISE_DAMPING_CEILING, steps)

    /**
     * Lowers the late fat SMB multiplier to [LATE_FAT_DAMPING_CEILING] when it is above it, else
     * returns null. Before step T1 this raised the default 0.70 to 0.80 (14 % more SMB).
     */
    fun strengthenLateFatDamping(preferences: Preferences, steps: Int): Double? =
        capSmbMultiplier(preferences.get(DoubleKey.OApsAIMISmbLateFatDamping), LATE_FAT_DAMPING_CEILING, steps)

    fun scaleTubeAggressiveness(preferences: Preferences, factor: Double): Double? {
        val current = preferences.get(DoubleKey.AimiTubeAggressiveness)
        val target = (current * factor).coerceIn(DoubleKey.AimiTubeAggressiveness.min, DoubleKey.AimiTubeAggressiveness.max)
        return target.takeIf { abs(target - current) >= 0.0001 }
    }

    private fun capSmbMultiplier(current: Double, ceiling: Double, steps: Int): Double? =
        ceiling.takeIf { steps > 0 && current > ceiling + EPS }

    private fun nearestIndex(band: List<Double>, value: Double): Int =
        band.indices.minByOrNull { index -> abs(band[index] - value) } ?: 0
}
