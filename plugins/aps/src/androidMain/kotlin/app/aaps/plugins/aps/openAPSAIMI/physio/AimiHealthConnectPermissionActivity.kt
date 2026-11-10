package app.aaps.plugins.aps.openAPSAIMI.physio

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.MetroAppCompatActivity
import dev.zacsweers.metro.Inject

/**
 * The Android window around [AimiHealthConnectPermissionScreen], for Health Connect itself.
 *
 * Health Connect does not show its permission dialog to an app that has no Activity for
 * `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` (and, from Android 14, for
 * `VIEW_PERMISSION_USAGE`). The request then comes back refused at once, with no dialog: that is
 * what "Grant access" did after the old Activity was removed. The manifest of the app declares this
 * Activity for both intents, next to the `android.permission.health.*` permissions.
 *
 * Inside AAPS the same screen opens from the AIMI settings, without this Activity.
 */
class AimiHealthConnectPermissionActivity : MetroAppCompatActivity() {

    @Inject lateinit var preferences: Preferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalPreferences provides preferences) {
                AapsTheme {
                    AimiHealthConnectPermissionScreen(onBack = { finish() })
                }
            }
        }
    }
}
