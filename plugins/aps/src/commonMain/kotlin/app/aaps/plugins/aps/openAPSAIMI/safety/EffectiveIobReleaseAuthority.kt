package app.aaps.plugins.aps.openAPSAIMI.safety

import app.aaps.core.data.format.NumberFormat
import app.aaps.core.data.format.NumberFormatPlatform

/**
 * AIMI-local authority governing the **effective-IOB release** of the maxIOB production gate.
 *
 * Problem it lifts: the maxIOB gate compares the **ledger** IOB (profile DIA) against maxIOB. For fast insulin
 * the learned/effective DIA is shorter, so the ledger **over-estimates** remaining insulin and the gate blocks
 * high-corrections that the (effective) prediction curves already want — the two halves of the decision disagree.
 *
 * This authority lets the gate compare against a **partial release** of the ledger toward the (lower) effective
 * IOB, but **retracts the release toward the ledger the moment hypo evidence appears**. It is closed-loop and
 * self-limiting: if a release causes a hypo, the hypo IS the retraction signal (θ→0) — the system pays for a
 * mistake at most once, then reverts, then a cooldown via the recent-BG floor.
 *
 * Invariants:
 * - **Release-only**: never raises the gate IOB above the ledger (slow-insulin caution is untouched here).
 * - **maxIOB is never changed** — only which IOB reading is compared against it.
 * - **[THETA_MAX] < 1** keeps a residual safety margin even at best.
 * - Fail-safe: disabled / effective unavailable / effective ≥ ledger / any hypo signal → θ 0, iobForGate = ledger.
 *
 * Sibling of `PostHypoDeliveryAuthority` (not in this lot); reuses [Input.postHypoAuthorityActive].
 */
object EffectiveIobReleaseAuthority {

    const val LOG_PREFIX = "IOB_RELEASE"

    /** Never release more than this fraction of the (ledger − effective) gap. */
    const val THETA_MAX = 0.5

    /** At/below this recent glucose floor (mg/dL), no release at all — a hypo just happened or is close. */
    const val HYPO_FLOOR_MGDL = 75.0

    /** Full [THETA_MAX] only once the recent glucose floor is at/above this margin (mg/dL). */
    const val MARGIN_UPPER_MGDL = 105.0

    /** Post-hypo state (ReboundSuspected / MealConfirmed) shrinks the release to this fraction. */
    const val POST_HYPO_STATE_DAMP = 0.3

    data class Input(
        val enabled: Boolean,
        val iobLedgerU: Double,
        /** Current IOB (U) on effective (learned) kinetics; null → feature unavailable this tick. */
        val iobEffectiveU: Double?,
        val postHypoAuthorityActive: Boolean,
        /** 0 None, 1 ReboundSuspected, 2 MealConfirmed. */
        val postHypoStateOrdinal: Int,
        /** UAM post-hypo probability if available; null → ignored. */
        val postHypoProb: Double?,
        /** Lowest glucose (mg/dL) over the recent post-hypo lookback window. */
        val minBgRecentMgdl: Double,
    )

    data class Decision(
        val theta: Double,
        val iobLedgerU: Double,
        val iobEffectiveU: Double?,
        val iobForGateU: Double,
        val releasedU: Double,
        val reasonTag: String,
    )

    fun evaluate(input: Input): Decision {
        val ledger = input.iobLedgerU
        fun noRelease(tag: String) = Decision(0.0, ledger, input.iobEffectiveU, ledger, 0.0, tag)

        if (!input.enabled) return noRelease("disabled")
        val eff = input.iobEffectiveU ?: return noRelease("effective_unavailable")
        if (!eff.isFinite()) return noRelease("effective_nonfinite")
        val gap = ledger - eff
        if (gap <= 0.0) return noRelease("no_gap_effective_ge_ledger") // slow insulin — untouched

        // Hypo governance: any active/recent hypo evidence retracts the release fully.
        if (input.postHypoAuthorityActive) return noRelease("post_hypo_active")
        if (input.minBgRecentMgdl < HYPO_FLOOR_MGDL) return noRelease("recent_hypo_floor")

        // Ramp: the further the recent floor is from the hypo threshold, the more we open.
        val ramp = ((input.minBgRecentMgdl - HYPO_FLOOR_MGDL) / (MARGIN_UPPER_MGDL - HYPO_FLOOR_MGDL))
            .coerceIn(0.0, 1.0)
        var theta = THETA_MAX * ramp
        if (input.postHypoStateOrdinal >= 1) theta *= POST_HYPO_STATE_DAMP
        val prob = input.postHypoProb
        if (prob != null && prob.isFinite() && prob > 0.5) {
            // prob 0.5 → factor 1.0, prob 1.0 → factor 0.0
            theta *= (1.0 - (prob - 0.5) * 2.0).coerceIn(0.0, 1.0)
        }
        theta = theta.coerceIn(0.0, THETA_MAX)
        if (theta <= 0.0) return noRelease("throttled_to_zero")

        val released = theta * gap
        return Decision(
            theta = theta,
            iobLedgerU = ledger,
            iobEffectiveU = eff,
            iobForGateU = ledger - released,
            releasedU = released,
            reasonTag = "release",
        )
    }

    fun formatLogLine(d: Decision): String =
        buildString {
            append(LOG_PREFIX)
            append(": theta=").append(fmt2(d.theta))
            append(" ledger=").append(fmt2(d.iobLedgerU))
            append(" eff=").append(d.iobEffectiveU?.let { fmt2(it) } ?: "na")
            append(" gate=").append(fmt2(d.iobForGateU))
            append(" released=").append(fmt2(d.releasedU))
            append(" (").append(d.reasonTag).append(")")
        }

    private fun fmt2(value: Double): String =
        NumberFormat.DECIMAL_2.format(value, NumberFormatPlatform.SEPARATOR_DOT)
}
