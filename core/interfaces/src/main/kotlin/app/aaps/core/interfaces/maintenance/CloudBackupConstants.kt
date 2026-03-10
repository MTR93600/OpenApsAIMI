package app.aaps.core.interfaces.maintenance

/**
 * Centralized management of cloud-related path constants.
 * This lives in core:interfaces so all plugins can use these paths without depending on plugins:configuration.
 */
object CloudBackupConstants {
    const val CLOUD_PATH_EXPORT = "/AAPS/export"
    const val CLOUD_PATH_SETTINGS = "${CLOUD_PATH_EXPORT}/preferences"
    const val CLOUD_PATH_LOGS = "${CLOUD_PATH_EXPORT}/logs"
    const val CLOUD_PATH_USER_ENTRIES = "${CLOUD_PATH_EXPORT}/user_entries"
    const val CLOUD_PATH_AIMI = "${CLOUD_PATH_EXPORT}/aimi"
}
