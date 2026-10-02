package app.aaps.plugins.source

import android.app.Application
import android.app.NotificationManager
import app.aaps.core.interfaces.source.SensorSlot
import app.aaps.plugins.libre3.Libre3WarmupState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The pre-soak message uses its own id, so it cannot replace the message about the sensor that
 * feeds the loop.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class Libre3WarmupNotificationSlotTest {

    private val application: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun `the pre-soak message is posted under id 4472 and production stays on 4471`() {
        val production = Libre3WarmupNotification(application, SensorSlot.PRODUCTION)
        val presoak = Libre3WarmupNotification(application, SensorSlot.STAGING)
        val pairing = Libre3WarmupState(phase = Libre3WarmupState.Phase.PAIRING)

        production.update(pairing)
        presoak.update(pairing)

        val manager = application.getSystemService(NotificationManager::class.java)
        val ids = manager.activeNotifications.map { it.id }.toSet()
        assertThat(ids).contains(4471)
        assertThat(ids).contains(4472)
    }
}
