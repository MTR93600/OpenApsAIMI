package app.aaps.plugins.aps.openAPSAIMI.sos

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.permissions.AimiPermissionRow
import app.aaps.plugins.aps.openAPSAIMI.permissions.AimiPermissionState
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionCriticalAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationOptions
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNNotificationSettingEnabled
import platform.UserNotifications.UNNotificationSettingNotSupported
import platform.UserNotifications.UNUserNotificationCenter
import kotlin.coroutines.resume

/**
 * What the SOS alert asks iOS for. [IosAimiEmergencySos] asks with these options before its first
 * alert, and the SOS permission screen asks with the same options, so both give the same answer.
 *
 * `CriticalAlert` needs an entitlement that Apple grants only on request. Without it, iOS simply
 * does not grant that option, and the SOS alert falls back to a time-sensitive notification.
 */
internal val SOS_AUTHORIZATION_OPTIONS: UNAuthorizationOptions =
    UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge or UNAuthorizationOptionCriticalAlert

/**
 * Reads and asks for what the iOS SOS alert needs: permission to show notifications, and, when Apple
 * granted the entitlement, critical alerts (which sound even in silent mode).
 *
 * iOS cannot send SMS from an app, so on iOS the SOS is a local alert on this phone
 * ([IosAimiEmergencySos]). Without the notification permission that alert is never seen, so the
 * screen built on this class is the one place where the user can check it.
 *
 * The notification center is resolved on first use: it throws without a real app bundle.
 */
internal class IosSosAlertPermissions(private val aapsLogger: AAPSLogger) {

    private val center by lazy { UNUserNotificationCenter.currentNotificationCenter() }

    suspend fun rows(): List<AimiPermissionRow> = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            val notifications = when (settings?.authorizationStatus) {
                UNAuthorizationStatusAuthorized,
                UNAuthorizationStatusProvisional,
                UNAuthorizationStatusEphemeral     -> AimiPermissionState.GRANTED
                UNAuthorizationStatusNotDetermined -> AimiPermissionState.NOT_ASKED
                else                               -> AimiPermissionState.DENIED
            }
            val critical = when {
                notifications == AimiPermissionState.NOT_ASKED                  -> AimiPermissionState.NOT_ASKED
                settings?.criticalAlertSetting == UNNotificationSettingEnabled -> AimiPermissionState.GRANTED
                // Not supported: this build has no critical alert entitlement from Apple.
                settings?.criticalAlertSetting == UNNotificationSettingNotSupported -> AimiPermissionState.UNAVAILABLE
                else                                                            -> AimiPermissionState.DENIED
            }
            continuation.resume(
                listOf(
                    AimiPermissionRow(ApsStrings.aimi_sos_ios_row_notifications, notifications),
                    AimiPermissionRow(ApsStrings.aimi_sos_ios_row_critical, critical),
                )
            )
        }
    }

    /** Shows the iOS question. iOS shows it only when it was never answered. */
    suspend fun ask() {
        suspendCancellableCoroutine { continuation ->
            center.requestAuthorizationWithOptions(SOS_AUTHORIZATION_OPTIONS) { granted, error ->
                if (error != null) aapsLogger.error(LTag.APS, "SOS notification permission failed: $error")
                else aapsLogger.debug(LTag.APS, "SOS notification permission granted=$granted")
                continuation.resume(Unit)
            }
        }
    }
}
