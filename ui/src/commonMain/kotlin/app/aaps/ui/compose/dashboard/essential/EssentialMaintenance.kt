package app.aaps.ui.compose.dashboard.essential

import app.aaps.core.ui.compose.StatusLevel

/** What can need changing soon. The order is the tie-break when two are equally urgent. */
enum class MaintenanceKind { SENSOR, CANNULA, INSULIN, BATTERY }

/**
 * One item of the status lights, as the maintenance satellite reads it.
 *
 * @property level from the user's own status light thresholds (the same ones every other screen uses)
 * @property value the text to show: the age ("3d4h"), or the level ("12 U") when [byLevel]
 * @property byLevel true when the level (insulin left, battery charge) is what makes the item due, not
 *   its age. A reservoir can be nearly empty long before it is old.
 */
data class EssentialMaintenance(val kind: MaintenanceKind, val level: StatusLevel, val value: String, val byLevel: Boolean = false) {

    companion object {

        /**
         * The item from both of its status lights: the age and, where the pump reports one, the level.
         * The more urgent of the two wins. When both are equally urgent the level is shown, because it is
         * the one that runs out.
         */
        fun of(kind: MaintenanceKind, ageStatus: StatusLevel, age: String, levelStatus: StatusLevel = StatusLevel.UNSPECIFIED, levelText: String? = null): EssentialMaintenance {
            val levelWins = levelText != null && levelStatus.isDue() && urgency(levelStatus) >= urgency(ageStatus)
            return if (levelWins) EssentialMaintenance(kind, levelStatus, levelText, byLevel = true)
            else EssentialMaintenance(kind, ageStatus, age)
        }
    }
}

/** Which item the maintenance satellite shows. Pure, so it is tested without a screen. */
object EssentialMaintenanceRule {

    /**
     * The most urgent item that is due: critical before warning, then [MaintenanceKind] order. Null when
     * nothing is due, and the satellite is then not drawn at all: the skin stays quiet while all is well.
     */
    fun mostUrgent(items: List<EssentialMaintenance>): EssentialMaintenance? =
        items
            .filter { it.level.isDue() }
            .minWithOrNull(compareBy<EssentialMaintenance>({ if (it.level == StatusLevel.CRITICAL) 0 else 1 }, { it.kind.ordinal }))
}

private fun StatusLevel.isDue(): Boolean = this == StatusLevel.WARNING || this == StatusLevel.CRITICAL

/** Higher is more urgent. NORMAL and UNSPECIFIED are both "nothing due". */
private fun urgency(level: StatusLevel): Int = when (level) {
    StatusLevel.CRITICAL -> 2
    StatusLevel.WARNING  -> 1
    else                 -> 0
}
