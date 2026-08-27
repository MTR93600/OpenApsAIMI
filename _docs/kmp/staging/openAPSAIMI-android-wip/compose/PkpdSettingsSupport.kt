package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.plugins.aps.openAPSAIMI.advisor.PkpdPrefsSnapshot
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import kotlin.math.abs

/** Maps a single 0–1 “correction prudence” slider to ISF fusion bounds. */
object PkpdCorrectionPrudence {
    private const val MIN_FACTOR_PRUDENT = 0.85
    private const val MAX_FACTOR_PRUDENT = 1.10
    private const val MIN_FACTOR_NEUTRAL = 0.75
    private const val MAX_FACTOR_NEUTRAL = 1.25
    private const val MIN_FACTOR_AGGRESSIVE = 0.65
    private const val MAX_FACTOR_AGGRESSIVE = 1.40

    fun readLevel(preferences: Preferences): Double =
        readLevelFromFactors(
            preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
            preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
        )

    internal fun readLevelFromFactors(min: Double, max: Double): Double {
        if (near(min, MIN_FACTOR_NEUTRAL) && near(max, MAX_FACTOR_NEUTRAL)) return 0.5
        if (near(min, MIN_FACTOR_PRUDENT) && near(max, MAX_FACTOR_PRUDENT)) return 0.0
        if (near(min, MIN_FACTOR_AGGRESSIVE) && near(max, MAX_FACTOR_AGGRESSIVE)) return 1.0
        return when {
            min >= MIN_FACTOR_PRUDENT - 0.02 -> 0.0
            min <= MIN_FACTOR_AGGRESSIVE + 0.02 -> 1.0
            min >= MIN_FACTOR_NEUTRAL ->
                0.5 * (MIN_FACTOR_PRUDENT - min) / (MIN_FACTOR_PRUDENT - MIN_FACTOR_NEUTRAL)
            else ->
                0.5 + 0.5 * (MIN_FACTOR_NEUTRAL - min) / (MIN_FACTOR_NEUTRAL - MIN_FACTOR_AGGRESSIVE)
        }.coerceIn(0.0, 1.0)
    }

    fun applyLevel(preferences: Preferences, level: Double) {
        val (minFactor, maxFactor) = factorsForLevel(level)
        val minStored = minFactor.coerceIn(DoubleKey.OApsAIMIIsfFusionMinFactor.min, DoubleKey.OApsAIMIIsfFusionMinFactor.max)
        val maxStored = maxFactor.coerceIn(DoubleKey.OApsAIMIIsfFusionMaxFactor.min, DoubleKey.OApsAIMIIsfFusionMaxFactor.max)
        preferences.put(DoubleKey.OApsAIMIIsfFusionMinFactor, minStored)
        preferences.put(DoubleKey.OApsAIMIIsfFusionMaxFactor, maxOf(maxStored, minStored))
    }

    internal fun factorsForLevel(level: Double): Pair<Double, Double> {
        val t = level.coerceIn(0.0, 1.0)
        val minFactor = when {
            t <= 0.5 -> lerp(MIN_FACTOR_PRUDENT, MIN_FACTOR_NEUTRAL, t * 2.0)
            else -> lerp(MIN_FACTOR_NEUTRAL, MIN_FACTOR_AGGRESSIVE, (t - 0.5) * 2.0)
        }
        val maxFactor = when {
            t <= 0.5 -> lerp(MAX_FACTOR_PRUDENT, MAX_FACTOR_NEUTRAL, t * 2.0)
            else -> lerp(MAX_FACTOR_NEUTRAL, MAX_FACTOR_AGGRESSIVE, (t - 0.5) * 2.0)
        }
        return minFactor to maxFactor
    }

    private fun near(a: Double, b: Double, eps: Double = 0.02) = abs(a - b) <= eps

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
}

/**
 * Maps a 0–1 **storage** prudence level to the SMB tail damping floor.
 * Higher storage level = more prudence = lower floor = stronger damping = LESS tail delivery.
 *
 * UI polarity (Wave1): both Simple sliders use **left = cautious, right = more delivery**.
 * The Compose screen therefore presents `uiLevel = 1 - storageLevel` for this axis so
 * left/right labels match [PkpdCorrectionPrudence] (left cautious, right aggressive).
 * See [PkpdSmbTailDamping] for authoritative damping semantics.
 */
object PkpdTailPrudence {
    fun readLevel(preferences: Preferences): Double =
        readLevelFromDamping(preferences.get(DoubleKey.OApsAIMISmbTailDamping))

    /** UI level: 0 = cautious (less late SMB), 1 = allow more late SMB. */
    fun readUiLevel(preferences: Preferences): Double = (1.0 - readLevel(preferences)).coerceIn(0.0, 1.0)

    internal fun readLevelFromDamping(damping: Double): Double =
        PkpdSmbTailDamping.sliderLevelFromDamping(damping)

