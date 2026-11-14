package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusSize
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.queue.command.CommandPlayTestBeep
import app.aaps.pump.omnipod.dashctl.queue.command.CommandResumeDelivery
import app.aaps.pump.omnipod.dashctl.queue.command.CommandSilenceAlerts
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import kotlin.reflect.KClass

/**
 * The buttons of the pod screen, the ones Android `DashOverviewViewModel` shows for Dash. Not here:
 * "Suspend" (always hidden for Dash on Android too), "Set time" (the iPhone does not store the pod
 * time zone yet, see P2b of the plan), "Pod history" (no pod history on the iPhone).
 */
enum class DashOverviewAction { REFRESH, SILENCE_ALERTS, RESUME_DELIVERY, ACTIVATE_POD, DEACTIVATE_POD, PLAY_TEST_BEEP, DISCARD_POD }

/** One button: shown or not, and whether it can be pressed. */
data class DashOverviewButton(val action: DashOverviewAction, val visible: Boolean, val enabled: Boolean = true)

/**
 * Android `buildPrimaryActions` and `buildManagementActions`, the same rules:
 * - Refresh: from SET_UNIQUE_ID on, with an empty queue;
 * - Silence alerts: a running pod with alerts (or a silence waiting in the queue), empty queue;
 * - Resume delivery: a running pod that is suspended (or a resume waiting in the queue), empty queue;
 * - Activate pod: the activation is not completed;
 * - Deactivate pod: as soon as a pod is paired (an address or a key is stored), not only after the
 *   activation, so a pod that faults during the activation can be removed (#1217);
 * - Play test beep: from PHASE_1_COMPLETED on, not while one waits in the queue;
 * - Discard pod: a pod id is stored but SET_UNIQUE_ID was not reached.
 *
 * @param queueEmpty Android `commandQueue.size() == 0 && commandQueue.performing() == null`
 * @param inQueue Android `commandQueue.isCustomCommandInQueue`
 */
fun dashOverviewButtons(
    state: DashPodStateManager,
    queueEmpty: Boolean,
    inQueue: (KClass<out CustomCommand>) -> Boolean
): List<DashOverviewButton> {
    val progress = state.activationProgress
    val phase1Done = progress.isAtLeast(ActivationProgress.PHASE_1_COMPLETED)
    return listOf(
        DashOverviewButton(DashOverviewAction.REFRESH, visible = true, enabled = progress.isAtLeast(ActivationProgress.SET_UNIQUE_ID) && queueEmpty),
        DashOverviewButton(
            DashOverviewAction.SILENCE_ALERTS,
            visible = state.isPodRunning && (state.activeAlerts?.isNotEmpty() == true || inQueue(CommandSilenceAlerts::class)),
            enabled = queueEmpty
        ),
        DashOverviewButton(
            DashOverviewAction.RESUME_DELIVERY,
            visible = state.isPodRunning && (state.isSuspended || inQueue(CommandResumeDelivery::class)),
            enabled = queueEmpty
        ),
        DashOverviewButton(DashOverviewAction.ACTIVATE_POD, visible = !state.isActivationCompleted),
        DashOverviewButton(DashOverviewAction.DEACTIVATE_POD, visible = state.bluetoothAddress != null || state.ltk != null),
        DashOverviewButton(DashOverviewAction.PLAY_TEST_BEEP, visible = phase1Done, enabled = phase1Done && !inQueue(CommandPlayTestBeep::class)),
        DashOverviewButton(DashOverviewAction.DISCARD_POD, visible = state.uniqueId != null && progress.isBefore(ActivationProgress.SET_UNIQUE_ID))
    )
}

/** The buttons of the first row (pod commands), the rest are pod management, as Android. */
val DashOverviewAction.isPrimary: Boolean
    get() = this == DashOverviewAction.REFRESH || this == DashOverviewAction.SILENCE_ALERTS || this == DashOverviewAction.RESUME_DELIVERY

