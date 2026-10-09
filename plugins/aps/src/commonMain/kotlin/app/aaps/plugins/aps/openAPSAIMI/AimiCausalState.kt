package app.aaps.plugins.aps.openAPSAIMI

/**
 * Causal state for one `DetermineBasalAIMI2` instance.
 *
 * These fields persist between ticks: hysteresis flags, hold/dwell timers,
 * memories and learner state. They are the "engine state" from the annex-8
 * contract: what tick N+1 needs to know about tick N.
 *
 * Fields moved here from `DetermineBasalAIMI2` are pure in-memory causal state.
 * The four `LegacyPrebolusMemory`-backed properties (`internalLastSmbMillis`,
 * `internalLastLegacyPrebolusMillis`, `pendingLegacyPrebolusUnit`,
 * `pendingLegacyPrebolusExpiry`) stay in the owning class: their custom
 * getters/setters read/write `preferences`, which is a shell concern, not
 * engine state. The mirror fields below (`lastSmbMillis`,
 * `lastLegacyPrebolusMillis`, `pendingLegacyPrebolusUnit`,
 * `pendingLegacyPrebolusExpiry`) are the transitional copy: the tick copies the
 * current values in via [syncFromLegacyPrebolus] so the replay harness can
 * observe and compare them. CACHE, EFFECT and tick-local WORKING fields also
 * stay out.
 *
 * See `_docs/kmp/annex-8-state-replay-and-extraction-contract.md`, lot E3.
 */
class AimiCausalState {
    // Hysteresis flags.
    var lateFatRiseFlag: Boolean = false
    var highBgOverrideUsed = false

    // Hypo hold/dwell timers (epoch ms).
    var lastHypoBlockAt: Long = 0L
    var hypoClearCandidateSince: Long? = null
    var lastHypoBelow70At: Long = 0L
    var lastBgRiseFastNightMs: Long = 0L

    // Hyper dwell timer (epoch ms).
    var hyperDwellAboveHighBgSinceMs: Long = 0L

    // Cycle belief memory.
    var lastWCycleBelief: WCycleBelief? = null

    // Rise-floor budget.
    var riseFloorSpentU: Double = 0.0
    var lastRiseFloorContributionMs: Long = 0L

    // Effort / physio / UAM memories.
    var lastEffortMemory = EffortActivityBelief.Memory()
    var lastPhysioLatentState: PhysioLatentState? = null
    var lastUamHypothesisState: UamHypothesisState? = null

    // Slow-carb budget.
    var slowCarbBudgetWindowMs: Long = 0L
    var slowCarbBudgetDeliveredU: Double = 0.0

    // Post-hypo / autodrive ordinal state.
    var lastPostHypoOrdinal: Int = 0
    var lastAutodriveState: AutodriveState = AutodriveState.IDLE

    // Zero-basal accumulator.
    var zeroBasalAccumulatedMinutes: Int = 0

    // Learner state: grouped here, logic unchanged.
    var adaptiveMult: Double = 1.0

    // Legacy prebolus latch, mirrored from the `LegacyPrebolusMemory`-backed
    // properties on the owning class (transitional, see [syncFromLegacyPrebolus]).
    // Key mapping: `AimiLongKey.LastPrebolusTime`,
    // `AimiLongKey.LastLegacyPrebolusTime`, `AimiLongKey.PendingLegacyPrebolusUnitMilli`
    // (stored as milli-units, mirrored here as units), `AimiLongKey.PendingLegacyPrebolusExpiry`.
    var lastSmbMillis: Long = 0L
    var lastLegacyPrebolusMillis: Long = 0L
    var pendingLegacyPrebolusUnit: Float = 0.0f
    var pendingLegacyPrebolusExpiry: Long = 0L

    // Meal advisor estimate, mirrored from preferences (transitional).
    // Key mapping: `BooleanKey.OApsAIMIMealAdvisorTrigger`,
    // `DoubleKey.OApsAIMILastEstimatedCarbTime` (epoch ms),
    // `DoubleKey.OApsAIMILastEstimatedCarbs` (grams).
    var mealAdvisorTrigger: Boolean = false
    var lastEstimatedCarbTimeMs: Long = 0L
    var lastEstimatedCarbsG: Double = 0.0

    /**
     * Transitional copy of the `LegacyPrebolusMemory`-backed state.
     *
     * The owning class keeps the custom getters/setters that read/write
     * `preferences` (shell concern). The tick calls this at the start with the
     * values read from those properties, so the replay harness can observe the
     * causal state without touching preferences. Once the shell feeds these
     * values through the snapshot, this method goes away.
     */
    fun syncFromLegacyPrebolus(
        lastSmbMillis: Long,
        lastLegacyPrebolusMillis: Long,
        pendingUnit: Float,
        pendingExpiry: Long,
    ) {
        this.lastSmbMillis = lastSmbMillis
        this.lastLegacyPrebolusMillis = lastLegacyPrebolusMillis
        this.pendingLegacyPrebolusUnit = pendingUnit
        this.pendingLegacyPrebolusExpiry = pendingExpiry
    }

    /**
     * Snapshot of the causal state after a tick.
     *
     * Returns a copy so the replay harness can compare `stateAfter`
     * without holding a reference to the live mutable state.
     */
    fun snapshot(): AimiCausalState {
        val copy = AimiCausalState()
        copy.lateFatRiseFlag = lateFatRiseFlag
        copy.highBgOverrideUsed = highBgOverrideUsed
        copy.lastHypoBlockAt = lastHypoBlockAt
        copy.hypoClearCandidateSince = hypoClearCandidateSince
        copy.lastHypoBelow70At = lastHypoBelow70At
        copy.lastBgRiseFastNightMs = lastBgRiseFastNightMs
        copy.hyperDwellAboveHighBgSinceMs = hyperDwellAboveHighBgSinceMs
        copy.lastWCycleBelief = lastWCycleBelief
        copy.riseFloorSpentU = riseFloorSpentU
        copy.lastRiseFloorContributionMs = lastRiseFloorContributionMs
        copy.lastEffortMemory = lastEffortMemory
        copy.lastPhysioLatentState = lastPhysioLatentState
        copy.lastUamHypothesisState = lastUamHypothesisState
        copy.slowCarbBudgetWindowMs = slowCarbBudgetWindowMs
        copy.slowCarbBudgetDeliveredU = slowCarbBudgetDeliveredU
        copy.lastPostHypoOrdinal = lastPostHypoOrdinal
        copy.lastAutodriveState = lastAutodriveState
        copy.zeroBasalAccumulatedMinutes = zeroBasalAccumulatedMinutes
        copy.adaptiveMult = adaptiveMult
        copy.lastSmbMillis = lastSmbMillis
        copy.lastLegacyPrebolusMillis = lastLegacyPrebolusMillis
        copy.pendingLegacyPrebolusUnit = pendingLegacyPrebolusUnit
        copy.pendingLegacyPrebolusExpiry = pendingLegacyPrebolusExpiry
        copy.mealAdvisorTrigger = mealAdvisorTrigger
        copy.lastEstimatedCarbTimeMs = lastEstimatedCarbTimeMs
        copy.lastEstimatedCarbsG = lastEstimatedCarbsG
        return copy
    }
}
