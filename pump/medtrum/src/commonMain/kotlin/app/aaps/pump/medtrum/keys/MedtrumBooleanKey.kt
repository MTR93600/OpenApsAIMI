package app.aaps.pump.medtrum.keys

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.medtrum.MedtrumSettingsDefaults
import app.aaps.pump.medtrum.MedtrumStrings

/**
 * The on/off pump settings. Shared code, so the Android and the iPhone settings screen show the
 * same keys with the same defaults.
 */
enum class MedtrumBooleanKey(
    override val key: String,
    override val defaultValue: Boolean,
    override val title: TextRef,
    override val summary: TextRef? = null,
) : BooleanPreferenceKey {

    MedtrumWarningNotification(
        key = "pump_warning_notification",
        defaultValue = MedtrumSettingsDefaults.PUMP_WARNING_NOTIFICATION,
        title = MedtrumStrings.pump_warning_notification_title,
        summary = MedtrumStrings.pump_warning_notification_summary
    ),
    MedtrumPatchExpiration(
        key = "patch_expiration",
        defaultValue = MedtrumSettingsDefaults.PATCH_EXPIRATION,
        title = MedtrumStrings.patch_expiration_title,
        summary = MedtrumStrings.patch_expiration_summary
    ),

    /**
     * Android only. The Android transport connects to a cached address and scans again only when
     * this is on. The iPhone transport always scans next to its direct connect, so on an iPhone
     * this switch would change nothing, and the iPhone screen does not show it.
     */
    MedtrumScanOnConnectionErrors(
        key = "scan_on_connection_error",
        defaultValue = false,
        title = MedtrumStrings.scan_on_connection_error_title,
        summary = MedtrumStrings.scan_on_connection_error_summary
    ),
    ;
}
