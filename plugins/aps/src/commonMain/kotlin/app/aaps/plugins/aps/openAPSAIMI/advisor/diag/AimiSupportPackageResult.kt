package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath

/**
 * What came of building the AIMI Advisor support package.
 *
 * Answered by [AimiSupportPackageExporter.build] and read by the shared support package screen. It
 * has nothing platform specific in it - a path and a message - so both phones use it as it is.
 */
sealed interface AimiSupportPackageResult {

    /** The package is written and ready to be shared. [zip] is the archive. */
    data class Ready(val zip: AimiPath) : AimiSupportPackageResult

    /** Nothing could be collected, so there is no package to send. */
    data object Empty : AimiSupportPackageResult

    /** The build failed. [message] is the platform's own wording, or null when it gave none. */
    data class Failed(val message: String?) : AimiSupportPackageResult
}
