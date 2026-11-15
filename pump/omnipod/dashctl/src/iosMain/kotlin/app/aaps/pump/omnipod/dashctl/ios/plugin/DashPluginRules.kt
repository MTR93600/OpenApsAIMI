package app.aaps.pump.omnipod.dashctl.ios.plugin

import app.aaps.core.data.model.TE
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.notifications.AlarmSound
import app.aaps.core.interfaces.notifications.NotificationAction
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
import app.aaps.pump.omnipod.dashctl.history.DashHistoryType
import app.aaps.pump.omnipod.dashctl.history.DashPodHistory
import app.aaps.pump.omnipod.dashctl.ios.pump.DashBolusEndedByFault
import app.aaps.pump.omnipod.dashctl.ios.pump.DashCommandOutcome
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpEnactResult
import app.aaps.pump.omnipod.dashctl.ios.pump.DashPumpOutcome
import app.aaps.pump.omnipod.dashctl.ios.pump.IosDashPump
import app.aaps.pump.omnipod.dashctl.keys.DashAlertIntKey
import app.aaps.pump.omnipod.dashctl.keys.DashSoundKey
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.queue.command.CommandAcknowledgeAlert
import app.aaps.pump.omnipod.dashctl.queue.command.CommandDeactivatePod
import app.aaps.pump.omnipod.dashctl.queue.command.CommandDeliverBasalCorrection
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
import app.aaps.pump.omnipod.dashctl.ui.dashAlertText
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
 * Ported in step P4: the basal drift correction of this fork ([CommandDeliverBasalCorrection], queued
 * in [onOutcome]), the time zone notice ([updatePodWarnings]) and the PDM reference in the fault text
 * ([checkPodKaput]).
 *
 * Known gap from Android, kept on purpose: during the bolus wait Android runs `verifyPumpState` at
 * each status read. Here it runs after the bolus call returns ([afterCommand]). Its second branch (AAPS
 * expects no temp basal, the pod runs one) stops the temp basal on the pod
 * ([IosDashPump.cancelTempBasalNotExpected]), a command that needs the command lock, and the bolus call
 * holds that lock for the whole wait. Running it from inside the wait would need a second way into the
 * pod past the lock, which is exactly what the lock is there to stop (two commands at once, a stop
 * sent while the bolus is not decided). Its first branch (AAPS expects a temp basal, the pod runs the
 * program) only writes to `PumpSync`; doing that alone inside the wait would split one Android rule in
 * two places, for a few seconds at most. What can change insulin counted at once does run at once: the
 * fault part (`checkPodKaput`), see [onOutcome].
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
    /** The pod history: the wizard parts and the discard are written here (Android INITIALIZE_POD, INSERT_CANNULA, DISCARD_POD). */
    private val history: DashPodHistory? = null,
) {

    /** Android `nextPodWarningCheck`: the pod warnings are checked at most every 15 minutes. */
    private var nextPodWarningCheck = 0L

    /**
     * True while [handleTimeChange] sends its basal program. Android records that program as SET_TIME
     * and asks for no profile change when it is confirmed; the iOS driver stores no such kind.
     */
    @Volatile
    private var timeChangeRunning = false

    /**
     * The basal program of a [handleTimeChange] that its call left not confirmed. Android stores such
     * a program as SET_TIME in its history and, when a status confirms it later, asks for no profile
     * change; the iOS driver stores no such kind, so it is kept here, in memory only. After an app
     * restart it is gone, and a late SUCCESS then asks for the profile once more (the queue finds the
     * profile set and sends nothing).
     */
    @Volatile
    private var openTimeChangeProgram: DashActiveCommand? = null

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
        // The time change program its call left open is decided now (it is not open any more after this).
        val lateTimeChange = outcome.confirmation.isFinal && isOpenTimeChangeProgram(outcome.command)
        val confirmed = outcome.confirmation == DashCommandConfirmation.SUCCESS
        if (!confirmed && outcome.confirmation != DashCommandConfirmation.DENIED) return
        when (outcome.command.kind) {
            DashCommandKind.STOP_TEMP_BASAL -> {
                // Android CANCEL_TEMPORARY_BASAL: the drift is checked after a confirmed cancel.
                if (confirmed && stateManager.needsBasalCorrection(now())) {
                    launch { commandQueue().customCommand(CommandDeliverBasalCorrection()) }
                }
                notificationManager.dismiss(NotificationId.OMNIPOD_TBR_ALERTS)
            }

            DashCommandKind.TEMP_BASAL      -> {
                // Android SET_TEMPORARY_BASAL: the drift is checked after a confirmed temp basal, unless
                // a correction already waits in the queue.
                if (confirmed && !commandQueue().isCustomCommandInQueue(CommandDeliverBasalCorrection::class) &&
                    stateManager.needsBasalCorrection(now())
                ) {
                    launch { commandQueue().customCommand(CommandDeliverBasalCorrection()) }
                }
                notificationManager.dismiss(NotificationId.OMNIPOD_TBR_ALERTS)
            }

            DashCommandKind.BOLUS           -> notificationManager.dismiss(NotificationId.OMNIPOD_UNCERTAIN_SMB)

            DashCommandKind.RESUME_DELIVERY,
            DashCommandKind.BASAL_PROGRAM   -> if (confirmed) {
                if (outcome.command.kind == DashCommandKind.BASAL_PROGRAM && !timeChangeRunning &&
                    !lateTimeChange &&
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
     * result never depends on them. Then the pod alerts of the last status ([updatePodAlerts]), which
     * is not in Android; [silenceAcknowledged] is false after a single alert silence itself, so a
     * silence that fails is not sent again and again in a row.
     */
    suspend fun afterCommand(silenceAcknowledged: Boolean = true) {
        try {
            verifyPumpState()
            checkPodKaput()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Dash: check after the command failed", e)
        }
        try {
            updatePodAlerts(silenceAcknowledged)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Dash: check of the pod alerts failed", e)
        }
        rxBus().send(EventRefreshOverview("Dash command", false))
    }

    // ------------------------------------------------------------------ pod alerts

    /**
     * The alerts in the pod alert notice now. In memory only: notices do not survive an app restart,
     * so after one every active alert the user did not silence is shown again.
     */
    @Volatile
    private var alertsInNotice: Set<AlertType> = emptySet()

    /**
     * The pod alerts of the last status on the phone, at every status read and command ([afterCommand])
     * and every run of the status checker ([statusCheck]). Not in Android, which shows the active
     * alerts only on the pump screen (and Eros only as one notice with no way to silence one). From
     * OmnipodKit (Trio) `alertsChanged` -> `issueAlert`, `acknowledgeAlert` and
     * `silenceAcknowledgedAlerts`. Deliberate difference from Android: without it an alert the pod
     * beeps (low reservoir, pod expiry) is not seen on the phone, and a silence of one alert that did
     * not reach the pod is a user action lost.
     * - The alerts of a pod that is activated and runs count; none for any other pod (no pod, a pod in
     *   alarm: the fault notice covers that one).
     * - An alert the user silenced ([DashPodStateManager.acknowledgedAlerts]) that the pod no longer
     *   reports is forgotten.
     * - The other active alerts are in one notice (NotificationId.OMNIPOD_POD_ALERTS, the id Android
     *   Eros uses for its pod alerts, at its level and with no sound, as Eros: the pod beeps itself),
     *   with Android's alert names ([dashAlertText]) and one "Silence" button for each alert that can
     *   be silenced ([acknowledgeAlert]). The notice is posted again only when that list changes, and
     *   dismissed when it is empty.
     * - [silenceAcknowledged]: one silenced alert the pod still reports is sent again
     *   ([silenceOneAcknowledgedAlert]).
     */
    fun updatePodAlerts(silenceAcknowledged: Boolean) {
        val active = if (stateManager.isActivatedAndRunning) stateManager.activeAlerts.orEmpty() - AlertType.UNKNOWN else emptySet()
        stateManager.forgetAcknowledgedAlertsExcept(active)
        val acknowledged = stateManager.acknowledgedAlerts
        val shown = active - acknowledged
        if (shown != alertsInNotice) {
            if (shown.isEmpty()) {
                notificationManager.dismiss(NotificationId.OMNIPOD_POD_ALERTS)
            } else {
                val ordered = shown.sortedBy { it.ordinal }
                aapsLogger.info(LTag.PUMP, "Dash: pod alerts on the phone: $ordered (silenced, not yet on the pod: $acknowledged)")
                notificationManager.post(
                    NotificationId.OMNIPOD_POD_ALERTS,
                    DashStrings.omnipod_dash_ios_pod_alerts.withArgs(ordered.joinToString("\n") { rh.gs(dashAlertText(it)) }),
                    actions = ordered.filter { canBeSilenced(it) }.map { alert ->
                        NotificationAction(DashStrings.omnipod_dash_ios_silence_alert.withArgs(rh.gs(dashAlertText(alert)))) { acknowledgeAlert(alert) }
                    }
                )
            }
            alertsInNotice = shown
        }
        if (silenceAcknowledged) silenceOneAcknowledgedAlert(acknowledged)
    }

    /**
     * The user silenced [alert] from its notice: [CommandAcknowledgeAlert] is queued (it stores the
     * silence again before it connects, see `IosDashPump.acknowledgeAlert`). OmnipodKit `acknowledgeAlert`.
     *
     * Two steps before the queue, both deliberate, so a user action is not lost:
     * - The notice is marked as gone (dismissed, and [alertsInNotice] emptied). The shared UI closes the
     *   whole card on a button tap without telling the plugin, so without this a silence the pod then
     *   refused, or one not sent at all, would leave the pod beeping with no notice on the phone until
     *   the list of alerts changes. The next [updatePodAlerts] posts the alerts that are still shown.
     * - The silence is stored here ([DashPodStateManager.addAcknowledgedAlert]), under the same checks
     *   as the driver (state readable, the pod runs, the alert is active and can be silenced). The queue
     *   refuses a [CommandAcknowledgeAlert] while another one waits (a silence sent again by
     *   [silenceOneAcknowledgedAlert]), and then the driver never sees this tap. Stored, it is sent at a
     *   later status read.
     */
    fun acknowledgeAlert(alert: AlertType) {
        alertsInNotice = emptySet()
        notificationManager.dismiss(NotificationId.OMNIPOD_POD_ALERTS)
        if (alert != AlertType.UNKNOWN && stateManager.reloadIfUnknown() && stateManager.isActivatedAndRunning &&
            stateManager.activeAlerts?.contains(alert) == true && canBeSilenced(alert)
        ) {
            stateManager.addAcknowledgedAlert(alert)
        }
        launch { commandQueue().customCommand(CommandAcknowledgeAlert(alert)) }
    }

    /** As OmnipodKit: "suspend ended" is not silenced while delivery is suspended; it stops with the resume. */
    private fun canBeSilenced(alert: AlertType): Boolean = !(alert == AlertType.SUSPEND_ENDED && stateManager.isSuspended)

    /**
     * OmnipodKit `silenceAcknowledgedAlerts`: one silenced alert the pod still reports (more than one
     * is rare) is queued again, after a status read. Only when the pod answered just now (the last
     * status is at most [ACKNOWLEDGE_RETRY_WINDOW_MS] old), no command is open and no single silence
     * already waits in the queue, so a pod out of range does not fill the queue with silences that
     * cannot reach it.
     */
    private fun silenceOneAcknowledgedAlert(acknowledged: Set<AlertType>) {
        val alert = acknowledged.minByOrNull { it.ordinal } ?: return
        val status = stateManager.podStatus ?: return
        if (now() - status.receivedAtMs > ACKNOWLEDGE_RETRY_WINDOW_MS) return
        if (stateManager.activeCommand != null) return
        if (commandQueue().isCustomCommandInQueue(CommandAcknowledgeAlert::class)) return
        aapsLogger.info(LTag.PUMP, "Dash: the silence of $alert did not reach the pod yet, sent again")
        launch { commandQueue().customCommand(CommandAcknowledgeAlert(alert)) }
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
     * - a PUMP_SUSPEND temp basal of 0 U/h for the pod life (80 h), unless a zero temp basal already
     *   runs in AAPS, so no basal is counted while the pod gives none. It starts at the fault time of the
     *   pod ([suspendTimeOfFault]) when the pod gave one, else at the time now (Android);
     * - once per fault ([DashPodStateManager.alarmSynced]): a fault notice with sound (not while a
     *   deactivation waits in the queue) and an announcement in the treatments.
     *
     * The text has the PDM reference of the fault when the pod gave one ([DashPodStateManager.pdmRef]),
     * as Android.
     */
    suspend fun checkPodKaput() {
        if (!stateManager.isPodKaput) return
        val tbr = pumpSync.expectedPumpState().temporaryBasal
        if (tbr == null || tbr.rate != 0.0) {
            val at = suspendTimeOfFault(tbr?.timestamp)
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
        val faultText = dashFaultText(code, alarm.toString(), stateManager.pdmRef)
        if (!commandQueue().isCustomCommandInQueue(CommandDeactivatePod::class)) {
            showNotification(NotificationId.OMNIPOD_POD_FAULT, faultText, AlarmSound.BOLUS_ERROR)
        }
        pumpSync.insertAnnouncement(error = rh.gs(faultText), pumpId = now(), pumpType = PumpType.OMNIPOD_DASH, pumpSerial = serialNumber())
        stateManager.updateAlarmSynced(true)
    }

    /**
     * When the PUMP_SUSPEND temp basal of a faulted pod starts: the time the fault stopped delivery
     * ([DashPodStateManager.faultTimeMs]), as OmnipodKit (Trio) `PodCommsSession.setDeliveryStoppedAt`
     * with `faultEventTimeSinceActivation`. Deliberate difference from Android, which writes it at the
     * time the phone sees the fault: when that is late (the phone was out of range), all the basal
     * between the fault and then is counted as given while the pod gave none (insulin counted wrong).
     *
     * The time now when the pod gave no fault time, or a fault time later than now. Never at or before
     * the start of the temp basal running in AAPS ([runningSince]): `PumpSync` ends a running temp basal
     * only at a later time, and an earlier one would leave both counted at once.
     */
    private fun suspendTimeOfFault(runningSince: Long?): Long {
        val nowMs = now()
        val fault = stateManager.faultTimeMs?.takeIf { it <= nowMs } ?: return nowMs
        return if (runningSince != null && fault <= runningSince) minOf(runningSince + 1, nowMs) else fault
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

    /**
     * [afterBasalProgram], then the result of a basal program call ([before] is the open command before
     * the call). When the program itself stays not confirmed, the comment is Android's
     * (`failWhenUnconfirmed`): the queue shows it in the FAILED_UPDATE_PROFILE notice. Android picks
     * "Failed to set the new basal profile. Delivery suspended" when the suspend before was confirmed,
     * but it reads that flag when the command chain is built, before the suspend ran, so it always
     * shows "Setting basal profile might have failed"; the same text is given here.
     */
    fun basalProgramResult(before: DashActiveCommand?, result: PumpEnactResult): PumpEnactResult {
        afterBasalProgram(before)
        if (!result.success && commandMadeSince(stateManager, before)?.kind == DashCommandKind.BASAL_PROGRAM) {
            result.comment(rh.gs(DashStrings.omnipod_dash_ios_basal_profile_might_have_failed))
        }
        return result
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
     * Also the pod alerts of the stored status ([updatePodAlerts], not in Android), so after an app
     * restart they are on the phone again before the next status read. Nothing is sent from here.
     */
    suspend fun statusCheck() {
        if (!podStateKnown()) return
        refreshStatusOnUnacknowledgedCommands()
        updatePodWarnings()
        try {
            updatePodAlerts(silenceAcknowledged = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.error(LTag.PUMP, "Dash: check of the pod alerts failed", e)
        }
        aapsLogger.info(LTag.PUMP, "statusChecker")
        try {
            writeNoPodTempBasal(stateManager.isPodRunning, pumpSync, now(), aapsLogger)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            aapsLogger.warn(LTag.PUMP, "Error on createFakeTBRWhenNoActivePod=$e")
        }
    }

    /**
     * True when the stored pod state is known ([IosDashPump.isPodStateKnown], which also reads it
     * again). While it is not, a pod may run on the body with a key this phone cannot read now: the
     * status checker then does nothing (no "no pod" temp basal, no "no active pod" notice), an urgent
     * notice says why, and the error is logged. The notice goes once the state is read. Deliberate
     * difference from Android, which has no such read failure (see `DashPodStateManager.isStateKnown`).
     */
    fun podStateKnown(): Boolean {
        if (driver.isPodStateKnown()) {
            if (stateUnreadableNoticeShown) {
                stateUnreadableNoticeShown = false
                notificationManager.dismiss(NotificationId.PUMP_ERROR)
            }
            return true
        }
        aapsLogger.error(LTag.PUMP, "Dash status check skipped: the stored pod state cannot be read (${stateManager.stateUnreadableReason})")
        stateUnreadableNoticeShown = true
        notificationManager.post(NotificationId.PUMP_ERROR, DashStrings.omnipod_dash_ios_pod_state_unreadable)
        return false
    }

    /** The notice of [podStateKnown] is shown, so it is dismissed once the state is read. */
    @Volatile
    private var stateUnreadableNoticeShown = false

    /** Android `refreshStatusOnUnacknowledgedCommands`. */
    fun refreshStatusOnUnacknowledgedCommands() {
        val queue = commandQueue()
        if (stateManager.isPodRunning && stateManager.activeCommand != null && queue.size() == 0 && queue.performing() == null) {
            launch { commandQueue().readStatus(rh.gs(DashStrings.omnipod_dash_ios_unconfirmed_command)) }
        }
    }

    /**
     * The pod showed a fault in its advertisement while it was not connected (the fault watch of
     * `DashHeldConnection`, given by [IosDashPump.setPodFaultListener]): a status read is queued at
     * once, so the alarm is read and handled now ([checkPodKaput] after the read: the bolus the fault
     * stopped, the PUMP_SUSPEND temp basal, the fault notice), as OmnipodKit (Trio)
     * `omnipodDidDetectAlert` reads the pod status. Deliberate difference from Android (insulin counted
     * wrong): Android learns of the fault only at the next status read, and until then counts basal
     * the pod does not give. Nothing before the activation is completed: the status read does nothing
     * then either (`getPumpStatus`).
     */
    fun onPodFaultAdvertised() {
        if (!stateManager.isActivationCompleted) {
            aapsLogger.info(LTag.PUMP, "Dash fault advertisement ignored: no activated pod")
            return
        }
        aapsLogger.warn(LTag.PUMP, "Dash pod advertises a fault: status read queued")
        launch { commandQueue().readStatus(rh.gs(DashStrings.omnipod_dash_ios_pod_fault_seen)) }
    }

    /**
     * Android `updatePodWarnings`, at most every 15 minutes: "no active pod", or "delivery suspended"
     * with sound, or, for a running pod that is not suspended, a notice when the time zone of the pod
     * (stored when it took its last basal program) is not the time zone of the phone now
     * ([DashPodStateManager.sameTimeZone]). The notice is not dismissed here, as Android; a confirmed
     * basal program or resume dismisses it ([onOutcome]).
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
                if (!stateManager.sameTimeZone(t)) {
                    notificationManager.post(NotificationId.OMNIPOD_TIME_OUT_OF_SYNC, DashStrings.omnipod_dash_ios_time_zone_different, level = NotificationLevel.NORMAL)
                }
            }
        }
        nextPodWarningCheck = t + POD_WARNING_INTERVAL_MS
    }

    // ------------------------------------------------------------------ queue commands

    /** The user alert settings, Android keys and defaults ([DashAlertIntKey.alertSettings]). */
    fun alertSettings(): DashAlertSettings = DashAlertIntKey.alertSettings(readBoolean, readInt)

    /**
     * Android `executeCustomCommand`, the same mapping. A command the Dash plugin does not know (also
     * `CommandSuspendDelivery`) is a failure, as Android.
     * Runs on the queue thread, blocking, as Android.
     */
    fun executeCustomCommand(customCommand: CustomCommand): PumpEnactResult = runBlocking {
        val result = when (customCommand) {
            is CommandSilenceAlerts            -> driver.silenceAlerts()
            is CommandAcknowledgeAlert         -> driver.acknowledgeAlert(customCommand.alert)
            is CommandResumeDelivery           -> resumeDelivery()
            is CommandDeactivatePod            -> deactivatePod()
            is CommandHandleTimeChange         -> handleTimeChange()
            is CommandUpdateAlertConfiguration -> driver.updateAlertConfiguration(alertSettings())
            is CommandPlayTestBeep             -> driver.playTestBeep()
            is CommandDisableSuspendAlerts     -> driver.disableSuspendAlerts()
            is CommandDeliverBasalCorrection   -> driver.deliverBasalCorrection()
            else                               -> {
                aapsLogger.warn(LTag.PUMP, "Unsupported custom command: ${customCommand::class.simpleName}")
                return@runBlocking DashPumpEnactResult(success = false, enacted = false, comment = "Unsupported custom command: ${customCommand.statusDescription}")
            }
        }
        afterCommand(silenceAcknowledged = customCommand !is CommandAcknowledgeAlert)
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
            commandMadeSince(stateManager, before)?.takeIf { it.kind == DashCommandKind.BASAL_PROGRAM }?.let { openTimeChangeProgram = it }
            timeChangeRunning = false
        }
        return basalProgramResult(before, result)
    }

    /**
     * True when [command] is the program of a time change that its call left open
     * ([openTimeChangeProgram]); it is then forgotten, as it is decided now.
     */
    private fun isOpenTimeChangeProgram(command: DashActiveCommand): Boolean {
        val open = openTimeChangeProgram ?: return false
        val same = open.kind == command.kind && open.sequence == command.sequence && open.createdAtMs == command.createdAtMs
        if (same) openTimeChangeProgram = null
        return same
    }

    // ------------------------------------------------------------------ activation (for the wizard)

    /**
     * The Android wizard `doInitializePod`: part 1 with the low reservoir alert of the user settings
     * (none when it is off), and the settings stored when part 1 is done.
     */
    suspend fun activatePodPart1(): PumpEnactResult {
        val low = alertSettings().lowReservoir
        val trigger = if (low.enabled) AlertTrigger.ReservoirVolumeTrigger((low.units * 10).toShort()) else null
        val result = driver.activatePodPart1(trigger, low)
        history?.addActivationStep(DashHistoryType.INITIALIZE_POD, result.success)
        return result
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
        history?.addActivationStep(DashHistoryType.INSERT_CANNULA, result.success)
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

    /**
     * The Android wizard and overview "Discard pod": the pod state is cleared and the fault notice goes.
     * Throws [app.aaps.pump.omnipod.dashctl.session.DashPodStateUnknownException], with nothing
     * changed and nothing recorded, while the stored pod state cannot be read ([IosDashPump.discardPod]).
     */
    suspend fun discardPod() {
        driver.discardPod()
        history?.addActivationStep(DashHistoryType.DISCARD_POD, success = true)
        notificationManager.dismiss(NotificationId.OMNIPOD_POD_FAULT)
    }

    private companion object {

        /** Android `DateTimeUtil.getTimeInFutureFromMinutes(15)`. */
        const val POD_WARNING_INTERVAL_MS = 15 * 60_000L

        /** A silenced alert is sent again only when the last status is at most this old: the pod is in reach. */
        const val ACKNOWLEDGE_RETRY_WINDOW_MS = 2 * 60_000L
    }
}

/**
 * The fault text of the notice and the announcement: Android
 * `omnipod_common_pod_status_pod_fault_description`, plus the PDM reference on a new line when there is
 * one. One template for each case, not two texts joined.
 */
internal fun dashFaultText(code: String, name: String, pdmRef: String?): TextRef =
    if (pdmRef != null) DashStrings.omnipod_dash_ios_pod_fault_with_ref.withArgs(code, name, pdmRef)
    else DashStrings.omnipod_dash_ios_pod_fault.withArgs(code, name)

/**
 * The open command, only when it is not [before]: a call refused because an earlier command is still
 * not confirmed leaves that earlier command open, and it must keep its own type.
 */
internal fun commandMadeSince(stateManager: DashPodStateManager, before: DashActiveCommand?): DashActiveCommand? {
    val after = stateManager.activeCommand ?: return null
    val same = before != null && after.sequence == before.sequence && after.historyId == before.historyId && after.createdAtMs == before.createdAtMs
    return if (same) null else after
}
