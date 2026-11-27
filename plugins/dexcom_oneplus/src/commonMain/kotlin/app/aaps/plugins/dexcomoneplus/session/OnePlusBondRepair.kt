package app.aaps.plugins.dexcomoneplus.session

/**
 * Where the repair of a stale Android bond stands, see [OnePlusBondRepair].
 */
enum class OnePlusBondRepairState {

    /** Nothing to repair. */
    NONE,

    /** The bond looks stale. The user can start a repair. */
    BROKEN,

    /**
     * The user started a repair: the bond was removed. The next pairing asks for a new bond, and
     * Android shows a pairing request that the user must accept. Ends at the next healthy cycle.
     */
    REPAIRING,
}

/**
 * The phone's Android bond with the sensor can go stale, and the session cannot leave that state
 * on its own.
 *
 * Field logs of 26/11/2026 (14:15) and 27/11/2026 (01:35), on a phone with a Bluetooth bug: a
 * handshake was cut, then the sensor refused the saved key of the phone slot only. From then on,
 * every cycle went the same way:
 * 1. the sensor refuses the saved key, the session deletes it (the soft refresh keeps the bond);
 * 2. a full J-PAKE is accepted, with `AuthStatus bonded=1`, on a link that Android calls bonded;
 * 3. the sensor closes the link (status 19) right after the first request, before any Control
 *    traffic.
 *
 * It went on for about 7 hours both times. Removing the bond in the Android settings fixed it: the
 * next J-PAKE got `bonded=2`, the session asked for a bond, the user accepted the pairing request,
 * and the saved key worked again. On 27/11 it took three cycles: two unbonded J-PAKEs stalled
 * before the sensor asked for the bond.
 *
 * The session never removes the bond by itself. After that, Android shows a pairing request that
 * must be accepted within seconds, and the sensor asks again at every cycle. At night nobody
 * accepts it. So the session only reports [OnePlusBondRepairState.BROKEN], and removes the bond
 * when the user asks for it, between two links.
 */
object OnePlusBondRepair {

    /** Stale cycles in a row before the bond is reported as broken. */
    const val STALE_CYCLES = 2

    /**
     * The count of stale cycles in a row, after a cycle that reached an accepted authentication.
     * A cycle that ended earlier (refused, failed connect) does not change it: in the stale state a
     * refusal comes between two stale cycles.
     *
     * @param fullPairing the cycle paired in full, with no saved key
     * @param osBonded Android called the link bonded when the cycle authenticated
     * @param provedHealthy the sensor sent Control traffic or glucose in the cycle
     */
    fun staleCycles(previous: Int, fullPairing: Boolean, osBonded: Boolean, provedHealthy: Boolean): Int = when {
        provedHealthy           -> 0
        fullPairing && osBonded -> previous + 1
        else                    -> previous
    }

    /**
     * The state after a cycle. A healthy cycle ends a repair and clears a broken state. Enough stale
     * cycles report a broken bond, also after a repair that did not help (the count starts again at
     * the repair).
     */
    fun next(state: OnePlusBondRepairState, staleCycles: Int, provedHealthy: Boolean): OnePlusBondRepairState = when {
        provedHealthy                   -> OnePlusBondRepairState.NONE
        staleCycles >= STALE_CYCLES     -> OnePlusBondRepairState.BROKEN
        else                            -> state
    }
}
