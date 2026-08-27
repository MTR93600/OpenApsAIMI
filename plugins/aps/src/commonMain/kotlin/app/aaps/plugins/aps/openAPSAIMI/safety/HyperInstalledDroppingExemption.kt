package app.aaps.plugins.aps.openAPSAIMI.safety

/**
 * Lever 1 — hyper-installed dropping exemption.
 *
 * Terminal safety historically hard-zeros SMB on `droppingFast` / `droppingFastAtHigh` /
 * `droppingVeryFast` whenever the 5‑min delta is negative at high BG. On an undeclared-meal
 * plateau (BG 200–248 oscillating), each redesignation of the sawtooth is treated as a
 * dangerous freefall even though the patient sits at ~2–3× target — so every down-tick
 * becomes SMB=0 while up-ticks under-deliver.
 *
 * This helper decides when those *rate-only* dropping conditions may be bypassed. It is
 * intentionally projection-gated (10‑min linear) and never touches hypoGuard / prediction /
 * below-target conditions. Fail-safe: caller must gate with the preference; this object is
 * pure and defaults to "no bypass" when inputs are non-finite.
 */
object HyperInstalledDroppingExemption {

    /** Absolute BG floor (mg/dL) — matches historical `isDroppingFastAtHigh` threshold. */
    const val MIN_BG_MGDL = 180.0

    /** How far above target BG must sit to count as "hyper-installed". */
    const val TARGET_MARGIN_MGDL = 45.0

    /** Deep-hyper path for undeclared meals with no COB / meal-clock flags. */
    const val DEEP_HYPER_BG_MGDL = 200.0

    /** COB grams that corroborate meal context for the exemption. */
    const val MEAL_COB_G = 5.0

    /** 10‑min linear projection must stay this far above the hypo threshold. */
    const val PROJECTED_HYPO_BUFFER_MGDL = 40.0

    /**
     * Absolute freefall floor (mg/dL per 5 min). Below this, never bypass — even in deep hyper.
     * Episode 25/07 saw Δ down to ≈−9; keep headroom under extreme sensor/IOB crashes.
     */
    const val FREEFALL_DELTA_MGDL5M = -15.0

    data class Input(
        val enabled: Boolean,
        val bgMgdl: Double,
        val targetBgMgdl: Double,
        val deltaMgdl5m: Double,
        val hypoThresholdMgdl: Double,
        val mealContextActive: Boolean,
        val cobG: Double = 0.0,
    )

    /**
     * @return true when the dropping-fast *family* may be bypassed for this tick.
     */
    fun shouldBypass(input: Input): Boolean {
        if (!input.enabled) return false
        val bg = input.bgMgdl
        val target = input.targetBgMgdl
        val delta = input.deltaMgdl5m
        val hypo = input.hypoThresholdMgdl
        if (!bg.isFinite() || !target.isFinite() || !delta.isFinite() || !hypo.isFinite()) return false
        if (bg <= MIN_BG_MGDL) return false
        if (bg < target + TARGET_MARGIN_MGDL) return false
        if (delta >= 0.0) return false // rising / flat — classic rise-fallback handles those
        if (delta < FREEFALL_DELTA_MGDL5M) return false

        val mealish = input.mealContextActive || input.cobG >= MEAL_COB_G
        val deepHyper = bg >= DEEP_HYPER_BG_MGDL
        if (!mealish && !deepHyper) return false

        // Two 5‑min steps ≈ 10 min linear projection of the current rate.
        val projected10 = bg + 2.0 * delta
        if (projected10 < hypo + PROJECTED_HYPO_BUFFER_MGDL) return false

        return true
    }
}
