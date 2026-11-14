package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.insulin.ConcentrationHelper
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpInsulin
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.queue.command.CommandDeactivatePod
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.ui.DashOverviewFormat
import app.aaps.pump.omnipod.dashctl.ui.DashWizardPod
import app.aaps.pump.omnipod.dashctl.ui.DashWizardResult
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

/**
 * The pod as the wizard sees it, the calls of Android `DashOmnipodWizardViewModel`:
 * - part 1 and part 2 straight to the plugin rules ([DashPluginRules.activatePodPart1] and
 *   [DashPluginRules.activatePodPart2]), as the Android wizard calls the manager directly, with the
 *   settings and the `PumpSync` records around them;
 * - the deactivation through the queue (Android `commandQueue.customCommand(CommandDeactivatePod())`),
 *   so it waits for the command that runs and the plugin records it as Android;
 * - the discard to [DashPluginRules.discardPod].
 *
 * [openRadio] runs before every pod call: it starts CoreBluetooth once (the plugin also does it in
 * `onStart`), so the wizard works even when it is the first thing that needs the radio.
 *
 * Every pod call runs on [podContext], the IO dispatcher, as Android subscribes the wizard actions on
 * `aapsSchedulers.io` (`OmnipodWizardViewModel.executeAction`) and runs `doInsertCannula` with
 * `rxSingle(Dispatchers.IO)`. The scan, the connect, the pairing and every activation command block
 * their thread for seconds (up to the connect timeout). The wizard model calls these from the main
 * thread and keeps its screen state there; only the pod work moves.
 */
class IosDashWizardPod(
    private val rules: DashPluginRules,
    private val stateManager: DashPodStateManager,
    private val commandQueue: () -> CommandQueue,
    private val openRadio: () -> Unit,
    /** Where the pod work runs. The IO dispatcher; a test on virtual time gives its own. */
    private val podContext: CoroutineContext = aapsIoDispatcher,
) : DashWizardPod {

    override val activationProgress: ActivationProgress get() = stateManager.activationProgress

    override suspend fun activatePart1(): DashWizardResult {
        openRadio()
        return withContext(podContext) { rules.activatePodPart1() }.toWizardResult()
    }

    override suspend fun activatePart2(): DashWizardResult {
        openRadio()
        return withContext(podContext) { rules.activatePodPart2() }.toWizardResult()
    }

    override suspend fun deactivate(): DashWizardResult {
        openRadio()
        return withContext(podContext) { commandQueue().customCommand(CommandDeactivatePod()) }.toWizardResult()
    }

    /** The discard waits for the command lock and writes `PumpSync`, so it runs on [podContext] too. */
    override suspend fun discard() = withContext(podContext) { rules.discardPod() }
}

private fun PumpEnactResult.toWizardResult() = DashWizardResult(success = success, comment = comment)

/** How the iPhone pod screen writes times and amounts: the `DateUtil` and `ConcentrationHelper` of Android. */
class IosDashOverviewFormat(
    private val dateUtil: DateUtil,
    private val ch: ConcentrationHelper,
    private val rh: TextResolver,
) : DashOverviewFormat {

    override fun dateAndTime(ms: Long): String = dateUtil.dateAndTimeString(ms)

    override fun insulin(units: Double): String = ch.insulinAmountString(PumpInsulin(units))

    override fun insulinAgo(units: Double, atMs: Long): String = ch.insulinAmountAgoString(PumpInsulin(units), atMs) ?: insulin(units)

    override fun basalRate(rate: Double): String = ch.basalRateString(PumpRate(rate), isAbsolute = true)

    override fun tempBasal(rate: Double, startMs: Long, minutes: Int): String = ch.basalTbrString(PumpRate(rate), startMs, minutes)

    override fun ago(ms: Long): String = dateUtil.minAgo(rh, ms)

    override fun timeRemaining(durationMs: Long): String = dateUtil.timeRemainingString(durationMs, rh)

    override fun fromPump(units: Double): Double = ch.fromPump(PumpInsulin(units))
}