    fun applyLevel(preferences: Preferences, level: Double) {
        preferences.put(
            DoubleKey.OApsAIMISmbTailDamping,
            dampingForLevel(level).coerceIn(DoubleKey.OApsAIMISmbTailDamping.min, DoubleKey.OApsAIMISmbTailDamping.max),
        )
    }

    /** Apply from UI level (left cautious → right allow more). */
    fun applyUiLevel(preferences: Preferences, uiLevel: Double) {
        applyLevel(preferences, (1.0 - uiLevel.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0))
    }

    internal fun dampingForLevel(level: Double): Double =
        PkpdSmbTailDamping.dampingForSliderLevel(level)
}

enum class PkpdLearningPace {
    SLOW,
    NORMAL,
    FAST,
}

fun PkpdLearningPace.readFrom(preferences: Preferences): PkpdLearningPace {
    val dia = preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH)
    return when {
        dia <= 0.25 -> PkpdLearningPace.SLOW
        dia <= 0.65 -> PkpdLearningPace.NORMAL
        dia <= 1.2 -> PkpdLearningPace.FAST
        else -> PkpdLearningPace.NORMAL
    }
}

fun PkpdLearningPace.applyTo(preferences: Preferences) {
    when (this) {
        PkpdLearningPace.SLOW -> {
            preferences.put(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH, 0.2)
            preferences.put(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin, 2.0)
        }
        PkpdLearningPace.NORMAL -> {
            preferences.put(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH, 0.5)
            preferences.put(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin, 5.0)
        }
        PkpdLearningPace.FAST -> {
            preferences.put(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH, 1.0)
            preferences.put(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin, 10.0)
        }
    }
}

fun pkpdPrefsSnapshotFrom(preferences: Preferences): PkpdPrefsSnapshot =
    PkpdPrefsSnapshot(
        pkpdEnabled = preferences.get(BooleanKey.OApsAIMIPkpdEnabled),
        initialDiaH = preferences.get(DoubleKey.OApsAIMIPkpdInitialDiaH),
        initialPeakMin = preferences.get(DoubleKey.OApsAIMIPkpdInitialPeakMin),
        boundsDiaMinH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH),
        boundsDiaMaxH = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH),
        boundsPeakMinMin = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin),
        boundsPeakMinMax = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax),
        maxDiaChangePerDayH = preferences.get(DoubleKey.OApsAIMIPkpdMaxDiaChangePerDayH),
        maxPeakChangePerDayMin = preferences.get(DoubleKey.OApsAIMIPkpdMaxPeakChangePerDayMin),
        isfFusionMinFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMinFactor),
        isfFusionMaxFactor = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxFactor),
        isfFusionMaxChangePerTick = preferences.get(DoubleKey.OApsAIMIIsfFusionMaxChangePerTick),
        smbTailThreshold = preferences.get(DoubleKey.OApsAIMISmbTailThreshold),
        smbTailDamping = preferences.get(DoubleKey.OApsAIMISmbTailDamping),
        smbExerciseDamping = preferences.get(DoubleKey.OApsAIMISmbExerciseDamping),
        smbLateFatDamping = preferences.get(DoubleKey.OApsAIMISmbLateFatDamping),
    )

fun applyPkpdPreferenceUpdate(preferences: Preferences, action: AimiAction.PreferenceUpdate): Boolean {
    return when (val value = action.newValue) {
        is Double -> (action.key as? DoublePreferenceKey)?.let {
            preferences.put(it, value)
            true
        } ?: false
        is Int -> (action.key as? IntPreferenceKey)?.let {
            preferences.put(it, value)
            true
        } ?: false
        is Boolean -> (action.key as? BooleanPreferenceKey)?.let {
            preferences.put(it, value)
            true
        } ?: false
        is String -> (action.key as? StringPreferenceKey)?.let {
            preferences.put(it, value)
            true
        } ?: false
        else -> false
    }
}

fun detectPkpdInsulinPreset(preferences: Preferences): PkpdInsulinPreset {
    fun near(a: Double, b: Double, eps: Double = 0.15) = abs(a - b) <= eps
    val bDiaLo = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH)
    val bDiaHi = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH)
    val bPeakLo = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin)
    val bPeakHi = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax)
    val anchorPeak = preferences.get(DoubleKey.OApsAIMIPkpdAnchorPeakMin)
    if (near(bDiaLo, 5.0) && near(bDiaHi, 8.0) && near(bPeakLo, 35.0) && near(anchorPeak, 55.0)) {
        return PkpdInsulinPreset.ULTRA_FAST
    }
    if (near(bDiaLo, 5.0) && near(bDiaHi, 11.0) && near(bPeakLo, 50.0) && near(anchorPeak, 75.0)) {
        return PkpdInsulinPreset.RAPID
    }
    if (near(bDiaLo, 6.0) && near(bDiaHi, 16.0) && near(bPeakLo, 65.0) && near(anchorPeak, 90.0)) {
        return PkpdInsulinPreset.STANDARD
    }
    return PkpdInsulinPreset.CUSTOM
}
