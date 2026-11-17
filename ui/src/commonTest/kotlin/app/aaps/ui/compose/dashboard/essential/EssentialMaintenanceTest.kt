package app.aaps.ui.compose.dashboard.essential

import app.aaps.core.ui.compose.StatusLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EssentialMaintenanceTest {

    private fun item(kind: MaintenanceKind, level: StatusLevel) = EssentialMaintenance(kind, level, "1d")

    @Test
    fun nothing_due_shows_nothing() {
        assertNull(EssentialMaintenanceRule.mostUrgent(MaintenanceKind.entries.map { item(it, StatusLevel.NORMAL) }))
        assertNull(EssentialMaintenanceRule.mostUrgent(listOf(item(MaintenanceKind.SENSOR, StatusLevel.UNSPECIFIED))))
    }

    @Test
    fun critical_comes_before_warning() {
        val shown = EssentialMaintenanceRule.mostUrgent(
            listOf(item(MaintenanceKind.SENSOR, StatusLevel.WARNING), item(MaintenanceKind.BATTERY, StatusLevel.CRITICAL)),
        )
        assertEquals(MaintenanceKind.BATTERY, shown?.kind)
    }

    @Test
    fun equal_urgency_follows_the_kind_order() {
        val shown = EssentialMaintenanceRule.mostUrgent(
            listOf(item(MaintenanceKind.CANNULA, StatusLevel.WARNING), item(MaintenanceKind.SENSOR, StatusLevel.WARNING)),
        )
        assertEquals(MaintenanceKind.SENSOR, shown?.kind)
    }
}
