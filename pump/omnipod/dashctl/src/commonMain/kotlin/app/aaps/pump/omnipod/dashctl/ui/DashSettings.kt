package app.aaps.pump.omnipod.dashctl.ui

import androidx.compose.ui.graphics.vector.ImageVector
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.pump.omnipod.dashctl.keys.DashAlertBooleanKey
import app.aaps.pump.omnipod.dashctl.keys.DashAlertIntKey
import app.aaps.pump.omnipod.dashctl.keys.DashBeepKey
import app.aaps.pump.omnipod.dashctl.keys.DashSoundKey

/**
 * The Dash settings screen, the counterpart of Android `OmnipodDashPumpPlugin.getPreferenceScreenContent`:
 * the same three groups, the same keys in the same order, with the same defaults and limits.
 *
 * - Confirmation beeps: bolus, basal, SMB, TBR.
 * - Alerts: expiration reminder and its hours, expiration alert and its hours, low reservoir alert and
 *   its units. A change is sent to the pod through the queue by the plugin (`onStart`).
 * - Notifications: the sound of the uncertain TBR, SMB and bolus notices and of the delivery suspended notice.
 *
 * Left out: the Android "Advanced" group, which holds only "Bluetooth bonding". That switch changes how
 * Android 15 bonds with the pod. On an iPhone the app cannot ask for or refuse bonding (iOS decides when
 * the pod asks for it), so the switch would change nothing here.
 */
fun dashSettingsScreen(title: TextRef, icon: ImageVector?): PreferenceSubScreenDef = PreferenceSubScreenDef(
    key = "omnipod_dash_settings",
    title = title,
    items = listOf(
        PreferenceSubScreenDef(
            key = "omnipod_dash_beeps",
            title = DashStrings.dash_pref_category_confirmation_beeps,
            items = listOf(
                DashBeepKey.BolusBeepsEnabled,
                DashBeepKey.BasalBeepsEnabled,
                DashBeepKey.SmbBeepsEnabled,
                DashBeepKey.TbrBeepsEnabled
            )
        ),
        PreferenceSubScreenDef(
            key = "omnipod_dash_alerts",
            title = DashStrings.dash_pref_category_alerts,
            items = listOf(
                DashAlertBooleanKey.ExpirationReminder,
                DashAlertIntKey.ExpirationReminderHours,
                DashAlertBooleanKey.ExpirationAlarm,
                DashAlertIntKey.ExpirationAlarmHours,
                DashAlertBooleanKey.LowReservoirAlert,
                DashAlertIntKey.LowReservoirAlertUnits
            )
        ),
        PreferenceSubScreenDef(
            key = "omnipod_dash_notifications",
            title = DashStrings.dash_pref_category_notifications,
            items = listOf(
                DashSoundKey.SoundUncertainTbrNotification,
                DashSoundKey.SoundUncertainSmbNotification,
                DashSoundKey.SoundUncertainBolusNotification,
                DashSoundKey.SoundDeliverySuspendedNotification
            )
        )
    ),
    icon = icon
)
