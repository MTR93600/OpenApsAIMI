package app.aaps.pump.omnipod.dashctl.session

/**
 * The temp basal the pod confirmed last. Port of Android `OmnipodDashPodStateManager.TempBasal`.
 *
 * Set when the pod confirms a temp basal, cleared when it confirms a cancel of the temp basal, a
 * suspend or a resume (Android `handleCommandConfirmation`). Stored with the rest of the state
 * (`DashPodStateCodec` version 4).
 *
 * @property startTimeMs when the temp basal command was made ([DashActiveCommand.createdAtMs]),
 *   as Android takes `System.currentTimeMillis()` when it opens the active command.
 * @property rate the rate in U/h.
 * @property durationInMinutes how long it runs.
 */
data class DashTempBasal(
    val startTimeMs: Long,
    val rate: Double,
    val durationInMinutes: Int,
) {

    /** True while the temp basal has not run out at [nowMs] (Android `tempBasalActive`, without the suspend check). */
    fun runsAt(nowMs: Long): Boolean = startTimeMs + durationInMinutes * MS_PER_MINUTE > nowMs

    private companion object {

        const val MS_PER_MINUTE = 60_000L
    }
}
