package app.aaps.plugins.aps.openAPSAIMI.physio

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.keys.interfaces.Preferences
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Android half of [HormonitorStudyExporterProvider].
 *
 * The `runCatching` below is the loop's own, moved here unchanged from the field that used to build
 * the exporter inside `DetermineBasalaimiSMB2`. It is not defensive decoration: **telemetry must
 * never crash the loop**. If the study exporter cannot initialise (storage, permissions, context),
 * this degrades to no telemetry rather than letting its construction abort the tick into a safe
 * hold. `null` then makes every telemetry call in the loop a no-op, because every one of them is
 * `?.`, and `AimiLoopTelemetry.enterPhase` already accepts a null exporter. The error log line is a
 * support-package marker, so keep its text.
 *
 * `by lazy` on purpose: nothing is built while the dependency graph is being wired, only on the
 * first tick that asks for telemetry.
 *
 * **No binding is declared here yet.** The interface has no injection site until the loop entry
 * point moves into a compiled source set, and a binding would let the graph construct this class -
 * and the exporter behind it - before anything needs it.
 */
@SingleIn(AppScope::class)
class AndroidHormonitorStudyExporterProvider @Inject constructor(
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val preferences: Preferences
) : HormonitorStudyExporterProvider {

    private val exporter: HormonitorStudyExporter? by lazy {
        runCatching { AimiHormonitorStudyExporterMTR(context, aapsLogger, preferences) }
            .onFailure { aapsLogger.error(LTag.APS, "Hormonitor exporter init failed — telemetry disabled this session", it) }
            .getOrNull()
    }

    override fun exporter(): HormonitorStudyExporter? = exporter
}
