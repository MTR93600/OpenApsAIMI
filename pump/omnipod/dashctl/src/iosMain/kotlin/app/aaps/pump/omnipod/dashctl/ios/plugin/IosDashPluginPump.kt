package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpEnactResult
import app.aaps.pump.omnipod.dashctl.ios.pump.IosDashPump
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import kotlin.time.Clock

/**
 * The [Pump] the iOS Dash plugin gives to the app: the driver ([IosDashPump]) for every command, and
 * the answers that Android gives from `OmnipodDashPumpPlugin` itself rather than from its manager.
 * A plain class, so it can be tested on the simulated pod without the plugin and its graph.
 *
 * Android `OmnipodDashPumpPlugin`, the same rule for each:
 * - [pumpDescription]: `PumpDescription().fillFor(PumpType.OMNIPOD_DASH)` (steps, limits, the 30 min
 *   temp basal step, no extended bolus);
 * - [isInitialized]: `podStateManager.isPodRunning`, so with no running pod the loop does not run;
 * - [isConfigured]: true, the Android default (Android does not override it);
 * - [serialNumber]: the unique id of the pod, "4241" without one (`Constants.PUMP_SERIAL_FOR_FAKE_TBR`);
 * - [baseBasalRate]: the rate of the confirmed program now, 0 while the pod reports an alarm;
 * - [deliveredBasal]: the 24 hourly rates of the confirmed program, null before the activation is
 *   completed or without a program (#2454);
 * - [loadTDDs], [setExtendedBolus], [cancelExtendedBolus]: refused, with Android's text;
 * - [timezoneOrDSTChanged]: logged only (the pod keeps its time, a known gap, as Android);
 * - [deliverTreatment] and [setTempBasalAbsolute] also tell the `PumpSync` recorder the type of the
 *   bolus or temp basal, which the driver does not store.
 *
 * Deliberate difference: a bolus with carbs or with no insulin is refused with a failure. Android
 * throws (`require`) out of `deliverTreatment`; nothing is sent in both.
 */
class IosDashPluginPump(
    private val driver: IosDashPump,
    private val stateManager: DashPodStateManager,
    private val recorder: DashPumpSyncRecorder,
    private val aapsLogger: AAPSLogger,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : Pump by driver {

    override val pumpDescription: PumpDescription = PumpDescription().fillFor(PumpType.OMNIPOD_DASH)

    override fun model(): PumpType = PumpType.OMNIPOD_DASH

    override fun isConfigured(): Boolean = true

    override fun isInitialized(): Boolean = stateManager.isPodRunning

    override fun serialNumber(): String = stateManager.uniqueId?.toString() ?: PUMP_SERIAL_FOR_FAKE_TBR

    override val baseBasalRate: PumpRate
        get() {
            val rate = stateManager.basalProgram?.rateAt(now()) ?: 0.0
            return PumpRate(if (stateManager.alarmType != null) 0.0 else rate)
        }

    override fun deliveredBasal(): List<Profile.ProfileValue>? =
        if (!stateManager.isActivationCompleted) null
        else stateManager.basalProgram?.hourlyRates()?.mapIndexed { hour, rate -> Profile.ProfileValue(hour * SECONDS_PER_HOUR, rate) }

    override suspend fun deliverTreatment(detailedBolusInfo: DetailedBolusInfo): PumpEnactResult {
        if (detailedBolusInfo.carbs != 0.0 || detailedBolusInfo.insulin <= 0.0) {
            aapsLogger.error(LTag.PUMP, "Dash: bolus refused, it needs insulin and no carbs: $detailedBolusInfo")
            return DashPumpEnactResult(success = false, enacted = false, comment = "Bolus refused: insulin must be more than 0 and carbs 0")
        }
        recorder.beginBolus(detailedBolusInfo.bolusType)
        val before = stateManager.activeCommand
        try {
            return driver.deliverTreatment(detailedBolusInfo)
        } finally {
            recorder.endCommand(commandMadeSince(before))
        }
    }

    override suspend fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        recorder.beginTempBasal(tbrType)
        val before = stateManager.activeCommand
        try {
            return driver.setTempBasalAbsolute(absoluteRate, durationInMinutes, enforceNew, tbrType)
        } finally {
            recorder.endCommand(commandMadeSince(before))
        }
    }

    /**
     * The open command, only when it is not [before]: a call refused because an earlier command is
     * still not confirmed leaves that earlier command open, and it must keep its own type.
     */
    private fun commandMadeSince(before: DashActiveCommand?): DashActiveCommand? {
        val after = stateManager.activeCommand ?: return null
        val same = before != null && after.sequence == before.sequence && after.historyId == before.historyId && after.createdAtMs == before.createdAtMs
        return if (same) null else after
    }

    override suspend fun loadTDDs(): PumpEnactResult =
        DashPumpEnactResult(success = false, enacted = false, comment = "Omnipod Dash driver does not support TDD")

    override suspend fun setExtendedBolus(insulin: Double, durationInMinutes: Int): PumpEnactResult =
        DashPumpEnactResult(success = false, enacted = false, comment = "Omnipod Dash driver does not support extended boluses")

    override suspend fun cancelExtendedBolus(): PumpEnactResult =
        DashPumpEnactResult(success = false, enacted = false, comment = "Omnipod Dash driver does not support extended boluses")

    override suspend fun timezoneOrDSTChanged(timeChangeType: TimeChangeType) {
        aapsLogger.info(LTag.PUMP, "Ignoring time change because automatic time handling is not implemented. timeChangeType=${timeChangeType.name}")
    }

    companion object {

        /** Android `Constants.PUMP_SERIAL_FOR_FAKE_TBR`: the serial when no pod is paired. */
        const val PUMP_SERIAL_FOR_FAKE_TBR = "4241"

        private const val SECONDS_PER_HOUR = 3600
    }
}
