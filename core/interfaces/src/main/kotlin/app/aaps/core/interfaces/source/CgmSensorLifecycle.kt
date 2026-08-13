package app.aaps.core.interfaces.source

import kotlinx.coroutines.flow.StateFlow

/**
 * Which logical sensor a reading / status belongs to when a CGM source supports overlapping a new
 * sensor over the outgoing one (pre-soak / staging).
 *
 * [PRODUCTION] is the sensor that feeds the loop (published glucose). [STAGING] is a second sensor
 * being warmed up / stabilised in parallel that is **never** published to the loop until the user
 * explicitly promotes it. See docs/ONEPLUS_G7_MIGRATION.md.
 */
enum class SensorSlot { PRODUCTION, STAGING }

/**
 * State of the optional STAGING slot, for the dashboard's "new sensor" card.
 *
 * - [ABSENT]   no staging sensor started.
 * - [WARMUP]   staging sensor warming up / (re)connecting (no glucose yet).
 * - [SETTLING] warmed up, collecting glucose but not yet settled long enough to promote.
 * - [READY]    settled ≥ minimum AND producing valid glucose → promotion allowed.
 */
enum class StagingState { ABSENT, WARMUP, SETTLING, READY }

/**
 * Generic, source-agnostic sensor lifecycle info so the dashboard can surface a sensor's
 * "beginning of life" (early, potentially jumpy readings) and end of life (time to start a new one)
 * — without hard-coding any vendor plugin.
 *
 * All times are epoch ms. Fields are null when the source cannot determine them.
 *
 * @param slot which sensor this describes.
 * @param startedAtEpochMs sensor session start.
 * @param expiresAtEpochMs nominal expiry (start + nominal life + grace).
 * @param ageMs now − startedAt.
 * @param remainingMs expiresAt − now.
 * @param earlyLife true while the sensor is in its early-life window (readings may be noisy).
 * @param endOfLife true while the sensor is within its end-of-life window (prompt to start a new one).
 */
data class CgmSensorLifecycle(
    val slot: SensorSlot,
    val startedAtEpochMs: Long?,
    val expiresAtEpochMs: Long?,
    val ageMs: Long?,
    val remainingMs: Long?,
    val earlyLife: Boolean,
    val endOfLife: Boolean,
)

/**
 * Proof that the collect-only staging sensor is really producing data.
 *
 * A pre-soak asks the user to wait many hours on a sensor whose readings are deliberately never
 * published, so without this the slot is unauditable: a working sensor and a dead one look exactly
 * the same on screen.
 *
 * @param validCount valid readings collected since the staging session started.
 * @param lastValueMgdl most recent collected reading (mg/dL), null when none yet.
 * @param lastValueAtEpochMs timestamp of [lastValueMgdl], null when none yet.
 */
data class CgmStagingEvidence(
    val validCount: Int,
    val lastValueMgdl: Double?,
    val lastValueAtEpochMs: Long?,
)

/** Why a promote-staging-to-production request was rejected (for UI feedback + audit). */
enum class PromotionRejectReason {
    STAGING_ABSENT,
    STAGING_NOT_SETTLED,
    STAGING_NO_VALID_GLUCOSE,

    /** Early promotion asked for, but the staging sensor has not sent a reading recently enough. */
    STAGING_NO_RECENT_GLUCOSE,
    LOOP_BUSY,
}

/** Result of a promote-staging-to-production request. */
sealed interface PromotionResult {
    /** Promotion succeeded — the staging sensor now feeds the loop. */
    data object Ok : PromotionResult

    /** Promotion refused; [reason] says why (no state changed). */
    data class Rejected(val reason: PromotionRejectReason) : PromotionResult
}

/**
 * Implemented by a [BgSource] plugin that can overlap a second (staging) sensor over the production
 * one. Extends [CgmWarmupProvider] (the existing production warm-up surface stays source of truth for
 * the hero ring) with lifecycle + staging status the dashboard reads generically:
 * `(activeBgSource as? CgmSensorStatusProvider)`.
 *
 * The STAGING slot is collect-only — it is never published to the loop until [promoteStagingToProduction].
 */
interface CgmSensorStatusProvider : CgmWarmupProvider {

    /** Production sensor lifecycle (early/end of life), or null when unknown / no sensor. */
    val lifecycle: StateFlow<CgmSensorLifecycle?>

    /** Staging warm-up status, or null when there is no staging sensor / nothing to show. */
    val stagingWarmupStatus: StateFlow<CgmWarmupStatus?>

    /** Staging sensor lifecycle, or null when there is no staging sensor. */
    val stagingLifecycle: StateFlow<CgmSensorLifecycle?>

    /** Coarse staging slot state driving the dashboard "new sensor" card + promote affordance. */
    val stagingState: StateFlow<StagingState>

    /** Evidence that the staging sensor is collecting, or null when there is no staging sensor. */
    val stagingEvidence: StateFlow<CgmStagingEvidence?>

    /**
     * Milliseconds left before the staging sensor has settled long enough to be promoted, or null
     * when there is no staging sensor or the wait is already over.
     *
     * The source owns the settle rule, so the UI can show a countdown without knowing it. A UI that
     * kept its own copy of the threshold would drift from the rule that actually gates promotion.
     */
    val stagingSettleRemainingMs: StateFlow<Long?>

    /**
     * Promote the staging sensor to production (the ONLY action that changes the loop's glucose
     * source — safety-critical). Suspends; returns [PromotionResult.Ok] on success or
     * [PromotionResult.Rejected] with the reason (e.g. not settled) without changing any state.
     *
     * @param allowEarly the user knowingly promotes a sensor that has not finished its soak, because
     *   the production sensor stopped early or must be replaced now. The soak gate is then skipped,
     *   but the evidence gates are NOT: the staging sensor must have produced enough valid readings
     *   and one of them must be recent, so the loop can never be handed a silent sensor. Readings
     *   from a sensor promoted this way are less reliable for the first hours.
     */
    suspend fun promoteStagingToProduction(allowEarly: Boolean = false): PromotionResult
}