/** The label of each button. */
val DashOverviewAction.label: TextRef
    get() = when (this) {
        DashOverviewAction.REFRESH         -> CoreUiStrings.refresh
        DashOverviewAction.SILENCE_ALERTS  -> DashStrings.dash_button_silence_alerts
        DashOverviewAction.RESUME_DELIVERY -> DashStrings.dash_button_resume_delivery
        DashOverviewAction.ACTIVATE_POD    -> DashStrings.dash_button_activate_pod
        DashOverviewAction.DEACTIVATE_POD  -> DashStrings.dash_button_deactivate_pod
        DashOverviewAction.PLAY_TEST_BEEP  -> DashStrings.dash_button_play_test_beep
        DashOverviewAction.DISCARD_POD     -> DashStrings.dash_button_discard_pod
    }

/**
 * How the pod screen writes times and amounts. On the iPhone it is `DateUtil` and
 * `ConcentrationHelper`, as Android; tests give a plain one.
 */
interface DashOverviewFormat {

    fun dateAndTime(ms: Long): String

    /** An amount of insulin with its unit, as the pump counts it. */
    fun insulin(units: Double): String

    /** A bolus amount with how long ago it was (Android `insulinAmountAgoString`). */
    fun insulinAgo(units: Double, atMs: Long): String

    /** A basal rate with its unit. */
    fun basalRate(rate: Double): String

    /** A running temp basal: rate, start and duration (Android `basalTbrString`). */
    fun tempBasal(rate: Double, startMs: Long, minutes: Int): String

    /** How long ago [ms] was. */
    fun ago(ms: Long): String

    /** The time left until something, [durationMs] from now. */
    fun timeRemaining(durationMs: Long): String
}

private const val PLACEHOLDER = "-"

/** Android `POD_GRACE_PERIOD_HOURS`: the pod delivers for 8 h after the expiry it reports. */
private const val POD_GRACE_PERIOD_MS = 8 * 60 * 60_000L

/** Android `PodExpiryRows.WARNING_TIME`. */
private const val EXPIRY_WARNING_MS = 4 * 60 * 60_000L

/**
 * The rows of the pod screen, Android `DashOverviewViewModel.buildInfoRows` for what the iPhone
 * stores. Not here, because the iPhone does not keep it yet: the connection quality (connection
 * counters), the time on the pod and its time zone (P2b), the PDM style fault text (no PDM reference).
 */
