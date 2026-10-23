package app.aaps.plugins.aps.openAPSAIMI.autodrive.safety

import kotlin.math.max
import kotlin.math.min

/**
 * Limits what Autodrive V3 may deliver for a rise that nobody declared, while the proof that it is
 * a meal is weak.
 *
 * The proof is the meal belief. Below [WEAK_BELIEF] the basal stays under [WEAK_BASAL_FACTOR] times
 * the profile basal and the SMB under [WEAK_SMB_U]. Between [WEAK_BELIEF] and [STRONG_BELIEF] both
 * limits rise in a straight line. At [STRONG_BELIEF] and above the rise must also be CONFIRMED to get
 * the full range: basal up to [CONFIRMED_BASAL_FACTOR] times the profile and no SMB limit. Without
 * confirmation the limits stay at the strong values.
 *
 * It never applies to a declared meal, COB, a recent meal estimate, or a glucose above
 * [HIGH_BG_EXEMPT_MGDL] (that is a correction, not a meal guess). It only lowers a value and never
 * goes below the profile basal.
 */
object UndeclaredEvidenceCap {

    const val WEAK_BELIEF = 0.40
    const val STRONG_BELIEF = 0.60
    const val WEAK_BASAL_FACTOR = 2.0
    const val STRONG_BASAL_FACTOR = 4.0
    const val CONFIRMED_BASAL_FACTOR = 10.0
    const val WEAK_SMB_U = 0.3
    const val STRONG_SMB_U = 0.6
    const val HIGH_BG_EXEMPT_MGDL = 180.0

    data class Limits(val basalFactor: Double, val smbCapU: Double) {
        val active: Boolean get() = basalFactor.isFinite() || smbCapU.isFinite()
    }

    val NO_LIMIT = Limits(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)

    fun limits(
        undeclared: Boolean,
        bgMgdl: Double,
        belief: Double?,
        riseLevel: RiseConfirmation.Level?,
    ): Limits {
        if (!undeclared || bgMgdl >= HIGH_BG_EXEMPT_MGDL) return NO_LIMIT
        // No glucose history to judge from: keep the old behavior.
        if (riseLevel == null || belief == null || !belief.isFinite()) return NO_LIMIT
        if (belief >= STRONG_BELIEF) {
            return if (riseLevel == RiseConfirmation.Level.CONFIRMED) {
                Limits(CONFIRMED_BASAL_FACTOR, Double.POSITIVE_INFINITY)
            } else Limits(STRONG_BASAL_FACTOR, STRONG_SMB_U)
        }
        val t = ((belief - WEAK_BELIEF) / (STRONG_BELIEF - WEAK_BELIEF)).coerceIn(0.0, 1.0)
        return Limits(
            basalFactor = WEAK_BASAL_FACTOR + t * (STRONG_BASAL_FACTOR - WEAK_BASAL_FACTOR),
            smbCapU = WEAK_SMB_U + t * (STRONG_SMB_U - WEAK_SMB_U),
        )
    }

    fun capBasal(requestedUph: Double, profileBasalUph: Double, limits: Limits): Double {
        if (!limits.basalFactor.isFinite() || profileBasalUph <= 0.0) return requestedUph
        return min(requestedUph, max(profileBasalUph, profileBasalUph * limits.basalFactor))
    }

    fun capSmb(requestedU: Double, limits: Limits): Double =
        if (limits.smbCapU.isFinite()) min(requestedU, limits.smbCapU) else requestedU
}
