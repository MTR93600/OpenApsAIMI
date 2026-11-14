package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationId
import app.aaps.core.interfaces.notifications.NotificationLevel
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.pump.PumpEnactResult
import app.aaps.core.interfaces.pump.PumpRate
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.core.interfaces.queue.Command
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventProfileChangeRequested
import app.aaps.core.interfaces.rx.events.EventRefreshOverview
import app.aaps.core.interfaces.ui.UiInteraction
import app.aaps.core.keys.interfaces.BooleanNonPreferenceKey
import app.aaps.core.keys.interfaces.IntNonPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.pump.omnipod.dashctl.ios.pump.DashBolusEndedByFault
import app.aaps.pump.omnipod.dashctl.ios.pump.DashCommandOutcome
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpEnactResult
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOutcome
import app.aaps.pump.omnipod.dashctl.ios.pump.IosDashPump
import app.aaps.pump.omnipod.dashctl.keys.DashAlertIntKey
import app.aaps.pump.omnipod.dashctl.keys.DashSoundKey
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.queue.command.CommandDeactivatePod
import app.aaps.pump.omnipod.dashctl.queue.command.CommandDisableSuspendAlerts
import app.aaps.pump.omnipod.dashctl.queue.command.CommandHandleTimeChange
import app.aaps.pump.omnipod.dashctl.queue.command.CommandPlayTestBeep
import app.aaps.pump.omnipod.dashctl.queue.command.CommandResumeDelivery
import app.aaps.pump.omnipod.dashctl.queue.command.CommandSilenceAlerts
import app.aaps.pump.omnipod.dashctl.queue.command.CommandUpdateAlertConfiguration
import app.aaps.pump.omnipod.dashctl.session.DashActiveCommand
import app.aaps.pump.omnipod.dashctl.session.DashAlertSettings
import app.aaps.pump.omnipod.dashctl.session.DashCommandConfirmation
import app.aaps.pump.omnipod.dashctl.session.DashCommandKind
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import app.aaps.pump.omnipod.dashctl.util.mapProfileToBasalProgram
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking

/**
 * What Android `OmnipodDashPumpPlugin` does around its manager, apart from `PumpSync` records
 * ([DashPumpSyncRecorder]): the queue commands, the notices, the status checker, `verifyPumpState`,
 * the PUMP_SUSPEND temp basal and fault notice of `checkPodKaput`, and the `PumpSync` records the
 * activation wizard writes after part 2. A plain class, so it is tested on the simulated pod without
 * the plugin and its graph.
 *
 * Notices go to the [NotificationManager] and alarms to [UiInteraction.runAlarm], as the iOS Medtrum
 * plugin and the shared code do. The texts are in the string table of this module ([DashStrings]).
 *
 * Queue work started from inside a command (Android `pluginScope.launch { commandQueue... }`) goes
 * through [launch], so the command that decided it never waits for the queue it runs in.
 *
 * Known gaps from Android, not ported yet (see the plan, step P2b). Both need new stored fields and a
 * new codec version:
 * - basal drift correction: Android (this fork) queues `CommandDeliverBasalCorrection` after a
 *   confirmed temp basal or cancel when `needsBasalCorrection()` is true, and gives a 0.05 U pulse to
 *   make up basal the pod gave too little. It needs the expected basal and the pod time zone in the
 *   state. Here nothing is queued and the command fails as unsupported, so the drift (at most about
 *   0.1 U before Android resets it) is not made up. Pinned by a test.
 * - time zone notice: Android posts OMNIPOD_TIME_OUT_OF_SYNC in `updatePodWarnings` when the time
 *   zone of the pod is not the time zone of the phone. The iOS state keeps no time zone, so no notice
 *   comes. Pinned by a test.
 * - during the bolus wait, Android runs `verifyPumpState` at each status read. Here it runs after the
 *   bolus call returns: it may stop a temp basal on the pod, which cannot be done while the bolus call
 *   holds the command lock. The fault part (`checkPodKaput`) does run at once, see [onOutcome].
 */