fun dashOverviewRows(state: DashPodStateManager, rh: TextResolver, format: DashOverviewFormat, nowMs: Long): List<PumpInfoRow> = buildList {
    add(PumpInfoRow(label = rh.gs(DashStrings.dash_overview_bluetooth_address), value = state.bluetoothAddress ?: PLACEHOLDER))
    val initialized = state.activationProgress.isAtLeast(ActivationProgress.SET_UNIQUE_ID)
    val statusRow = PumpInfoRow(label = rh.gs(DashStrings.dash_overview_pod_status), value = dashPodStatusText(state, rh), level = dashPodStatusLevel(state))
    if (!initialized) {
        add(statusRow)
        return@buildList
    }
    add(PumpInfoRow(label = rh.gs(DashStrings.dash_overview_unique_id), value = state.uniqueId?.toString() ?: PLACEHOLDER))
    add(PumpInfoRow(label = rh.gs(DashStrings.dash_overview_lot_number), value = state.lotNumber?.toString() ?: PLACEHOLDER))
    add(PumpInfoRow(label = rh.gs(DashStrings.dash_overview_sequence_number), value = state.podSequenceNumber?.toString() ?: PLACEHOLDER))
    add(
        PumpInfoRow(
            label = rh.gs(DashStrings.dash_overview_firmware_version),
            value = rh.gs(DashStrings.dash_overview_firmware_version_value, state.firmwareVersion.toString(), state.bleVersion.toString())
        )
    )
    // Pod expiry and hard end, with the colours of Android `podExpiryRows` (#5365).
    val expiresAt = state.expiryMs
    val hardEndAt = expiresAt?.let { it + POD_GRACE_PERIOD_MS }
    val hardEndLevel = when {
        hardEndAt != null && nowMs >= hardEndAt - EXPIRY_WARNING_MS -> StatusLevel.CRITICAL
        expiresAt != null && nowMs >= expiresAt                     -> StatusLevel.WARNING
        else                                                        -> StatusLevel.NORMAL
    }
    val hardEndValue = when {
        hardEndAt == null                                       -> PLACEHOLDER
        hardEndLevel != StatusLevel.NORMAL && nowMs < hardEndAt ->
            rh.gs(DashStrings.dash_overview_pod_hard_end_value, format.dateAndTime(hardEndAt), format.timeRemaining(hardEndAt - nowMs))

        else                                                    -> format.dateAndTime(hardEndAt)
    }
    add(
        PumpInfoRow(
            label = rh.gs(DashStrings.dash_overview_pod_expires),
            value = expiresAt?.let { format.dateAndTime(it) } ?: PLACEHOLDER,
            level = if (expiresAt != null && nowMs >= expiresAt - EXPIRY_WARNING_MS) StatusLevel.WARNING else StatusLevel.NORMAL
        )
    )
    add(PumpInfoRow(label = rh.gs(DashStrings.dash_overview_pod_hard_end), value = hardEndValue, level = hardEndLevel))
    add(statusRow)
    add(PumpInfoRow(label = rh.gs(DashStrings.dash_overview_last_connection), value = if (state.lastUpdatedSystemMs > 0L) format.ago(state.lastUpdatedSystemMs) else PLACEHOLDER))
    lastBolusRow(state, rh, format)?.let { add(it) }
    val basalProgram = state.basalProgram
    add(
        PumpInfoRow(
            label = rh.gs(DashStrings.dash_overview_base_basal_rate),
            value = if (basalProgram != null && !state.isSuspended) format.basalRate(PumpType.OMNIPOD_DASH.determineCorrectBasalSize(basalProgram.rateAt(nowMs))) else PLACEHOLDER
        )
    )
    val tempBasal = state.tempBasal
    val tempBasalText = if (state.isActivationCompleted && state.tempBasalActive(nowMs) && tempBasal != null)
        format.tempBasal(tempBasal.rate, tempBasal.startTimeMs, tempBasal.durationInMinutes)
    else PLACEHOLDER
    add(PumpInfoRow(label = rh.gs(DashStrings.dash_overview_temp_basal_rate), value = tempBasalText, visible = tempBasalText != PLACEHOLDER))
    add(reservoirRow(state, rh, format))
    val delivered = state.podStatus?.totalPulsesDelivered
    add(
        PumpInfoRow(
            label = rh.gs(DashStrings.dash_overview_total_delivered),
            value = if (state.isActivationCompleted && delivered != null) format.insulin(delivered * PodConstants.POD_PULSE_BOLUS_UNITS) else PLACEHOLDER
        )
    )
    add(
        PumpInfoRow(
            label = rh.gs(DashStrings.dash_overview_active_alerts),
            value = state.activeAlerts?.takeIf { it.isNotEmpty() }?.joinToString("\n") { rh.gs(alertText(it)) } ?: PLACEHOLDER
        )
    )
    val alarm = state.alarmType
    add(
        PumpInfoRow(
            label = rh.gs(CoreUiStrings.errors),
            value = alarm?.let { rh.gs(DashStrings.omnipod_dash_ios_pod_fault, (it.value.toInt() and 0xff).toString().padStart(3, '0'), it.toString()) } ?: PLACEHOLDER,
            level = if (alarm != null) StatusLevel.CRITICAL else StatusLevel.NORMAL
        )
    )
}

