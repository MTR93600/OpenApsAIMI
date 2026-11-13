package app.aaps.pump.omnipod.dashctl.driver

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertConfiguration
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertTrigger
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepRepetitionType
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.session.DashAlertSettings

/**
 * The alert configurations the Android Dash plugin sends after the activation, with the same values.
 * Rules only: the pump sends them (`IosDashPump.programAlerts`).
 */
object DashAlerts {

    private const val MS_PER_HOUR = 3_600_000L
    private const val MINUTES_PER_HOUR = 60L

    /** Android adds 8 hours to the expiration alarms "for grace period". */
    private const val GRACE_HOURS = 8L

    /**
     * Android `OmnipodDashPumpPlugin.disableSuspendAlerts`: the "suspend ended" alert turned off. Sent
     * after delivery runs again (a confirmed resume or basal program), so the pod does not beep about
     * a suspend that is over (#2982).
     */
    fun disableSuspendAlerts(): List<AlertConfiguration> = listOf(
        AlertConfiguration(
            AlertType.SUSPEND_ENDED,
            enabled = false,
            durationInMinutes = 0,
            autoOff = false,
            AlertTrigger.TimerTrigger(0),
            BeepType.FOUR_TIMES_BIP_BEEP,
            BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
        )
    )

    /**
     * Android `OmnipodDashPumpPlugin.updateAlertConfiguration`: the low reservoir alert, the user
     * expiration reminder, the expiration alarm and the imminent expiration alarm, in that order and
     * with the same values.
     *
     * @param podLifeLeftMs from now to the expiry of the pod (Android `Duration.between(now, expiry)`).
     *
     * Ported as Android (reported): a delay that comes out negative is logged and the alert is still
     * sent with it. Android builds a failure result there but drops it (no `return`), so the negative
     * number of minutes goes to the pod. Minutes are whole minutes as Java `Duration.toMinutes`.
     */
    fun updateAlertConfigurations(settings: DashAlertSettings, podLifeLeftMs: Long, logger: AAPSLogger): List<AlertConfiguration> {
        val expiration = settings.expiration
        val lowReservoir = settings.lowReservoir
        val expiryReminderDelayMs = podLifeLeftMs - expiration.reminderHours * MS_PER_HOUR
        if (expiryReminderDelayMs < 0) {
            logger.warn(LTag.PUMPBTCOMM, "updateAlertConfiguration negative expiryAlertDuration=$expiryReminderDelayMs ms")
        }
        val expiryAlarmDelayMs = podLifeLeftMs - expiration.alarmHours * MS_PER_HOUR + GRACE_HOURS * MS_PER_HOUR
        if (expiryAlarmDelayMs < 0) {
            logger.warn(LTag.PUMPBTCOMM, "updateAlertConfiguration negative expiryAlarmDuration=$expiryAlarmDelayMs ms")
        }
        val expiryImminentDelayMs =
            podLifeLeftMs - PodConstants.POD_EXPIRATION_IMMINENT_ALERT_HOURS_REMAINING * MS_PER_HOUR + GRACE_HOURS * MS_PER_HOUR
        if (expiryImminentDelayMs < 0) {
            logger.warn(LTag.PUMPBTCOMM, "updateAlertConfiguration negative expiryImminentDuration=$expiryImminentDelayMs ms")
        }
        return listOf(
            AlertConfiguration(
                AlertType.LOW_RESERVOIR,
                enabled = lowReservoir.enabled,
                durationInMinutes = 0,
                autoOff = false,
                AlertTrigger.ReservoirVolumeTrigger((lowReservoir.units * 10).toShort()),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.XXX
            ),
            AlertConfiguration(
                AlertType.USER_SET_EXPIRATION,
                enabled = expiration.reminderEnabled,
                durationInMinutes = 0,
                autoOff = false,
                AlertTrigger.TimerTrigger(DashActivation.wholeMinutes(expiryReminderDelayMs).toShort()),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
            ),
            AlertConfiguration(
                AlertType.EXPIRATION,
                enabled = expiration.alarmEnabled,
                durationInMinutes = ((expiration.alarmHours - 1) * MINUTES_PER_HOUR).toShort(),
                autoOff = false,
                AlertTrigger.TimerTrigger(DashActivation.wholeMinutes(expiryAlarmDelayMs).toShort()),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.XXX3
            ),
            AlertConfiguration(
                AlertType.EXPIRATION_IMMINENT,
                enabled = expiration.alarmEnabled,
                durationInMinutes = 0,
                autoOff = false,
                AlertTrigger.TimerTrigger(DashActivation.wholeMinutes(expiryImminentDelayMs).toShort()),
                BeepType.FOUR_TIMES_BIP_BEEP,
                BeepRepetitionType.XXX3
            )
        )
    }
}

/**
 * The beep settings of the user (Android preferences `BolusBeepsEnabled`, `SmbBeepsEnabled`,
 * `BasalBeepsEnabled`, `TbrBeepsEnabled`). All off by default: beeps stay off until the plugin
 * (phase 4) brings the user settings.
 *
 * @property bolusBeeps a bolus beeps when it starts and when it ends, and a stop of the bolus beeps.
 * @property smbBeeps the same for an SMB, in place of [bolusBeeps].
 * @property basalBeeps a basal program beeps when it starts, a suspend beeps.
 * @property tempBasalBeeps a temp basal beeps when it starts and when it ends, a stop of a temp basal beeps.
 */
data class DashBeepSettings(
    val bolusBeeps: Boolean = false,
    val smbBeeps: Boolean = false,
    val basalBeeps: Boolean = false,
    val tempBasalBeeps: Boolean = false,
) {

    companion object {

        /**
         * Android `observeSendStopDeliveryCommand` and `observeSuspendDeliveryCommand`: a long single
         * beep when beeps are on, silent otherwise.
         */
        fun stopBeep(beep: Boolean): BeepType = if (beep) BeepType.LONG_SINGLE_BEEP else BeepType.SILENT
    }
}
