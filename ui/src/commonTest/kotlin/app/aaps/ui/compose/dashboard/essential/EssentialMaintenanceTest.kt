package app.aaps.ui.compose.dashboard.essential

import app.aaps.core.ui.compose.StatusLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun a_nearly_empty_reservoir_is_due_even_when_the_insulin_is_young() {
        val insulin = EssentialMaintenance.of(MaintenanceKind.INSULIN, StatusLevel.NORMAL, "1d", StatusLevel.CRITICAL, "8 U")
        assertEquals(StatusLevel.CRITICAL, insulin.level)
        assertEquals("8 U", insulin.value)
        assertTrue(insulin.byLevel)
        assertEquals(insulin, EssentialMaintenanceRule.mostUrgent(listOf(insulin)))
    }

    @Test
    fun the_more_urgent_of_age_and_level_wins() {
        val oldReservoir = EssentialMaintenance.of(MaintenanceKind.INSULIN, StatusLevel.CRITICAL, "4d", StatusLevel.WARNING, "30 U")
        assertEquals(StatusLevel.CRITICAL, oldReservoir.level)
        assertEquals("4d", oldReservoir.value)
        assertFalse(oldReservoir.byLevel)
    }

    @Test
    fun equal_urgency_shows_the_level() {
        val both = EssentialMaintenance.of(MaintenanceKind.BATTERY, StatusLevel.WARNING, "9d", StatusLevel.WARNING, "15 %")
        assertEquals("15 %", both.value)
        assertTrue(both.byLevel)
    }

    @Test
    fun no_level_reported_keeps_the_age() {
        val noLevel = EssentialMaintenance.of(MaintenanceKind.INSULIN, StatusLevel.WARNING, "3d", StatusLevel.CRITICAL, null)
        assertEquals(StatusLevel.WARNING, noLevel.level)
        assertEquals("3d", noLevel.value)
        assertFalse(noLevel.byLevel)
        val levelFine = EssentialMaintenance.of(MaintenanceKind.INSULIN, StatusLevel.NORMAL, "1d", StatusLevel.NORMAL, "120 U")
        assertNull(EssentialMaintenanceRule.mostUrgent(listOf(levelFine)))
    }
}
