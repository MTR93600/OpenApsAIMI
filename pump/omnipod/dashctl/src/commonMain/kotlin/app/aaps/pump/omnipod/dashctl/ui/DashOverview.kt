package app.aaps.pump.omnipod.dashctl.ui

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.pump.defs.determineCorrectBasalSize
import app.aaps.core.interfaces.pump.defs.determineCorrectBolusSize
import app.aaps.core.interfaces.queue.CustomCommand
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.StatusLevel
import app.aaps.core.ui.compose.pump.PumpInfoRow
import app.aaps.pump.omnipod.dashctl.DashStrings
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.PdmFaultCategory
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.queue.command.CommandAcknowledgeAlert
import app.aaps.pump.omnipod.dashctl.queue.command.CommandPlayTestBeep
import app.aaps.pump.omnipod.dashctl.queue.command.CommandResumeDelivery
import app.aaps.pump.omnipod.dashctl.queue.command.CommandSilenceAlerts
import app.aaps.pump.omnipod.dashctl.session.DashPodStateManager
import kotlin.math.abs
import kotlin.reflect.KClass

/**
 * The buttons of the pod screen, the ones Android `DashOverviewViewModel` shows for Dash. Not here:
 * "Suspend" (always hidden for Dash on Android too).
 */
enum class DashOverviewAction { REFRESH, SILENCE_ALERTS, RESUME_DELIVERY, SET_TIME, ACTIVATE_POD, DEACTIVATE_POD, PLAY_TEST_BEEP, POD_HISTORY, DISCARD_POD }

/** One button: shown or not, and whether it can be pressed. */
data class DashOverviewButton(val action: DashOverviewAction, val visible: Boolean, val enabled: Boolean = true)

/**
 * Android `buildPrimaryActions` and `buildManagementActions`, the same rules:
 * - Refresh: from SET_UNIQUE_ID on, with an empty queue;
 * - Silence alerts: a running pod with alerts (or a silence waiting in the queue), empty queue;
 * - Resume delivery: a running pod that is suspended (or a resume waiting in the queue), empty queue;
 * - Set time: the activation is completed and the pod time zone is not the phone time zone
 *   ([DashPodStateManager.sameTimeZone]), not suspended, empty queue;
 * - Activate pod: the activation is not completed;
 * - Deactivate pod: as soon as a pod is paired (an address or a key is stored), not only after the
 *   activation, so a pod that faults during the activation can be removed (#1217);
 * - Play test beep: from PHASE_1_COMPLETED on, not while one waits in the queue;
 * - Pod history: always;
 * - Discard pod: a pod id is stored but SET_UNIQUE_ID was not reached.
 *
 * @param queueEmpty Android `commandQueue.size() == 0 && commandQueue.performing() == null`
 * @param inQueue Android `commandQueue.isCustomCommandInQueue`
 * @param nowMs the wall clock now, for the time zone of the phone
 */
fun dashOverviewButtons(
    state: DashPodStateManager,
    queueEmpty: Boolean,
    nowMs: Long,
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
        DashOverviewButton(
            DashOverviewAction.SET_TIME,
            visible = state.isActivationCompleted && !state.sameTimeZone(nowMs),
            enabled = !state.isSuspended && queueEmpty
        ),
        DashOverviewButton(DashOverviewAction.ACTIVATE_POD, visible = !state.isActivationCompleted),
        DashOverviewButton(DashOverviewAction.DEACTIVATE_POD, visible = state.bluetoothAddress != null || state.ltk != null),
        DashOverviewButton(DashOverviewAction.PLAY_TEST_BEEP, visible = phase1Done, enabled = phase1Done && !inQueue(CommandPlayTestBeep::class)),
        DashOverviewButton(DashOverviewAction.POD_HISTORY, visible = true),
        DashOverviewButton(DashOverviewAction.DISCARD_POD, visible = state.uniqueId != null && progress.isBefore(ActivationProgress.SET_UNIQUE_ID))
    )
}

/** One "Silence <alert>" button of the pod screen ([dashAlertSilenceButtons]). */
data class DashAlertButton(val alert: AlertType, val enabled: Boolean) {

    /** "Silence Low Reservoir": the name of the alert in a template, never two texts joined. */
    fun label(rh: TextResolver): String = rh.gs(DashStrings.omnipod_dash_ios_silence_alert.withArgs(rh.gs(dashAlertText(alert))))
}

