package app.aaps.plugins.aps.openAPSAIMI.advisor.diag

import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath

/**
 * What came of building the AIMI Advisor support package.
 *
 * It was a nested `AimiSupportPackageExporter.Result` before. It is here on its own because the
 * screen that reacts to it is shared code while the exporter is not: the exporter still builds the
 * archive with `java.util.zip` and writes it to the Android cache directory, which has no shared
 * answer yet. The result itself has nothing Android in it - a path and a message - so it can live
 * where both sides can see it.
 */
sealed interface AimiSupportPackageResult {

    /** The package is written and ready to be shared. [zip] is the archive. */
    data class Ready(val zip: AimiPath) : AimiSupportPackageResult

    /** Nothing could be collected, so there is no package to send. */
    data object Empty : AimiSupportPackageResult

    /** The build failed. [message] is the platform's own wording, or null when it gave none. */
    data class Failed(val message: String?) : AimiSupportPackageResult
}
