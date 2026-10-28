package app.aaps.core.interfaces.aps

/**
 * Opens the full report of the AIMI auditor.
 *
 * The report is shown as a dialog by the host, so a caller does not need a screen of its own. It is
 * implemented in `:plugins:aps` and bound in the Android and iOS graphs. Desktop has no AIMI plugin,
 * so it has no implementation either: there the callers must hide the entry that would open it.
 */
interface AimiAuditorReport {

    /**
     * Shows the report and marks the verdict in it as read. It also removes the "new insight"
     * notification, because the user has now seen it.
     */
    fun openReport()
}
