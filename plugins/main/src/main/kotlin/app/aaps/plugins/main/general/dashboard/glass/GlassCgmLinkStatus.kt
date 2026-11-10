package app.aaps.plugins.main.general.dashboard.glass

import app.aaps.core.interfaces.source.CgmWarmupStatus

/**
 * When the GLASS headline must say that the sensor link is down.
 *
 * GLASS used to show the last glucose as if it were current while the ONE+/G7 or the Libre 3 was
 * reconnecting: the source state reached the view model but GLASS dropped it. The other skins already
 * show it, with the same gate: a link status is active AND the shown value is stale. The ONE+ reports
 * RECONNECTING between two normal radio windows too; its value is then still fresh, so nothing shows.
 */
internal object GlassCgmLinkStatus {

    /** Same freshness limit as the view model (`DashboardCoherentGlucose.isDisplayActual`, 9 min). */
    const val STALE_AFTER_MS = 9L * 60_000L

    data class Banner(
        val phase: CgmWarmupStatus.Phase,
        /** Minutes since the shown glucose, null when its time is unknown. */
        val minutesSinceLastValue: Long?,
    )

    /**
     * True when the shown glucose is too old. The view model only recomputes on events, so the card
     * also checks the age itself, between two refreshes.
     */
    fun isStale(isGlucoseActual: Boolean, glucoseTimestampMs: Long?, nowMs: Long): Boolean =
        !isGlucoseActual || (glucoseTimestampMs != null && nowMs - glucoseTimestampMs > STALE_AFTER_MS)

    fun banner(warmup: CgmWarmupStatus?, isGlucoseActual: Boolean, glucoseTimestampMs: Long?, nowMs: Long): Banner? {
        if (warmup?.active != true || !isStale(isGlucoseActual, glucoseTimestampMs, nowMs)) return null
        return Banner(
            phase = warmup.phase,
            minutesSinceLastValue = glucoseTimestampMs?.let { ((nowMs - it) / 60_000L).coerceAtLeast(0L) },
        )
    }
}
