package app.aaps.plugins.aps.openAPSAIMI

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
     * Time the cache entry that was used was written.
     *
     * It used to be `bucketStart + glucose`, a time and a glucose summed into one scalar, and
     * [lastAgeMs] was derived from its 30-minute bucket. It is now the exact write time of the
     * sample, so [lastAgeMs] is exact too.
     */
    @Volatile
    var lastCacheKey: Long? = null
        private set

    /** Glucose the used value was computed for, when it is known. */
    @Volatile
    var lastCacheGlucoseMgdl: Long? = null
        private set

    /**
     * Exit path of the last `OpenAPSAIMIPlugin.calculateVariableIsf` pass:
     * `OFF`, `DB`, `GLUC` or `CALC`.
     *
     * Only `CALC` writes the cache. Measured on a 24-hour package of 1412 cycles, the loop took the
     * `DB` short cut on nearly every cycle, so `CALC` appeared on about **0 %** of the ticks and the
     * cache was never refreshed: 93.9 % of the cycles served a stale value, median age 45.7 min,
     * max 241.9 min. After the fix the background refresh does not take the short cut, so this
     * should read `CALC` on close to **100 %** of the ticks.
     */
    @Volatile
    var lastCalcPath: String? = null
        private set

    /** How many samples the dynamic ISF store held at the end of the last pass. */
    @Volatile
    var lastCacheSize: Int? = null
        private set

    /** Records the exit path of one `calculateVariableIsf` pass and the store size it left behind. */
    fun recordCalcPath(path: String, cacheSize: Int) {
        lastCalcPath = path
        lastCacheSize = cacheSize
    }

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
     * Verdict of the stress-ISF-floor signature for the current tick, and what it would cost.
     *
     * The sensitivity is commanded in `OpenAPSAIMIPlugin`, but the decision snapshot is assembled in
     * `DetermineBasalAIMI2`, which never sees that value. This object is already the side channel the
     * ISF chain uses for exactly that hand-over (see [lastProfileStaticMgdl] and
     * [lastPhysioIsfFactor]), so the three fields below travel the same way rather than adding a field
     * to `OapsProfileAimi`, which is a dosing input and not a place for observation.
     *
     * Written on every tick, armed or not: the gesture has to be measurable before it is armed.
     * Exported as `baseline_state.stress_isf_floor_*`. Never read by a dose calculation.
     */
    @Volatile var lastStressIsfFloorActive: Boolean? = null; private set
    @Volatile var lastStressIsfFloorReason: String? = null; private set

    /**
     * Sensitivity that **would** be commanded with the floor at 1.0 x profile, mg/dL per U.
     *
     * `null` unless the signature is active and that value really differs from the commanded one, so a
     * missing field means "nothing to see", never "zero".
     */
    @Volatile var lastStressIsfFloorIsfMgdl: Double? = null; private set

    fun recordStressIsfFloor(active: Boolean, reason: String, flooredIsfMgdl: Double?) {
        lastStressIsfFloorActive = active
        lastStressIsfFloorReason = reason
        lastStressIsfFloorIsfMgdl = flooredIsfMgdl?.takeIf { it.isFinite() }
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