/** Android `buildPodStatusText`. */
fun dashPodStatusText(state: DashPodStateManager, rh: TextResolver): String {
    val progress = state.activationProgress
    return when {
        progress == ActivationProgress.NOT_STARTED            -> rh.gs(DashStrings.omnipod_dash_ios_no_active_pod)
        !state.isActivationCompleted                          ->
            if (progress.isBefore(ActivationProgress.PRIME_COMPLETED)) rh.gs(DashStrings.dash_pod_status_waiting_for_activation)
            else rh.gs(DashStrings.dash_pod_status_waiting_for_cannula_insertion)

        state.reportedPodStatus?.isRunning() == true          ->
            if (state.isSuspended) rh.gs(DashStrings.dash_pod_status_suspended) else rh.gs(DashStrings.dash_pod_status_running)

        else                                                  -> state.reportedPodStatus?.toString() ?: PLACEHOLDER
    }
}

/** Android `buildPodStatusLevel`. */
fun dashPodStatusLevel(state: DashPodStateManager): StatusLevel = when {
    !state.isActivationCompleted || state.isPodKaput || state.isSuspended -> StatusLevel.CRITICAL
    state.activeCommand != null                                           -> StatusLevel.WARNING
    else                                                                  -> StatusLevel.NORMAL
}

/** Android `buildLastBolus`: an open bolus first (uncertain, red), else the last bolus. */
private fun lastBolusRow(state: DashPodStateManager, rh: TextResolver, format: DashOverviewFormat): PumpInfoRow? {
    val label = rh.gs(CoreUiStrings.last_bolus_label)
    state.activeCommand?.let { command ->
        val requested = command.requestedBolusUnits
        val createdAt = command.createdAtMs
        if (requested != null && createdAt != null) {
            val text = format.insulinAgo(PumpType.OMNIPOD_DASH.determineCorrectBolusSize(requested), createdAt)
            return PumpInfoRow(label = label, value = rh.gs(InterfacesStrings.value_with_detail, text, rh.gs(DashStrings.dash_overview_uncertain)), level = StatusLevel.CRITICAL)
        }
    }
    val last = state.lastBolus ?: return null
    val units = last.deliveredUnits() ?: last.requestedUnits
    return PumpInfoRow(
        label = label,
        value = format.insulinAgo(PumpType.OMNIPOD_DASH.determineCorrectBolusSize(units), last.startTimeMs),
        level = if (!last.deliveryComplete) StatusLevel.WARNING else StatusLevel.NORMAL
    )
}

/** Android `buildReservoir`: below 20 U red, and "over 50 U" while the pod does not count it yet. */
private fun reservoirRow(state: DashPodStateManager, rh: TextResolver, format: DashOverviewFormat): PumpInfoRow {
    val label = rh.gs(CoreUiStrings.reservoir_label)
    val pulses = state.pulsesRemaining
        ?: return PumpInfoRow(label = label, value = rh.gs(CoreUiStrings.overview_reservoir_concentration_value_over, format.insulin(50.0)), level = StatusLevel.NORMAL)
    val units = pulses * PodConstants.POD_PULSE_BOLUS_UNITS
    val low = units < PodConstants.DEFAULT_MAX_RESERVOIR_ALERT_THRESHOLD.toDouble()
    return PumpInfoRow(label = label, value = format.insulin(units), level = if (low) StatusLevel.CRITICAL else StatusLevel.NORMAL)
}

/** Android `translatedActiveAlert`. */
private fun alertText(alert: AlertType) = when (alert) {
    AlertType.LOW_RESERVOIR                                -> DashStrings.dash_alert_low_reservoir
    AlertType.EXPIRATION, AlertType.EXPIRATION_IMMINENT,
    AlertType.USER_SET_EXPIRATION                          -> DashStrings.dash_alert_expiration
    AlertType.AUTO_OFF                                     -> DashStrings.dash_alert_shutdown_imminent
    AlertType.SUSPEND_IN_PROGRESS, AlertType.SUSPEND_ENDED -> DashStrings.dash_alert_delivery_suspended
    else                                                   -> DashStrings.dash_alert_unknown
}
