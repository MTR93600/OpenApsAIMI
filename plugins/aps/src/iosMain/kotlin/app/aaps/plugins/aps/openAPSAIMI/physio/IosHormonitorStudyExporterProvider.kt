package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage

/**
 * iOS half of [HormonitorStudyExporterProvider], the same shape as `AndroidHormonitorStudyExporterProvider`:
 * built on the first tick that asks, never while the graph is wired, and a failure to build means
 * "no telemetry this session", never a failed tick. One instance per app (the binding is
 * `@SingleIn(AppScope)`), because the exporter keeps day counters of its own.
 */
class IosHormonitorStudyExporterProvider(
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences,
    private val storage: AimiStorage,
) : HormonitorStudyExporterProvider {

    private val exporter: HormonitorStudyExporter? by lazy {
        runCatching { AimiHormonitorStudyExporterMTR(IosHormonitorStudyPlatform(storage), aapsLogger, preferences, storage) }
            .onFailure { aapsLogger.error(LTag.APS, "Hormonitor exporter init failed — telemetry disabled this session", it) }
            .getOrNull()
    }

    override fun exporter(): HormonitorStudyExporter? = exporter
}
