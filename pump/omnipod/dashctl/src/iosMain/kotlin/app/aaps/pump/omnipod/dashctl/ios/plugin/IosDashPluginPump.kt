package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpDescription
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.data.pump.defs.TimeChangeType
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.pump.defs.fillFor
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.pump.omnipod.dashctl.history.DashPodHistory
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpEnactResult
import app.aaps.pump.omnipod.dashctl.ios.pump.IosDashPump
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
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
 *   bolus or temp basal, which the driver does not store;
 * - after every command and status read, what Android runs in its command chain
 *   ([DashPluginRules.afterCommand]: `verifyPumpState`, `checkPodKaput`, overview refresh), and the
 *   notices of a command the pod did not confirm (`notifyOnUnconfirmed`, the bolus alarm);
 * - [executeCustomCommand]: the queue commands of Android ([DashPluginRules.executeCustomCommand]).
 *
 * Deliberate difference: a bolus with carbs or with no insulin is refused with a failure. Android
 * throws (`require`) out of `deliverTreatment`; nothing is sent in both.
 */
class IosDashPluginPump(
    private val driver: IosDashPump,
    private val stateManager: DashPodStateManager,
    private val recorder: DashPumpSyncRecorder,
    private val rules: DashPluginRules,
    private val aapsLogger: AAPSLogger,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    /** The pod history, told whether the next bolus is an SMB (Android `BolusType.SMB` in the record). */
    private val history: DashPodHistory? = null,
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
        history?.nextBolusIsSmb = detailedBolusInfo.bolusType == BS.Type.SMB
        val before = stateManager.activeCommand
        val result = try {
            driver.deliverTreatment(detailedBolusInfo)
        } finally {
            recorder.endCommand(commandMadeSince(before))
            history?.nextBolusIsSmb = false
        }
        rules.afterCommand()
        rules.afterBolus(isSmb = detailedBolusInfo.bolusType == BS.Type.SMB, units = detailedBolusInfo.insulin)
        return result
    }

    override suspend fun setTempBasalAbsolute(
        absoluteRate: Double,
        durationInMinutes: Int,
        enforceNew: Boolean,
        tbrType: PumpSync.TemporaryBasalType
    ): PumpEnactResult {
        recorder.beginTempBasal(tbrType)
        val before = stateManager.activeCommand
        val result = try {
            driver.setTempBasalAbsolute(absoluteRate, durationInMinutes, enforceNew, tbrType)
        } finally {
            recorder.endCommand(commandMadeSince(before))
        }
        // Android: a notice after the cancel before it, or after the temp basal itself, when it stays open.
        when (commandMadeSince(before)?.kind) {
            DashCommandKind.STOP_TEMP_BASAL -> rules.notifyOnUnconfirmed(
                NotificationId.OMNIPOD_TBR_ALERTS, DashStrings.omnipod_dash_ios_cancel_tbr_before_unconfirmed, AlarmSound.BOLUS_ERROR
            )

            DashCommandKind.TEMP_BASAL      -> rules.notifyOnUnconfirmed(
                NotificationId.OMNIPOD_TBR_ALERTS, DashStrings.omnipod_dash_ios_tbr_unconfirmed, AlarmSound.BOLUS_ERROR
            )

            else                            -> Unit
        }
        rules.afterCommand()
        return result
    }

    /** Android `cancelTempBasal`: a notice when the cancel stays open. */
    override suspend fun cancelTempBasal(enforceNew: Boolean): PumpEnactResult {
        val before = stateManager.activeCommand
        val result = driver.cancelTempBasal(enforceNew)
        if (commandMadeSince(before)?.kind == DashCommandKind.STOP_TEMP_BASAL) {
            rules.notifyOnUnconfirmed(NotificationId.OMNIPOD_TBR_ALERTS, DashStrings.omnipod_dash_ios_cancel_tbr_unconfirmed, AlarmSound.BOLUS_ERROR)
        }
        rules.afterCommand()
        return result
    }

    /**
     * Android `setNewBasalProfile`: a notice when the suspend before the program stays open, and
     * Android's text when the program stays open ([DashPluginRules.basalProgramResult]).
     */
    override suspend fun setNewBasalProfile(profile: PumpProfile): PumpEnactResult {
        val before = stateManager.activeCommand
        val result = rules.basalProgramResult(before, driver.setNewBasalProfile(profile))
        rules.afterCommand()
        return result
    }

    /**
     * Android `getPumpStatus`: `verifyPumpState` and `checkPodKaput` follow the status read. As Android,
     * nothing at all runs before the activation is completed, unless the user asked for the read: the
     * driver reads nothing then, and the checks would compare with an old status (a half activated pod
     * whose basal runs would fight the zero temp basal of "no pod" at every read).
     */
    override suspend fun getPumpStatus(reason: String) {
        if (reason != IosDashPump.REQUESTED_BY_USER && !stateManager.isActivationCompleted) {
            aapsLogger.debug(LTag.PUMP, "getPumpStatus ($reason): pod activation not completed, nothing done")
            return
        }
        driver.getPumpStatus(reason)
        rules.afterCommand()
    }

    /** Android `executeCustomCommand`, see [DashPluginRules.executeCustomCommand]. */
    override fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult = rules.executeCustomCommand(customCommand)

    /** See [commandMadeSince]. */
    private fun commandMadeSince(before: DashActiveCommand?): DashActiveCommand? = commandMadeSince(stateManager, before)

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
