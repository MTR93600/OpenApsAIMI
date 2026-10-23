package app.aaps.ui.compose.dashboard

import app.aaps.core.interfaces.source.CgmWarmupStatus

/**
 * When the GLASS headline must say that the sensor link is down.
 *
 * GLASS used to show the last glucose as if it were current while the ONE+/G7 or the Libre 3 was
 * reconnecting. The gate is: the active source reports a link status AND the shown value is stale.
 * The ONE+ reports RECONNECTING between two normal radio windows too; its value is then still
 * fresh, so nothing shows.
 *
 * Source: `origin/dev_OAPSAIMI` `e5e7033311` (`GlassCgmLinkStatus` in `:plugins:main`). On this
 * branch GLASS is shared code, so the rule lives in commonMain and works the same on iOS.
 */
internal object GlassCgmLinkStatus {

    /** Same freshness limit as `BgInfoData.isOutdated` (9 min). */
    const val STALE_AFTER_MS: Long = 9L * 60_000L

    data class Banner(
        val phase: CgmWarmupStatus.Phase,
        /** Minutes since the shown glucose, null when its time is unknown. */
        val minutesSinceLastValue: Long?,
    )

    /**
     * True when the shown glucose is too old. The overview data only refreshes on events, so the
     * card also checks the age itself, between two refreshes.
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
