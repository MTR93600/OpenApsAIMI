package app.aaps.ui.compose.notificationsSheet

import app.aaps.core.interfaces.notifications.AapsNotification
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NotificationUrgencyTest {

    private fun item(key: Int, level: NotificationLevel) =
        AapsNotification(id = NotificationId.PROFILE_SET_OK, instanceKey = key, text = "", level = level, date = 0L)

    @Test
    fun no_notification_has_no_level() {
        assertNull(emptyList<AapsNotification>().mostUrgentLevel())
    }

    @Test
    fun an_alarm_wins_whatever_its_place_in_the_list() {
        val list = listOf(item(1, NotificationLevel.INFO), item(2, NotificationLevel.URGENT), item(3, NotificationLevel.IMPORTANT))
        assertEquals(NotificationLevel.URGENT, list.mostUrgentLevel())
    }

    @Test
    fun important_comes_before_normal() {
        val list = listOf(item(1, NotificationLevel.NORMAL), item(2, NotificationLevel.IMPORTANT), item(3, NotificationLevel.LOW))
        assertEquals(NotificationLevel.IMPORTANT, list.mostUrgentLevel())
    }
}
