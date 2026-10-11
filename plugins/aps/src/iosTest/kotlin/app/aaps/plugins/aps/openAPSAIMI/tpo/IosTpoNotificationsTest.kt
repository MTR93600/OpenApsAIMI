package app.aaps.plugins.aps.openAPSAIMI.tpo

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The identifiers the two TPO notifications are posted under.
 *
 * `IosTpoNotifications` itself reaches a notification centre, which a test binary has no app bundle
 * for, but the identifiers are plain string work and getting them wrong is silent in both
 * directions: two kinds sharing an identifier would make the end notification replace the start one
 * instead of its own, and an `aaps-` prefix would let "mute all alarms" delete a notification that
 * is not an alarm.
 */
class IosTpoNotificationsTest {

    @Test
    fun `each kind has its own identifier`() {
        val ids = TpoNotificationKind.entries.map { IosTpoNotifications.identifierOf(it) }

        assertEquals(ids.size, ids.toSet().size, "two kinds sharing an id would replace each other")
    }

    /**
     * `IosSystemNotificationPlatform.cancelAll` claims every delivered `aaps-` notification as one
     * of its own alarms. A TPO notification must not be swept up by muting alarms.
     */
    @Test
    fun `identifiers stay out of the alarm platform's namespace`() {
        TpoNotificationKind.entries.forEach { kind ->
            assertFalse(IosTpoNotifications.identifierOf(kind).startsWith("aaps-"))
        }
    }
}
