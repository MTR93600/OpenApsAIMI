package app.aaps.plugins.aps.openAPSAIMI.safety

import kotlin.math.min

/**
 * One ceiling for every automatic SMB restore: the dose may not end above what the guards let
 * through. A pre-guard model/floor is evidence of demand, not permission to undo PK/PD safety.
 *
 * ## Why this exists
 *
 * Red Carpet and the RBT/HTR finalize floor put back what the guards had cut. But what they put back
 * is often not a model request: it is the autodrive floor. On 2026-10-21 08:00 the model asked 0 U, the
 * floor posted 1.70 U, the PKPD guard cut it to 0.82 U, and Red Carpet sent 1.70 U again. Before a low,
 * these restores carry about twice the insulin they carry otherwise (measured 2026-09-02 → 10-20).
 *
 * A declared meal and a manual action are exempt: there the person asked for the insulin. Reduce-only:
 * it never raises a dose. [modelOutputU] is kept in the signature for trace compatibility.
 */
object SmbRestoreCeiling {

    fun apply(finalU: Double, modelOutputU: Double?, afterGuardsU: Double, exempt: Boolean): Double {
        if (exempt) return finalU
        if (!afterGuardsU.isFinite()) return finalU
        return min(finalU, afterGuardsU.coerceAtLeast(0.0))
    }
}
