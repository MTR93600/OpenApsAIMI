package app.aaps.core.interfaces.rx.events

/**
 * Event sent by APS plugin back to UI with results of AIMI backup.
 */
data class EventAimiCloudBackupResult(
    val successCount: Int,
    val totalCount: Int,
    val error: String? = null
) : Event()
