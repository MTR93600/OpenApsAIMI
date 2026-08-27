package app.aaps.plugins.aps.openAPSAIMI

import kotlin.concurrent.Volatile

/**
 * Diagnostic-only side channel that records **which sensitivity source** the last
 * `OpenAPSAIMIPlugin.getIsfMgdl` call actually returned.
 *
 * ## Why this exists
 *
 * `getIsfMgdl` returns a bare `Double?`, and a `null` makes `ProfileSealed.getIsfMgdl` fall back to
 * the static profile ISF — a different quantity, chosen silently. The cached branch can also return
 * a value computed for an earlier tick, because the recomputation runs on a background scope. From
 * a support package it was impossible to tell which of the three happened, so a value moving by a
 * factor of ten between two consecutive ticks could not be explained.
 *
 * This object makes the choice observable. It is written on every call and read once per tick when
 * the decision snapshot is assembled. It never feeds a dose calculation.
 *
 * See `docs/adr/0003-dynisf-cache-read-path.md`.
 */
object IsfSourceTelemetry {

    /** No call recorded yet for this process. */
    const val SOURCE_NONE = "NONE"

    /** Cache hit whose entry was written for the current tick. */
    const val SOURCE_DYNAMIC_FRESH = "DYNAMIC_FRESH"

    /** Cache hit whose entry is older than one CGM interval. */
    const val SOURCE_DYNAMIC_STALE = "DYNAMIC_STALE"

    /** No usable cache entry: the caller falls back to the static profile ISF. */
    const val SOURCE_PROFILE_FALLBACK = "PROFILE_FALLBACK"

    /** Profile is not an effective profile switch, so no dynamic value can be produced. */
    const val SOURCE_NO_EPS = "NO_EPS"

    /** Age (ms) above which a cached dynamic value is reported as stale. */
    const val STALE_AFTER_MS: Long = 5 * 60 * 1000L

    @Volatile
    var lastSource: String = SOURCE_NONE
        private set

    /** Dynamic value before the effective-profile multiplier, or `null` when none was available. */
    @Volatile
    var lastDynamicMgdl: Double? = null
        private set

    /** Age (ms) of the cache entry that was used, or `null` when no cache entry was used. */
    @Volatile
    var lastAgeMs: Long? = null
        private set

    /**
     * Key of the cache entry that was used.
     *
     * [lastAgeMs] is derived from the key's 30-minute bucket, so it cannot tell two entries of the
     * same bucket apart — and the read rule selects **within** a bucket by glucose. Production data
     * showed 4 sensitivity jumps out of 20 where the age kept rising while the value changed, which
     * is exactly that case and was unreadable without the key.
     */
    @Volatile
    var lastCacheKey: Long? = null
        private set

    /** Glucose embedded in the cache key, i.e. the reading the used value was computed for. */
    @Volatile
    var lastCacheGlucoseMgdl: Long? = null
        private set

    /**
     * User profile ISF block for the current time of day, captured where the profile is in hand.
     * `Profile.getProfileIsfMgdl()` is not suspend, but `ProfileFunction.getProfile()` is, so the
     * dosing path cannot read it directly.
     */
    @Volatile
    var lastProfileStaticMgdl: Double? = null
        private set

    fun record(
        source: String,
        dynamicMgdl: Double?,
        ageMs: Long?,
        cacheKey: Long? = null,
        cacheGlucoseMgdl: Long? = null,
    ) {
        lastSource = source
        lastDynamicMgdl = dynamicMgdl
        lastAgeMs = ageMs
        lastCacheKey = cacheKey
        lastCacheGlucoseMgdl = cacheGlucoseMgdl
    }

    fun recordProfileStatic(profileStaticMgdl: Double?) {
        lastProfileStaticMgdl = profileStaticMgdl
    }

