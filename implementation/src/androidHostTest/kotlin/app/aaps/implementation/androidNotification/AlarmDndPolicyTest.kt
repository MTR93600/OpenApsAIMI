package app.aaps.implementation.androidNotification

import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AlarmDndPolicyTest {

    @Test
    fun dndBlocksAlarmsOnlyInTotalSilenceOrPriorityWithoutAlarms() {
        assertThat(AlarmDndPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_ALL, alarmsAllowedInPriority = null)).isFalse()
        assertThat(AlarmDndPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_ALARMS, null)).isFalse()
        assertThat(AlarmDndPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_NONE, null)).isTrue()
        assertThat(AlarmDndPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarmsAllowedInPriority = true)).isFalse()
        assertThat(AlarmDndPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarmsAllowedInPriority = false)).isTrue()
        // Policy unreadable: assume the worst, the alarm must be heard.
        assertThat(AlarmDndPolicy.dndBlocksAlarms(NotificationManager.INTERRUPTION_FILTER_PRIORITY, alarmsAllowedInPriority = null)).isTrue()
    }
}
