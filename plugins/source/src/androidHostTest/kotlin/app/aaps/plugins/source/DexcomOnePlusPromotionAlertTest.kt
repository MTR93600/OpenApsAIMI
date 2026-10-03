package app.aaps.plugins.source

import android.app.Notification
import android.app.NotificationManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The status-bar alert that [DexcomOnePlusWarmupNotification.alert] posts for a promotion problem. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DexcomOnePlusPromotionAlertTest {

    @Test
    fun `bound failure notification shows the text and the action`() {
        val context = RuntimeEnvironment.getApplication()
        val text = context.getString(R.string.dexcom_oneplus_staging_promote_bound_failed)

        DexcomOnePlusWarmupNotification(context).alert(text)

        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications.single()
        val shown = posted.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertThat(shown).isEqualTo(text)
        assertThat(shown).contains("older calibration entries could not be ignored")
        assertThat(shown).contains("Remove fingerstick calibrations taken before this switch")
    }
}
