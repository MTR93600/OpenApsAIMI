package app.aaps.implementation.maintenance

/**
 * SharedPreferences keys controlling export destination (local / cloud) for
 * settings, logs and CSV exports.
 */
object ExportPrefKeys {

    const val PREF_ALL_CLOUD_ENABLED = "export_all_cloud_enabled"
    const val PREF_LOG_EMAIL_ENABLED = "export_log_email_enabled"
    const val PREF_LOG_CLOUD_ENABLED = "export_log_cloud_enabled"
    const val PREF_SETTINGS_LOCAL_ENABLED = "export_settings_local_enabled"
    const val PREF_SETTINGS_CLOUD_ENABLED = "export_settings_cloud_enabled"
    const val PREF_CSV_LOCAL_ENABLED = "export_csv_local_enabled"
    const val PREF_CSV_CLOUD_ENABLED = "export_csv_cloud_enabled"
    /** When set, a successful settings export to cloud also triggers [app.aaps.core.interfaces.rx.events.EventAimiCloudBackupTrigger]. */
    const val PREF_AIMI_CLOUD_ENABLED = "export_aimi_cloud_enabled"
}
