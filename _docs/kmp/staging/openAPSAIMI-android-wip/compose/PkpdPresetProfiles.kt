package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Insulin action presets for the guided PK/PD screen.
 * Values are clamped to each [DoubleKey] min/max before storage.
 */
enum class PkpdInsulinPreset {
    ULTRA_FAST,
    RAPID,
    STANDARD,
    CUSTOM,
}

private fun Preferences.putClamped(key: DoubleKey, value: Double) {
    put(key, value.coerceIn(key.min, key.max))
}

/**
 * Writes **insulin kinetics only**: the initial PK/PD parameters, the learning bounds and the
 * anchors. It then **reclamps** the learned state into the new envelope (preserves learning;
 * does not wipe to initial).
 *
 * It does **not** touch settings the user owns on other screens: the learning pace
 * ([PkpdLearningPace]), the correction prudence slider ([PkpdCorrectionPrudence]) and the
 * tail prudence slider ([PkpdTailPrudence]). Picking an insulin type must not undo choices
 * that have nothing to do with the insulin. The first-run wizard seeds those defaults instead.
 */
fun applyPkpdInsulinPreset(preferences: Preferences, preset: PkpdInsulinPreset) {
    if (preset == PkpdInsulinPreset.CUSTOM) return
    when (preset) {
        PkpdInsulinPreset.ULTRA_FAST -> {
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMinH, 5.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH, 8.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin, 35.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax, 95.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialDiaH, 6.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialPeakMin, 55.0)
            // Anchor = initial DIA. An anchor of 4.0 h sat below the 5.0 h floor and pulled the
            // learned DIA onto that floor for ever.
            preferences.putClamped(DoubleKey.OApsAIMIPkpdAnchorDiaH, 6.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdAnchorPeakMin, 55.0)
        }
        PkpdInsulinPreset.RAPID -> {
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMinH, 5.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH, 11.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin, 50.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax, 130.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialDiaH, 6.5)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialPeakMin, 75.0)
            // Anchor = initial DIA, and inside the [5, 11] bounds.
            preferences.putClamped(DoubleKey.OApsAIMIPkpdAnchorDiaH, 6.5)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdAnchorPeakMin, 75.0)
        }
        PkpdInsulinPreset.STANDARD -> {
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMinH, 6.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH, 16.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin, 65.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax, 200.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialDiaH, 8.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdInitialPeakMin, 90.0)
            // Anchor = initial DIA, and inside the [6, 16] bounds.
            preferences.putClamped(DoubleKey.OApsAIMIPkpdAnchorDiaH, 8.0)
            preferences.putClamped(DoubleKey.OApsAIMIPkpdAnchorPeakMin, 90.0)
        }
        PkpdInsulinPreset.CUSTOM -> Unit
    }
    reclampPkpdLearnedStateToBounds(preferences)
}

/**
 * Reclamps existing learned DIA/peak into current bounds (preserves learning).
 */
fun reclampPkpdLearnedStateToBounds(preferences: Preferences) {
    val bDiaLo = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH)
    val bDiaHi = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH)
    val bPeakLo = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin)
    val bPeakHi = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax)
    val dia = preferences.get(DoubleKey.OApsAIMIPkpdStateDiaH).coerceIn(bDiaLo, bDiaHi)
    val peak = preferences.get(DoubleKey.OApsAIMIPkpdStatePeakMin).coerceIn(bPeakLo, bPeakHi)
    preferences.putClamped(DoubleKey.OApsAIMIPkpdStateDiaH, dia)
    preferences.putClamped(DoubleKey.OApsAIMIPkpdStatePeakMin, peak)
}

/**
 * Explicit reset: learned state → initial × bounds (wipes adaptation).
 * Prefer [reclampPkpdLearnedStateToBounds] on routine preset changes.
 */
fun resetPkpdLearnedStateToInitial(preferences: Preferences) {
    val diaInit = preferences.get(DoubleKey.OApsAIMIPkpdInitialDiaH)
    val peakInit = preferences.get(DoubleKey.OApsAIMIPkpdInitialPeakMin)
    val bDiaLo = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMinH)
    val bDiaHi = preferences.get(DoubleKey.OApsAIMIPkpdBoundsDiaMaxH)
    val bPeakLo = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMin)
    val bPeakHi = preferences.get(DoubleKey.OApsAIMIPkpdBoundsPeakMinMax)
    preferences.putClamped(DoubleKey.OApsAIMIPkpdStateDiaH, diaInit.coerceIn(bDiaLo, bDiaHi))
    preferences.putClamped(DoubleKey.OApsAIMIPkpdStatePeakMin, peakInit.coerceIn(bPeakLo, bPeakHi))
}

/**
 * @deprecated Use [reclampPkpdLearnedStateToBounds] or [resetPkpdLearnedStateToInitial].
 * Kept as reset alias for any leftover call sites.
 */
@Deprecated(
    message = "Use reclampPkpdLearnedStateToBounds (preserve) or resetPkpdLearnedStateToInitial (wipe)",
    replaceWith = ReplaceWith("resetPkpdLearnedStateToInitial(preferences)"),
)
fun syncPkpdLearnedStateToBounds(preferences: Preferences) {
    resetPkpdLearnedStateToInitial(preferences)
}