/**
 * The buttons that silence one pod alert at a time, next to "Silence Alerts" (which stays as Android).
 * Not in Android; from OmnipodKit (Trio) `acknowledgeAlert`, see `IosDashPump.acknowledgeAlert`. One
 * for each alert of the last status that the user has not silenced yet
 * ([DashPodStateManager.acknowledgedAlerts]), on a running pod; none for "suspend ended" while
 * delivery is suspended (it stays on until the resume). Enabled with an empty queue and no single
 * silence waiting in it.
 */
fun dashAlertSilenceButtons(
    state: DashPodStateManager,
    queueEmpty: Boolean,
    inQueue: (KClass<out CustomCommand>) -> Boolean
): List<DashAlertButton> {
    if (!state.isPodRunning) return emptyList()
    val enabled = queueEmpty && !inQueue(CommandAcknowledgeAlert::class)
    return (state.activeAlerts.orEmpty() - state.acknowledgedAlerts)
        .filter { it != AlertType.UNKNOWN && !(it == AlertType.SUSPEND_ENDED && state.isSuspended) }
        .sortedBy { it.ordinal }
        .map { DashAlertButton(it, enabled) }
}

/** The buttons of the first row (pod commands), the rest are pod management, as Android. */
val DashOverviewAction.isPrimary: Boolean
    get() = this == DashOverviewAction.REFRESH || this == DashOverviewAction.SILENCE_ALERTS ||
        this == DashOverviewAction.RESUME_DELIVERY || this == DashOverviewAction.SET_TIME

/** The label of each button. */
val DashOverviewAction.label: TextRef
    get() = when (this) {
        DashOverviewAction.REFRESH         -> CoreUiStrings.refresh
        DashOverviewAction.SILENCE_ALERTS  -> DashStrings.dash_button_silence_alerts
        DashOverviewAction.RESUME_DELIVERY -> DashStrings.dash_button_resume_delivery
        DashOverviewAction.SET_TIME        -> DashStrings.dash_button_set_time
        DashOverviewAction.POD_HISTORY     -> DashStrings.dash_button_pod_history
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

    /**
     * Pod units as insulin units (Android `ConcentrationHelper.fromPump`): with U200 insulin one pod
     * unit is two insulin units.
     */
    fun fromPump(units: Double): Double
}

private const val PLACEHOLDER = "-"

/** Android `POD_GRACE_PERIOD_HOURS`: the pod delivers for 8 h after the expiry it reports. */
private const val POD_GRACE_PERIOD_MS = 8 * 60 * 60_000L

/** Android `PodExpiryRows.WARNING_TIME`. */
private const val EXPIRY_WARNING_MS = 4 * 60 * 60_000L

/** Android `MAX_TIME_DEVIATION_MINUTES`: a pod clock more than 10 minutes off is shown as a warning. */
private const val MAX_TIME_DEVIATION_MS = 10 * 60_000L

/**
 * The rows of the pod screen, Android `DashOverviewViewModel.buildInfoRows`. The engineering mode
 * "delivery status" row of Android is not here (no engineering mode on the iPhone). The time zone of
 * the pod is shown by its id (Android shows the short display name, which common code cannot make).
 */
fun dashOverviewRows(state: DashPodStateManager, rh: TextResolver, format: DashOverviewFormat, nowMs: Long): List<PumpInfoRow> = buildList {
    add(PumpInfoRow(label = rh.gs(DashStrings.dash_overview_bluetooth_address), value = state.bluetoothAddress ?: PLACEHOLDER))
    add(connectionQualityRow(state, rh))
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
    add(timeOnPodRow(state, rh, format, nowMs))
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
            value = state.activeAlerts?.takeIf { it.isNotEmpty() }?.joinToString("\n") { rh.gs(dashAlertText(it)) } ?: PLACEHOLDER
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
    pdmNotificationRow(state, rh)?.let { add(it) }
}

/**
 * Android connection quality row: worked/attempts :: per cent; red below 70 % and orange below 90 %,
 * both only after more than 50 connections that worked.
 */