class DashPluginRules(
    private val driver: IosDashPump,
    private val stateManager: DashPodStateManager,
    private val pumpSync: PumpSync,
    private val notificationManager: NotificationManager,
    /** The user settings (on the phone `Preferences.get`), read each time, as Android reads them. */
    private val readBoolean: (BooleanNonPreferenceKey) -> Boolean,
    private val readInt: (IntNonPreferenceKey) -> Int,
    private val rh: TextResolver,
    private val aapsLogger: AAPSLogger,
    private val commandQueue: () -> CommandQueue,
    private val rxBus: () -> RxBus,
    private val uiInteraction: () -> UiInteraction,
    /** Android `serialNumber()`: the unique id of the pod, "4241" without one. */
    private val serialNumber: () -> String,
    /** Android `pluginScope.launch`: runs queue work on its own, never inside the caller. */
    private val launch: (suspend () -> Unit) -> Unit,
    private val now: () -> Long,
) {

    /** Android `nextPodWarningCheck`: the pod warnings are checked at most every 15 minutes. */
    private var nextPodWarningCheck = 0L

    /**
     * True while [handleTimeChange] sends its basal program. Android records that program as SET_TIME
     * and asks for no profile change when it is confirmed; the iOS driver stores no such kind.
     */
    @Volatile
    private var timeChangeRunning = false

    // ------------------------------------------------------------------ outcomes

    /**
     * The non `PumpSync` half of Android `handleCommandConfirmation`, for a SUCCESS or DENIED outcome
     * (Android gets no confirmation for a send failure or an open command):
     * - STOP_TEMP_BASAL and TEMP_BASAL: the "uncertain temp basal" notice is dismissed;
     * - BOLUS: the "uncertain SMB" notice is dismissed;
     * - RESUME_DELIVERY and BASAL_PROGRAM confirmed: the suspended, profile, temp basal and time notices
     *   are dismissed and [CommandDisableSuspendAlerts] is queued (the pod would beep "suspend ended"
     *   20 minutes later, #2982); for a basal program the queue is not running (a late confirmation),
     *   a profile change is asked again (`EventProfileChangeRequested`).
     *
     * Also the plugin part of [checkPodKaput], at once, for a kaput pod (ALARM or DEACTIVATED) when:
     * - a fault ended a bolus ([DashBolusEndedByFault]): Android runs `checkPodKaput` at each status read
     *   of the bolus wait, so the PUMP_SUSPEND temp basal and the fault notice do not wait for the end of
     *   the bolus call;
     * - a deactivation is decided: Android runs `checkPodKaput` before `podStateManager.reset()`, so the
     *   PUMP_SUSPEND temp basal is written with the serial of the pod from the deactivation on. Here the
     *   driver clears the state inside its call, right after this outcome.
     *
     * Called inside the driver command, so the queue work goes through [launch].
     */
    fun onOutcome(outcome: DashPumpOutcome) {
        val podKaputNow = outcome is DashBolusEndedByFault ||
            (outcome is DashCommandOutcome && outcome.command.kind == DashCommandKind.DEACTIVATE && outcome.confirmation.isFinal)
        if (podKaputNow && stateManager.isPodKaput) {
            try {
                // PumpSync is suspend; this runs on the queue thread inside the driver command, as the recorder.
                runBlocking { checkPodKaput() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                aapsLogger.error(LTag.PUMP, "Dash: check of the kaput pod failed", e)
            }
        }
        if (outcome !is DashCommandOutcome) return
        val confirmed = outcome.confirmation == DashCommandConfirmation.SUCCESS
        if (!confirmed && outcome.confirmation != DashCommandConfirmation.DENIED) return
        when (outcome.command.kind) {
            DashCommandKind.STOP_TEMP_BASAL,
            DashCommandKind.TEMP_BASAL      -> notificationManager.dismiss(NotificationId.OMNIPOD_TBR_ALERTS)

            DashCommandKind.BOLUS           -> notificationManager.dismiss(NotificationId.OMNIPOD_UNCERTAIN_SMB)

            DashCommandKind.RESUME_DELIVERY,
            DashCommandKind.BASAL_PROGRAM   -> if (confirmed) {
                if (outcome.command.kind == DashCommandKind.BASAL_PROGRAM && !timeChangeRunning &&
                    !commandQueue().isRunning(Command.CommandType.BASAL_PROFILE)
                ) {
                    // We are late-confirming this command. Before that, we answered with success false.
                    rxBus().send(EventProfileChangeRequested())
                }
                notificationManager.dismiss(NotificationId.OMNIPOD_POD_SUSPENDED)
                notificationManager.dismiss(NotificationId.FAILED_UPDATE_PROFILE)
                notificationManager.dismiss(NotificationId.OMNIPOD_TBR_ALERTS)
                notificationManager.dismiss(NotificationId.OMNIPOD_TIME_OUT_OF_SYNC)
                launch { commandQueue().customCommand(CommandDisableSuspendAlerts()) }
            }

            else                            -> Unit
        }
    }

    // ------------------------------------------------------------------ after each command

    /**
     * What Android runs after every programming command and every status read, after the confirmation:
     * [verifyPumpState], [checkPodKaput], and a refresh of the overview. Errors are logged; a command
     * result never depends on them.
     */
    suspend fun afterCommand() {
        try {
            verifyPumpState()
            checkPodKaput()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Dash: check after the command failed", e)
        }
        rxBus().send(EventRefreshOverview("Dash command", false))
    }

    /**
     * Android `verifyPumpState`: compares the temp basal AAPS expects with the delivery the pod
     * reported last.
     * - AAPS expects one and the pod runs its basal program: alarm, the temp basal is stopped in AAPS
     *   now (Android passes the database id of the temp basal as the end pump id), and the stored temp
     *   basal is dropped.
     * - AAPS expects none and the pod runs one: alarm, and the temp basal is stopped on the pod
     *   ([IosDashPump.cancelTempBasalNotExpected]).
     */
    suspend fun verifyPumpState() {
        val tbr = pumpSync.expectedPumpState().temporaryBasal
        val delivery = stateManager.podStatus?.deliveryStatus
        aapsLogger.debug(LTag.PUMP, "verifyPumpState, AAPS: $tbr Pump: $delivery")
        if (tbr != null && delivery?.basalActive() == true) {
            aapsLogger.error(LTag.PUMP, "AAPS expected a TBR running but pump has no TBR running! AAPS: $tbr Pump: $delivery")
            showErrorDialog(DashStrings.omnipod_dash_ios_tbr_out_of_sync, bolusErrorSound())
            val ret = pumpSync.syncStopTemporaryBasalWithPumpId(now(), tbr.id, PumpType.OMNIPOD_DASH, serialNumber())
            aapsLogger.info(LTag.PUMP, "syncStopTemporaryBasalWithPumpId ret=$ret pumpId=${tbr.id}")
            stateManager.clearTempBasal()
        } else if (tbr == null && delivery?.tempBasalActive() == true) {
            aapsLogger.error(LTag.PUMP, "AAPS expected no TBR running but pump has a TBR running! AAPS: null Pump: $delivery")
            showErrorDialog(DashStrings.omnipod_dash_ios_tbr_out_of_sync, bolusErrorSound())
            val ret = driver.cancelTempBasalNotExpected()
            aapsLogger.info(LTag.PUMP, "verifyPumpState: cancel of the TBR not expected: $ret")
            // Android `observeNoActiveTempBasal`, doOnComplete.
            notifyOnUnconfirmed(NotificationId.OMNIPOD_TBR_ALERTS, DashStrings.omnipod_dash_ios_cancel_tbr_before_unconfirmed, AlarmSound.BOLUS_ERROR)
        }
    }

    /**
     * The plugin part of Android `checkPodKaput`, for a pod in ALARM or DEACTIVATED (the bolus part is
     * the driver's, [DashBolusEndedByFault]):
     * - a PUMP_SUSPEND temp basal of 0 U/h for the pod life (80 h) at the time now, unless a zero temp
     *   basal already runs in AAPS, so no basal is counted while the pod gives none;
     * - once per fault ([DashPodStateManager.alarmSynced]): a fault notice with sound (not while a
     *   deactivation waits in the queue) and an announcement in the treatments.
     *
     * Android adds the PDM reference of the fault to the text; the iOS state has no such reference yet.
     */
    suspend fun checkPodKaput() {
        if (!stateManager.isPodKaput) return
        val tbr = pumpSync.expectedPumpState().temporaryBasal
        if (tbr == null || tbr.rate != 0.0) {
            val at = now()
            pumpSync.syncTemporaryBasalWithPumpId(
                timestamp = at,
                rate = PumpRate(0.0),
                duration = PodConstants.MAX_POD_LIFETIME.inWholeMilliseconds,
                isAbsolute = true,
                type = PumpSync.TemporaryBasalType.PUMP_SUSPEND,
                // Android: not used, only unique.
                pumpId = at,
                pumpType = PumpType.OMNIPOD_DASH,
                pumpSerial = serialNumber()
            )
        }
        if (stateManager.alarmSynced) return
        val alarm = stateManager.alarmType ?: return
        val code = (alarm.value.toInt() and 0xff).toString().padStart(3, '0')
        val faultText = DashStrings.omnipod_dash_ios_pod_fault.withArgs(code, alarm.toString())
        if (!commandQueue().isCustomCommandInQueue(CommandDeactivatePod::class)) {
            showNotification(NotificationId.OMNIPOD_POD_FAULT, faultText, AlarmSound.BOLUS_ERROR)
        }
        pumpSync.insertAnnouncement(error = rh.gs(faultText), pumpId = now(), pumpType = PumpType.OMNIPOD_DASH, pumpSerial = serialNumber())
        stateManager.updateAlarmSynced(true)
    }

    // ------------------------------------------------------------------ notices of a call

    /**
     * Android `suspendDeliveryIfActive`, doOnComplete: a notice when the suspend a basal program call
     * sent before the program ([before] is the open command before the call) stays not confirmed. For
     * `setNewBasalProfile` and the time change.
     */
    fun afterBasalProgram(before: DashActiveCommand?) {
        if (commandMadeSince(stateManager, before)?.kind == DashCommandKind.SUSPEND_DELIVERY) {
            notifyOnUnconfirmed(NotificationId.FAILED_UPDATE_PROFILE, DashStrings.omnipod_dash_ios_suspend_unconfirmed, AlarmSound.BOLUS_ERROR, NotificationLevel.IMPORTANT)
        }
    }

    /** Android `notifyOnUnconfirmed`: the notice only while a command is still not confirmed. */
    fun notifyOnUnconfirmed(id: NotificationId, text: TextRef, sound: AlarmSound?, level: NotificationLevel = NotificationLevel.IMPORTANT) {
        if (stateManager.activeCommand == null) return
        aapsLogger.debug(LTag.PUMP, "Notification for active command: ${stateManager.activeCommand}")
        showNotification(id, text, sound, level)
    }

    /**
     * Android `deliverTreatment`, `doFinally`: an SMB that is not confirmed gets a notice, any other
     * bolus an alarm. As Android it looks at any open command, also one from before the call.
     */
    fun afterBolus(isSmb: Boolean, units: Double) {
        if (isSmb) {
            val amount = rh.gs(InterfacesStrings.format_insulin_units, units)
            notifyOnUnconfirmed(
                NotificationId.OMNIPOD_UNCERTAIN_SMB,
                DashStrings.omnipod_dash_ios_smb_uncertain.withArgs(amount),
                AlarmSound.BOLUS_ERROR
            )
        } else if (stateManager.activeCommand != null) {
            showErrorDialog(DashStrings.omnipod_dash_ios_bolus_uncertain, bolusErrorSound())
        }
    }

    /** Android `showNotification`: the sound only when its user setting allows it. */
    fun showNotification(id: NotificationId, text: TextRef, sound: AlarmSound?, level: NotificationLevel = id.defaultLevel) {
        notificationManager.post(id, text, level = level, sound = if (sound != null && soundEnabledFor(id)) sound else null)
    }

    /** Android `showErrorDialog`: `uiInteraction.runAlarm` with the "Error" title. */
    private fun showErrorDialog(text: TextRef, sound: AlarmSound?) {
        uiInteraction().runAlarm(rh.gs(text), rh.gs(DashStrings.omnipod_dash_ios_error), sound)
    }

    /** Android `hasBolusErrorBeepEnabled`. */
    private fun bolusErrorSound(): AlarmSound? = if (readBoolean(DashSoundKey.SoundUncertainBolusNotification)) AlarmSound.BOLUS_ERROR else null

    /** Android `soundEnabledForNotificationType`. */
    private fun soundEnabledFor(id: NotificationId): Boolean = when (id) {
        NotificationId.OMNIPOD_TBR_ALERTS    -> readBoolean(DashSoundKey.SoundUncertainTbrNotification)
        NotificationId.OMNIPOD_UNCERTAIN_SMB -> readBoolean(DashSoundKey.SoundUncertainSmbNotification)
        NotificationId.OMNIPOD_POD_SUSPENDED -> readBoolean(DashSoundKey.SoundDeliverySuspendedNotification)
        else                                 -> true
    }

    // ------------------------------------------------------------------ status checker

    /**
     * One run of the Android status checker (every minute): a status read when a command is not
     * confirmed and the queue is idle, the pod warnings, and the zero temp basal while no pod runs.
     */
    suspend fun statusCheck() {
        refreshStatusOnUnacknowledgedCommands()
        updatePodWarnings()
        aapsLogger.info(LTag.PUMP, "statusChecker")
        try {
            writeNoPodTempBasal(stateManager.isPodRunning, pumpSync, now(), aapsLogger)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.warn(LTag.PUMP, "Error on createFakeTBRWhenNoActivePod=$e")
        }
    }

    /** Android `refreshStatusOnUnacknowledgedCommands`. */
    fun refreshStatusOnUnacknowledgedCommands() {
        val queue = commandQueue()
        if (stateManager.isPodRunning && stateManager.activeCommand != null && queue.size() == 0 && queue.performing() == null) {
            launch { commandQueue().readStatus(rh.gs(DashStrings.omnipod_dash_ios_unconfirmed_command)) }
        }
    }

    /**
     * Android `updatePodWarnings`, at most every 15 minutes: "no active pod", or "delivery suspended"
     * with sound. Android also warns when the time zone of the pod differs from the phone; the iOS state
     * keeps no time zone (time zone and DST changes are ignored, as Android `timezoneOrDSTChanged`).
     */
    fun updatePodWarnings() {
        val t = now()
        if (t <= nextPodWarningCheck) return
        if (!stateManager.isPodRunning) {
            notificationManager.post(NotificationId.OMNIPOD_POD_NOT_ATTACHED, DashStrings.omnipod_dash_ios_no_active_pod)
        } else {
            notificationManager.dismiss(NotificationId.OMNIPOD_POD_NOT_ATTACHED)
            if (stateManager.isSuspended) {
                showNotification(NotificationId.OMNIPOD_POD_SUSPENDED, DashStrings.omnipod_dash_ios_delivery_suspended, AlarmSound.BOLUS_ERROR)
            } else {
                notificationManager.dismiss(NotificationId.OMNIPOD_POD_SUSPENDED)
            }
        }
        nextPodWarningCheck = t + POD_WARNING_INTERVAL_MS
    }

    // ------------------------------------------------------------------ queue commands

    /** The user alert settings, Android keys and defaults ([DashAlertIntKey.alertSettings]). */
    fun alertSettings(): DashAlertSettings = DashAlertIntKey.alertSettings(readBoolean, readInt)

    /**
     * Android `executeCustomCommand`, the same mapping. A command the Dash plugin does not know (also
     * `CommandSuspendDelivery` and the basal correction, see the plan) is a failure, as Android.
     * Runs on the queue thread, blocking, as Android.
     */
    fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult = runBlocking {
        val result = when (customCommand) {
            is CommandSilenceAlerts            -> driver.silenceAlerts()
            is CommandResumeDelivery           -> resumeDelivery()
            is CommandDeactivatePod            -> deactivatePod()
            is CommandHandleTimeChange         -> handleTimeChange()
            is CommandUpdateAlertConfiguration -> driver.updateAlertConfiguration(alertSettings())
            is CommandPlayTestBeep             -> driver.playTestBeep()
            is CommandDisableSuspendAlerts     -> driver.disableSuspendAlerts()
            else                               -> {
                aapsLogger.warn(LTag.PUMP, "Unsupported custom command: ${customCommand::class.simpleName}")
                return@runBlocking DashPumpEnactResult(success = false, enacted = false, comment = "Unsupported custom command: ${customCommand.statusDescription}")
            }
        }
        afterCommand()
        result
    }

    /**
     * Android `resumeDelivery`: the profile AAPS expects (`expectedPumpState().profile`) to a suspended
     * pod; no profile: failure. A resume that is not confirmed gets a notice.
     */
    private suspend fun resumeDelivery(): PumpEnactResult {
        val profile = pumpSync.expectedPumpState().profile
            ?: return DashPumpEnactResult(success = false, enacted = false, comment = "No profile active")
        val result = driver.resumeDelivery(profile)
        notifyOnUnconfirmed(NotificationId.FAILED_UPDATE_PROFILE, DashStrings.omnipod_dash_ios_resume_unconfirmed, AlarmSound.BOLUS_ERROR)
        return result
    }

    /** Android `deactivatePod`: when the pod is gone the fault notice goes too. */
    private suspend fun deactivatePod(): PumpEnactResult {
        val result = driver.deactivatePod()
        if (result.success) notificationManager.dismiss(NotificationId.OMNIPOD_POD_FAULT)
        return result
    }

    /**
     * Android `handleTimeChange`: the basal program of the profile AAPS expects, sent again, with the
     * notice of a suspend that is not confirmed ([afterBasalProgram]), as Android `setNewBasalProfile`.
     */
    private suspend fun handleTimeChange(): PumpEnactResult {
        val profile = pumpSync.expectedPumpState().profile
            ?: return DashPumpEnactResult(success = false, enacted = false, comment = "No profile active")
        val before = stateManager.activeCommand
        timeChangeRunning = true
        val result = try {
            driver.setNewBasalProfile(profile)
        } finally {
            timeChangeRunning = false
        }
        afterBasalProgram(before)
        return result
    }

    // ------------------------------------------------------------------ activation (for the wizard)

    /**
     * The Android wizard `doInitializePod`: part 1 with the low reservoir alert of the user settings
     * (none when it is off), and the settings stored when part 1 is done.
     */
    suspend fun activatePodPart1(): PumpEnactResult {
        val low = alertSettings().lowReservoir
        val trigger = if (low.enabled) AlertTrigger.ReservoirVolumeTrigger((low.units * 10).toShort()) else null
        return driver.activatePodPart1(trigger, low)
    }

    /**
     * The Android wizard `doInsertCannula`: part 2 with the basal program of the profile AAPS expects and
     * the expiration alerts of the user settings. When it is done, as Android: the zero temp basal of
     * "no pod" is stopped (serial "4241"), the new pod is registered (`connectNewPump`), the cannula and
     * insulin changes are recorded with the serial of the pod, the "no active pod" notice goes, and a
     * status is read through the queue.
     */
    suspend fun activatePodPart2(): PumpEnactResult {
        val profile = pumpSync.expectedPumpState().profile
            ?: return DashPumpEnactResult(success = false, enacted = false, comment = "No profile set")
        val basalProgram = try {
            mapProfileToBasalProgram(profile, PumpType.OMNIPOD_DASH)
        } catch (e: IllegalArgumentException) {
            return DashPumpEnactResult(success = false, enacted = false, comment = "Basal program refused: ${e.message}")
        }
        val expiration = alertSettings().expiration
        val result = driver.activatePodPart2(
            basalProgram,
            if (expiration.reminderEnabled) expiration.reminderHours.toLong() else null,
            if (expiration.alarmEnabled) expiration.alarmHours.toLong() else null,
            expiration,
        )
        if (!result.success) return result
        val at = now()
        pumpSync.syncStopTemporaryBasalWithPumpId(
            timestamp = at,
            endPumpId = at,
            pumpType = PumpType.OMNIPOD_DASH,
            pumpSerial = IosDashPluginPump.PUMP_SERIAL_FOR_FAKE_TBR
        )
        pumpSync.connectNewPump()
        val podSerial = stateManager.uniqueId?.toString() ?: "n/a"
        pumpSync.insertTherapyEventIfNewWithTimestamp(timestamp = at, type = TE.Type.CANNULA_CHANGE, pumpType = PumpType.OMNIPOD_DASH, pumpSerial = podSerial)
        pumpSync.insertTherapyEventIfNewWithTimestamp(timestamp = at, type = TE.Type.INSULIN_CHANGE, pumpType = PumpType.OMNIPOD_DASH, pumpSerial = podSerial)
        notificationManager.dismiss(NotificationId.OMNIPOD_POD_NOT_ATTACHED)
        launch { commandQueue().readStatus(rh.gs(DashStrings.omnipod_dash_ios_pod_activated)) }
        return result
    }

    /** The Android wizard and overview "Discard pod": the pod state is cleared and the fault notice goes. */
    suspend fun discardPod() {
        driver.discardPod()
        notificationManager.dismiss(NotificationId.OMNIPOD_POD_FAULT)
    }

    private companion object {

        /** Android `DateTimeUtil.getTimeInFutureFromMinutes(15)`. */
        const val POD_WARNING_INTERVAL_MS = 15 * 60_000L
    }
}

/**
 * The open command, only when it is not [before]: a call refused because an earlier command is still
 * not confirmed leaves that earlier command open, and it must keep its own type.
 */
internal fun commandMadeSince(stateManager: DashPodStateManager, before: DashActiveCommand?): DashActiveCommand? {
    val after = stateManager.activeCommand ?: return null
    val same = before != null && after.sequence == before.sequence && after.historyId == before.historyId && after.createdAtMs == before.createdAtMs
    return if (same) null else after
}
