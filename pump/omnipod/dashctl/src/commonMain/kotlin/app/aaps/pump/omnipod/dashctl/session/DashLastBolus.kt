package app.aaps.pump.omnipod.dashctl.session

/**
 * The last bolus the pod took, from its SUCCESS until the pod no longer delivers it. Port of
 * Android `OmnipodDashPodStateManager.LastBolus`.
 *
 * Stored with the rest of the state (`DashPodStateCodec` version 4), so a crash or a restart during
 * the wait for the end of the bolus keeps what is needed to count it: what was asked, what the pod
 * still had to give at the last status, and whether it ended.
 *
 * @property startTimeMs the wall clock when the SUCCESS of the bolus was known (Android
 *   `startTime`, set by `createLastBolus`).
 * @property requestedUnits what the bolus asked for.
 * @property bolusUnitsRemaining what the pod still had to give at the last status (Android
 *   `bolusUnitsRemaining`, from `bolusPulsesRemaining`). Starts at [requestedUnits].
 * @property deliveryComplete true when the bolus ended: the pod reported 0 left, a stop of the bolus
 *   was confirmed, or the pod stopped delivering it (Android: "cancelled, delivered 100% or pod
 *   failure"). Once true, a later status no longer changes [bolusUnitsRemaining]; see
 *   `DashPodStateManager.updateLastBolusFromStatus`.
 * @property historyId Android `historyId`: the history record of the bolus. 0 on iOS until the pod
 *   history is ported.
 * @property createdAtMs the time the bolus command was made ([DashActiveCommand.createdAtMs]). A
 *   bolus stopped early is recorded again with the amount that went in, at this time, as Android
 *   records it at the `createdAt` of the bolus history record. Null when the command came from a
 *   store too old to have the time.
 */
data class DashLastBolus(
    val startTimeMs: Long,
    val requestedUnits: Double,
    val bolusUnitsRemaining: Double,
    val deliveryComplete: Boolean,
    val historyId: Long = 0L,
    val createdAtMs: Long? = null,
) {

    /**
     * Android `deliveredUnits`: what went in (asked - left), once the bolus ended; null before that.
     *
     * When the units left are not between 0 and what was asked, no status of this bolus can say so,
     * and the whole bolus is counted. Android does the same in effect: on CANCEL_BOLUS it logs an
     * error for a negative amount and does not record it, so the bolus stays recorded with the asked
     * amount. Never more than asked, never a negative bolus.
     */
    fun deliveredUnits(): Double? {
        if (!deliveryComplete) return null
        val left = bolusUnitsRemaining
        return if (left < 0.0 || left > requestedUnits) requestedUnits else requestedUnits - left
    }
}