    /**
     * Intermediate terms of one `calculateVariableIsf` pass.
     *
     * Production data showed that the BG-dependent terms of the formula explain only 18 % of the
     * variation of the sensitivity actually commanded (R² = 0.18 over 285 ticks), and that no other
     * intended input explains much either. More than half of the movement comes from the estimation
     * chain itself, and it cannot be attributed without seeing the terms separately.
     */
    @Volatile var lastKalmanFastIsf: Double? = null; private set
    @Volatile var lastIsfAdjEngine: Double? = null; private set
    @Volatile var lastFusedSlowIsf: Double? = null; private set
    @Volatile var lastTrustFast: Double? = null; private set
    @Volatile var lastDynamicFactor: Double? = null; private set
    @Volatile var lastTrajectoryMultiplier: Double? = null; private set

    /**
     * Physiological ISF factor of the tick, bounds [0.85, 1.15].
     *
     * Exported because the fix that made it apply once instead of twice could not be verified from
     * a support package: the only comparable field, `dynamic_isf.final_value_mgdl`, turned out to
     * carry the PKPD fused ISF, an unrelated quantity.
     */
    @Volatile var lastPhysioIsfFactor: Double? = null; private set

    fun recordPhysioFactor(factor: Double?) {
        lastPhysioIsfFactor = factor
    }

    /**
     * Lower bound of the shadow exit clamp, as a fraction of the profile ISF.
     *
     * Today the only relative bound in the whole chain lives inside `DynIsfTrajectoryTuning`
     * (`[0.58, 1.42] × profile`), behind six gates that skip it. Measured on 2026-08-06: **94 of 285
     * ticks (33 %) fell outside it**, extremes ×0.32 and ×2.15. This is the same shape as
     * `BasalTerminalInvariants` with `meal_mode_exempt` — a correct invariant written in a branch
     * instead of at the exit.
     *
     * A wider band than the trajectory layer's is used on purpose: the point is to keep the
     * commanded sensitivity inside the domain where the profile still means something, not to
     * reproduce a tuning decision. See `docs/adr/0008-isf-decision-architecture.md`.
     */
    const val PROFILE_RELATIVE_LOW: Double = 0.5

    /** Upper bound of the shadow exit clamp, as a fraction of the profile ISF. */
    const val PROFILE_RELATIVE_HIGH: Double = 2.0

    /** What the commanded sensitivity would be with an exit-level relative bound. Shadow: never applied. */
    @Volatile var lastProfileRelativeShadowMgdl: Double? = null; private set

    /** True when the shadow bound would have changed the value. */
    @Volatile var lastProfileRelativeBoundHit: Boolean? = null; private set

    /**
     * Records what an unconditional exit clamp would produce, without applying it.
     *
     * @param blendedMgdl the value the chain actually produces
     * @param profileIsfMgdl the static profile block for this time of day
     */
    fun recordProfileRelativeShadow(blendedMgdl: Double, profileIsfMgdl: Double) {
        if (!blendedMgdl.isFinite() || !profileIsfMgdl.isFinite() || profileIsfMgdl <= 0.0) {
            lastProfileRelativeShadowMgdl = null
            lastProfileRelativeBoundHit = null
            return
        }
        val bounded = blendedMgdl.coerceIn(
            profileIsfMgdl * PROFILE_RELATIVE_LOW,
            profileIsfMgdl * PROFILE_RELATIVE_HIGH,
        )
        lastProfileRelativeShadowMgdl = bounded
        lastProfileRelativeBoundHit = bounded != blendedMgdl
    }

    fun recordComponents(
        kalmanFastIsf: Double?,
        isfAdjEngine: Double?,
        fusedSlowIsf: Double?,
        trustFast: Double?,
        dynamicFactor: Double?,
        trajectoryMultiplier: Double?,
    ) {
        lastKalmanFastIsf = kalmanFastIsf
        lastIsfAdjEngine = isfAdjEngine
        lastFusedSlowIsf = fusedSlowIsf
        lastTrustFast = trustFast
        lastDynamicFactor = dynamicFactor
        lastTrajectoryMultiplier = trajectoryMultiplier
    }

    fun reset() {
        lastSource = SOURCE_NONE
        lastDynamicMgdl = null
        lastAgeMs = null
        lastCacheKey = null
        lastCacheGlucoseMgdl = null
        lastProfileStaticMgdl = null
        lastPhysioIsfFactor = null
    }
}