private fun connectionQualityRow(state: DashPodStateManager, rh: TextResolver): PumpInfoRow {
    val worked = state.successfulConnectionAttemptsAfterRetries
    val attempts = state.failedConnectionsAfterRetries + worked
    val percent = state.connectionSuccessRatio() * 100
    val level = when {
        percent < 70 && worked > 50 -> StatusLevel.CRITICAL
        percent < 90 && worked > 50 -> StatusLevel.WARNING
        else                        -> StatusLevel.NORMAL
    }
    return PumpInfoRow(
        label = rh.gs(DashStrings.dash_overview_connection_quality),
        value = rh.gs(DashStrings.dash_overview_connection_quality_value, worked, attempts, percent.toDouble()),
        level = level
    )
}

/**
 * Android time on pod row: the pod time ([DashPodStateManager.podTimeMs]) with the pod time zone;
 * red (Android magenta) when the pod time zone is not the phone time zone, orange when the pod clock
 * is more than 10 minutes off.
 */
private fun timeOnPodRow(state: DashPodStateManager, rh: TextResolver, format: DashOverviewFormat, nowMs: Long): PumpInfoRow {
    val zone = state.timeZoneId?.takeIf { state.timeZoneUpdatedMs != null } ?: PLACEHOLDER
    val value = state.podTimeMs(nowMs)?.let { rh.gs(DashStrings.dash_overview_time_with_time_zone, format.dateAndTime(it), zone) } ?: PLACEHOLDER
    val drift = state.timeDriftMs(nowMs)
    val level = when {
        !state.sameTimeZone(nowMs)                               -> StatusLevel.CRITICAL
        drift != null && abs(drift) > MAX_TIME_DEVIATION_MS        -> StatusLevel.WARNING
        else                                                     -> StatusLevel.NORMAL
    }
    return PumpInfoRow(label = rh.gs(DashStrings.dash_overview_time_on_pod), value = value, level = level)
}

/**
 * Android PDM notification row: the fault as the PDM would have shown it (title, text, Ref), for a
 * fault with a PDM category and a stored reference ([DashPodStateManager.pdmRef]).
 */
private fun pdmNotificationRow(state: DashPodStateManager, rh: TextResolver): PumpInfoRow? {
    val category = state.alarmType?.pdmFaultCategory ?: return null
    val ref = state.pdmRef ?: return null
    val (header, text) = when (category) {
        PdmFaultCategory.RESERVOIR_EMPTY -> DashStrings.dash_pdm_header_reservoir_empty to DashStrings.dash_pdm_text_reservoir_empty
        PdmFaultCategory.AUTO_OFF        -> DashStrings.dash_pdm_header_auto_off to DashStrings.dash_pdm_text_auto_off
        PdmFaultCategory.POD_EXPIRED     -> DashStrings.dash_pdm_header_pod_expired to DashStrings.dash_pdm_text_pod_expired
        PdmFaultCategory.OCCLUDED        -> DashStrings.dash_pdm_header_occluded to DashStrings.dash_pdm_text_occluded
        PdmFaultCategory.POD_ERROR       -> DashStrings.dash_pdm_header_pod_error to DashStrings.dash_pdm_text_pod_error
    }
    return PumpInfoRow(
        label = rh.gs(DashStrings.dash_overview_pdm_notification),
        value = rh.gs(DashStrings.dash_overview_pdm_notification_value, rh.gs(header), rh.gs(text), rh.gs(DashStrings.dash_pdm_ref, ref))
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
    // Android `buildReservoir`: the threshold is in insulin units, so the concentration comes first.
    val low = format.fromPump(units) < PodConstants.DEFAULT_MAX_RESERVOIR_ALERT_THRESHOLD.toDouble()
    return PumpInfoRow(label = label, value = format.insulin(units), level = if (low) StatusLevel.CRITICAL else StatusLevel.NORMAL)
}

/** Android `translatedActiveAlert`. Also the name of the alert in its notice on the phone. */
internal fun dashAlertText(alert: AlertType): TextRef = when (alert) {
    AlertType.LOW_RESERVOIR                                -> DashStrings.dash_alert_low_reservoir
    AlertType.EXPIRATION, AlertType.EXPIRATION_IMMINENT,
    AlertType.USER_SET_EXPIRATION                          -> DashStrings.dash_alert_expiration
    AlertType.AUTO_OFF                                     -> DashStrings.dash_alert_shutdown_imminent
    AlertType.SUSPEND_IN_PROGRESS, AlertType.SUSPEND_ENDED -> DashStrings.dash_alert_delivery_suspended
    else                                                   -> DashStrings.dash_alert_unknown
}
