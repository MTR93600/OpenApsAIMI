package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences

/**
 * Shared tail-damping scale for loop math, the simplified PK/PD settings UI, and the advisors.
 *
 * **Authoritative semantics** (see [SmbDamping.damp] and `SmbDampingTest`): the stored preference
 * `OApsAIMISmbTailDamping` is a **multiplicative floor** applied to SMB when tail IOB is high —
 * `tailMult = floor + (1 − floor) × relief`, so `tailMult ∈ [floor, 1.0]`.
 *
 * → **Lower stored value = stronger damping** (e.g. 0.70 = up to −30% SMB; 1.0 = guard disabled).
 *
 * Any consumer reasoning the other way around ("higher = more damping") is inverted and will
 * weaken the hypo guard when it intends to strengthen it.
 */
object PkpdSmbTailDamping {
    /** Mildest damping of the slider stops (−8% SMB max at tail). */
    const val DAMPING_LIGHT = 0.92

    /** Default damping (−15% SMB max at tail). */
    const val DAMPING_NEUTRAL = 0.85

    /** Strongest damping of the slider stops (−30% SMB max at tail). */
    const val DAMPING_STRONG = 0.70

    /** Pre-simplified UI stored tail damping on 0.0–1.0 scale; values below this are legacy. */
    const val LEGACY_NEUTRAL_CUTOFF = 0.55

    /**
     * Control Center Stability family writeback ladder (index 0 = Smoother → 4 = More reactive).
     *
     * Must stay inside [[DAMPING_STRONG], [DAMPING_LIGHT]] so [effectiveStoredValue] does not rewrite
     * the value to neutral. Polarity matches PKPD semantics: **lower floor = stronger damping =
     * smoother / less aggressive tail SMB**.
     *
     * Historical bug: Stability wrote `0.20…0.80`; levels ≤0.55 were silently remapped to 0.85,
     * so "move Stability left toward safety" did not strengthen tail damping at runtime.
     */
    val STABILITY_FAMILY_FLOOR_LADDER: List<Double> = listOf(
        DAMPING_STRONG, // 0.70 — Very smooth
        0.76,
        0.81, // near-balanced (maps to five-step index 2 when scored on this band)
        0.86,
        DAMPING_LIGHT, // 0.92 — Very responsive
    )

    fun effectiveStoredValue(stored: Double): Double =
        if (stored <= LEGACY_NEUTRAL_CUTOFF) DAMPING_NEUTRAL else stored

    /**
     * Maps an effective tail-damping floor onto Control Center Stability score polarity
     * (0 = smoother, 1 = more reactive) using the authoritative PKPD band.
     */
    fun stabilityFamilyScore(storedOrEffective: Double): Float {
        val effective = effectiveStoredValue(storedOrEffective)
        val span = DAMPING_LIGHT - DAMPING_STRONG
        if (span <= 0.0) return 0.5f
        return ((effective - DAMPING_STRONG) / span).toFloat().coerceIn(0f, 1f)
    }

    /**
     * One-shot Control Center / settings hygiene: persist the runtime-effective floor when the
     * stored preference is still in the legacy zone (≤ [LEGACY_NEUTRAL_CUTOFF]). Returns true if
     * a write occurred so the UI can refresh.
     */
    fun migrateLegacyStoredPreference(preferences: Preferences): Boolean {
        val stored = preferences.get(DoubleKey.OApsAIMISmbTailDamping)
        if (stored > LEGACY_NEUTRAL_CUTOFF) return false
        val effective = effectiveStoredValue(stored)
        if (stored == effective) return false
        preferences.put(DoubleKey.OApsAIMISmbTailDamping, effective)
        return true
    }

    /**
     * Clamp for advisor/tuning targets: keeps proposals inside the slider band so the guard can
     * never be silently disabled (1.0) nor pushed into the legacy zone (≤ [LEGACY_NEUTRAL_CUTOFF])
     * where [effectiveStoredValue] would rewrite the runtime value to [DAMPING_NEUTRAL].
     */
    fun clampForAdvisor(value: Double): Double = value.coerceIn(DAMPING_STRONG, DAMPING_LIGHT)

    fun dampingForSliderLevel(level: Double): Double {
        val t = level.coerceIn(0.0, 1.0)
        return when {
            t <= 0.5 -> lerp(DAMPING_LIGHT, DAMPING_NEUTRAL, t * 2.0)
            else -> lerp(DAMPING_NEUTRAL, DAMPING_STRONG, (t - 0.5) * 2.0)
        }
    }

    fun sliderLevelFromDamping(damping: Double): Double {
        if (damping <= LEGACY_NEUTRAL_CUTOFF) return 0.5
        return when {
            damping >= DAMPING_LIGHT - 0.02 -> 0.0
            damping <= DAMPING_STRONG + 0.02 -> 1.0
            damping >= DAMPING_NEUTRAL ->
                0.5 * (DAMPING_LIGHT - damping) / (DAMPING_LIGHT - DAMPING_NEUTRAL)
            else ->
                0.5 + 0.5 * (DAMPING_NEUTRAL - damping) / (DAMPING_NEUTRAL - DAMPING_STRONG)
        }.coerceIn(0.0, 1.0)
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t
}
