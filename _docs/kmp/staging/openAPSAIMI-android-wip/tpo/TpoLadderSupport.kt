package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import kotlin.math.abs

internal object TpoLadderSupport {

    val MAX_SMB_LADDER = listOf(0.80, 1.00, 1.30, 1.80, 2.40)
    val HIGH_BG_MAX_SMB_LADDER = listOf(1.00, 1.25, 1.60, 2.20, 3.00)
    val PRIORITY_MAX_IOB_FACTOR_LADDER = listOf(1.05, 1.10, 1.20, 1.35, 1.50)
    val PRIORITY_MAX_IOB_EXTRA_LADDER = listOf(0.50, 1.00, 2.00, 3.00, 4.00)
    val PKPD_RELIEF_MIN_LADDER = listOf(0.60, 0.68, 0.75, 0.82, 0.90)
    val RED_CARPET_RESTORE_LADDER = listOf(0.60, 0.68, 0.75, 0.82, 0.90)
    val MEAL_FACTOR_LADDER = listOf(0.70, 0.85, 1.00, 1.15, 1.30)
    val TUBE_HYPO_FLOOR_LADDER = listOf(72.0, 76.0, 80.0, 84.0, 88.0)

    private val TAIL_DAMPING_BAND = listOf(
        PkpdSmbTailDamping.DAMPING_LIGHT,
        PkpdSmbTailDamping.DAMPING_NEUTRAL,
        PkpdSmbTailDamping.DAMPING_STRONG,
    )

    private val EXERCISE_DAMPING_BAND = listOf(0.45, 0.60, 0.72, 0.85)
    private val LATE_FAT_DAMPING_BAND = listOf(0.55, 0.70, 0.80, 0.90)

    fun stepDownLadder(
        preferences: Preferences,
        key: DoublePreferenceKey,
        ladder: List<Double>,
        steps: Int,
    ): Double? {
        if (steps <= 0 || ladder.isEmpty()) return null
        val current = preferences.get(key)
        val currentIndex = ladder.indices.minByOrNull { index -> abs(ladder[index] - current) } ?: 0
        val targetIndex = (currentIndex - steps).coerceAtLeast(0)
        val target = ladder[targetIndex].coerceIn(key.min, key.max)
        return target.takeIf { abs(target - current) >= 0.0001 }
    }

    fun stepUpLadder(
        preferences: Preferences,
        key: DoublePreferenceKey,
        ladder: List<Double>,
        steps: Int,
    ): Double? {
        if (steps <= 0 || ladder.isEmpty()) return null
        val current = preferences.get(key)
        val currentIndex = ladder.indices.minByOrNull { index -> abs(ladder[index] - current) } ?: 0
        val targetIndex = (currentIndex + steps).coerceAtMost(ladder.lastIndex)
        val target = ladder[targetIndex].coerceIn(key.min, key.max)
        return target.takeIf { abs(target - current) >= 0.0001 }
    }

    fun strengthenTailDamping(preferences: Preferences, steps: Int): Double? {
        if (steps <= 0) return null
        val stored = preferences.get(DoubleKey.OApsAIMISmbTailDamping)
        val effective = PkpdSmbTailDamping.effectiveStoredValue(stored)
        val currentIndex = nearestIndex(TAIL_DAMPING_BAND, effective)
        val targetIndex = (currentIndex + steps).coerceAtMost(TAIL_DAMPING_BAND.lastIndex)
        val proposed = PkpdSmbTailDamping.clampForAdvisor(TAIL_DAMPING_BAND[targetIndex])
        return proposed.takeIf { abs(proposed - effective) >= 0.0001 }
    }

    fun strengthenExerciseDamping(preferences: Preferences, steps: Int): Double? {
        return stepBand(preferences.get(DoubleKey.OApsAIMISmbExerciseDamping), EXERCISE_DAMPING_BAND, steps)
            ?.coerceIn(DoubleKey.OApsAIMISmbExerciseDamping.min, DoubleKey.OApsAIMISmbExerciseDamping.max)
    }

    fun strengthenLateFatDamping(preferences: Preferences, steps: Int): Double? {
        return stepBand(preferences.get(DoubleKey.OApsAIMISmbLateFatDamping), LATE_FAT_DAMPING_BAND, steps)
            ?.coerceIn(DoubleKey.OApsAIMISmbLateFatDamping.min, DoubleKey.OApsAIMISmbLateFatDamping.max)
    }

    fun scaleTubeAggressiveness(preferences: Preferences, factor: Double): Double? {
        val current = preferences.get(DoubleKey.AimiTubeAggressiveness)
        val target = (current * factor).coerceIn(DoubleKey.AimiTubeAggressiveness.min, DoubleKey.AimiTubeAggressiveness.max)
        return target.takeIf { abs(target - current) >= 0.0001 }
    }

    private fun stepBand(current: Double, band: List<Double>, steps: Int): Double? {
        if (steps <= 0) return null
        val currentIndex = nearestIndex(band, current)
        val targetIndex = (currentIndex + steps).coerceAtMost(band.lastIndex)
        return band[targetIndex].takeIf { abs(band[targetIndex] - current) >= 0.0001 }
    }

    private fun nearestIndex(band: List<Double>, value: Double): Int =
        band.indices.minByOrNull { index -> abs(band[index] - value) } ?: 0
}
