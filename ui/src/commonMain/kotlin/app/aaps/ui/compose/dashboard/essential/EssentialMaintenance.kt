package app.aaps.ui.compose.dashboard.essential

import app.aaps.core.ui.compose.StatusLevel

/** What can need changing soon. The order is the tie-break when two are equally urgent. */
enum class MaintenanceKind { SENSOR, CANNULA, INSULIN, BATTERY }

/**
 * One item of the status lights, as the maintenance satellite reads it.
 *
 * @property level from the user's own status light thresholds (the same ones every other screen uses)
 * @property age the age text the status lights show, already formatted
 */
data class EssentialMaintenance(val kind: MaintenanceKind, val level: StatusLevel, val age: String)

/** Which item the maintenance satellite shows. Pure, so it is tested without a screen. */
object EssentialMaintenanceRule {

    /**
     * The most urgent item that is due: critical before warning, then [MaintenanceKind] order. Null when
     * nothing is due, and the satellite is then not drawn at all: the skin stays quiet while all is well.
     */
    fun mostUrgent(items: List<EssentialMaintenance>): EssentialMaintenance? =
        items
            .filter { it.level == StatusLevel.WARNING || it.level == StatusLevel.CRITICAL }
            .minWithOrNull(compareBy<EssentialMaintenance>({ if (it.level == StatusLevel.CRITICAL) 0 else 1 }, { it.kind.ordinal }))
}
