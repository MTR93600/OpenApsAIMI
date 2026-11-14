package app.aaps.pump.omnipod.dashctl.driver

import app.aaps.pump.omnipod.dashctl.driver.ActivatingPodModel.Companion.podDefaultStatus
import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType

/**
 * The pod side of a pod that runs, at the level of pod commands: it answers each command with a
 * default status (Android `DefaultStatusResponse`) that reports the last programming command it
 * took, as a real pod does, and it follows what it was told to deliver:
 * - a temp basal command starts a temp basal, a stop with the temp basal bit ends it;
 * - a basal program starts the basal, a stop with the basal bit ends it;
 * - a bolus runs when [bolusPulsesOnNextBolus] is set (else the pod reports it as given at once), a
 *   stop with the bolus bit or a deactivation ends it;
 * - a deactivation stops everything and reports DEACTIVATED;
 * - a silence of alerts clears the alerts it names;
 * - with [faultAlarmType] set, the pod takes nothing and answers every command with an alarm status.
 *
 * Moved here from `DashPumpOnFakePod` (dashctl iosTest), which now uses it, so that tests outside
 * dashctl can run a pod too.
 */
class RunningPodModel {

    /**
     * The sequence of the last programming command the pod took, as it reports it. 0x0f before
     * any, so it never matches a first command built with sequence 0.
     */
    var lastProgrammingSequence = 0x0f

    /** Bolus pulses the pod reports as not delivered yet. */
    var bolusPulsesRemaining = 0

    /**
     * True while the pod delivers a bolus: the status then reports `BOLUS_AND_BASAL_ACTIVE` (or
     * `BOLUS_AND_TEMP_BASAL_ACTIVE`). Set by a bolus the pod takes when [bolusPulsesOnNextBolus] is
     * set; a stop of the bolus clears it. Tests move [bolusPulsesRemaining] to make the bolus go on.
     */
    var bolusRunning = false

    /**
     * When set, the next bolus the pod takes starts running with this many pulses left. Null: the pod
     * reports no bolus running, as if it had delivered it at once.
     */
    var bolusPulsesOnNextBolus: Int? = null

    /** The stops of the bolus the pod got, with the pulses that were left at each one. */
    val bolusStops = mutableListOf<Int>()

    /**
     * True while the pod runs a temp basal: set by a temp basal command it takes, cleared by a stop
     * of the temp basal. The status then reports `TEMP_BASAL_ACTIVE`.
     */
    var tempBasalRunning = false

    /**
     * True while the pod runs a basal program: set by a basal program it takes, cleared by a stop with
     * the basal bit (a suspend, a stop of all delivery). With no temp basal and no bolus, the status
     * then reports `BASAL_ACTIVE`. False by default, so a status reports the delivery status of the
     * Android capture (`SUSPENDED`).
     */
    var basalRunning = false

    /**
     * The pod status the default status reports (`PodStatus` value): RUNNING_ABOVE_MIN_VOLUME by
     * default. A deactivation the pod takes sets DEACTIVATED (0x0f).
     */
    var podStatusCode = ActivatingPodModel.RUNNING

    /**
     * The alerts the status reports (the `AlertUtil.encodeAlertSet` byte). A silence of alerts the
     * pod takes clears the alerts it names.
     */
    var activeAlerts = 0

    /**
     * When set (an `AlarmType` value), the pod has a fault: it takes no command and answers every
     * command with an alarm status ([podAlarmStatus]) that reports this alarm, pod status ALARM,
     * delivery suspended, the last programming command it took and the bolus pulses it had left.
     */
    var faultAlarmType: Int? = null

    /**
     * The alarm time the alarm status reports: the minutes since activation when the fault came (the
     * status reports [ALARM_STATUS_MINUTES] as its own minutes since activation). 0 by default: no
     * alarm time, as the Android vector.
     */
    var faultAlarmMinutes = 0

    /** The reservoir pulses the default status reports: 1023 (more than 50 U) by default, as the capture. */
    var reservoirPulses = 0x3ff

    /** The answer (response bytes without header and CRC) to [command], and the change of pod state. */
    fun answer(command: ByteArray): ByteArray {
        faultAlarmType?.let { return podAlarmStatus(it, lastProgrammingSequence, bolusPulsesRemaining, faultAlarmMinutes) }
        val sequence = (command[4].toInt() and 0xff shr 2) and 0x0f
        if (command[6] != CommandType.GET_STATUS.value) lastProgrammingSequence = sequence
        if (isTempBasal(command)) tempBasalRunning = true
        if (isStopOf(command, TEMP_BASAL_BIT)) tempBasalRunning = false
        if (isBasalProgram(command)) basalRunning = true
        if (isStopOf(command, BASAL_BIT)) basalRunning = false
        if (isBolus(command)) bolusPulsesOnNextBolus?.let {
            bolusRunning = true
            bolusPulsesRemaining = it
        }
        val stopsBolus = isStopOf(command, BOLUS_BIT) || command[6] == CommandType.DEACTIVATE.value
        if (stopsBolus) {
            bolusRunning = false
            bolusStops += bolusPulsesRemaining
        }
        if (command[6] == CommandType.DEACTIVATE.value) {
            tempBasalRunning = false
            basalRunning = false
            podStatusCode = DEACTIVATED
        }
        if (command[6] == CommandType.SILENCE_ALERTS.value) activeAlerts = activeAlerts and (command[12].toInt() and 0xff).inv()
        // The answer to the stop reports the pulses that were not delivered; after it, none are left.
        val answer = podDefaultStatus(podStatusCode, lastProgrammingSequence, bolusPulsesRemaining, deliveryStatus(), activeAlerts, reservoirPulses)
        if (stopsBolus) bolusPulsesRemaining = 0
        return answer
    }

    /** The default status the pod would report now, with no change of pod state (no command taken). */
    fun currentStatus(): ByteArray {
        faultAlarmType?.let { return podAlarmStatus(it, lastProgrammingSequence, bolusPulsesRemaining, faultAlarmMinutes) }
        return podDefaultStatus(podStatusCode, lastProgrammingSequence, bolusPulsesRemaining, deliveryStatus(), activeAlerts, reservoirPulses)
    }

    private fun deliveryStatus(): Int = when {
        bolusRunning && tempBasalRunning -> BOLUS_AND_TEMP_BASAL_ACTIVE
        bolusRunning                     -> BOLUS_AND_BASAL_ACTIVE
        tempBasalRunning                 -> TEMP_BASAL_ACTIVE
        basalRunning                     -> BASAL_ACTIVE
        else                             -> CAPTURE_DELIVERY
    }

    companion object {

        /** The delivery status of the Android capture (0, `SUSPENDED`): no temp basal. */
        const val CAPTURE_DELIVERY = ActivatingPodModel.CAPTURE_DELIVERY

        /** `PodStatus.DEACTIVATED`. */
        const val DEACTIVATED = 0x0f

        /** `PodStatus.ALARM`. */
        const val ALARM = 0x0d

        /** `DeliveryStatus.BASAL_ACTIVE`. */
        const val BASAL_ACTIVE = 0x1

        /** `DeliveryStatus.TEMP_BASAL_ACTIVE`. */
        const val TEMP_BASAL_ACTIVE = 0x2

        /** `DeliveryStatus.BOLUS_AND_BASAL_ACTIVE`. */
        const val BOLUS_AND_BASAL_ACTIVE = 0x5

        /** `DeliveryStatus.BOLUS_AND_TEMP_BASAL_ACTIVE`. */
        const val BOLUS_AND_TEMP_BASAL_ACTIVE = 0x6

        /** The basal bit of `StopDeliveryCommand.DeliveryType`. */
        const val BASAL_BIT = 0x01

        /** The temp basal bit of `StopDeliveryCommand.DeliveryType`. */
        const val TEMP_BASAL_BIT = 0x02

        /** The bolus bit of `StopDeliveryCommand.DeliveryType`. */
        const val BOLUS_BIT = 0x04

        /** The minutes since activation an alarm status of [podAlarmStatus] reports (0x0195). */
        const val ALARM_STATUS_MINUTES = 0x0195

        /** `AlarmType.ALARM_OCCLUDED`. */
        const val OCCLUDED = 0x14

        /**
         * An alarm status (the layout of `AlarmStatusResponse`): the Android vector of
         * `AlarmStatusResponseTest` (`021602080100000501BD00000003FF01950000000000670A`) with pod status
         * ALARM (0x0d), delivery SUSPENDED (0), the bolus pulses left, the last programming sequence and
         * the alarm type set, and the alarm time [alarmMinutes] (0, none, in the vector).
         */
        fun podAlarmStatus(alarmType: Int, lastProgrammingSequence: Int, bolusPulsesRemaining: Int, alarmMinutes: Int = 0): ByteArray =
            parityHex(
                "021602" + "0d" + "00" +
                    (bolusPulsesRemaining and 0x7ff).toString(16).padStart(4, '0') +
                    (lastProgrammingSequence and 0x0f).toString(16).padStart(2, '0') +
                    "01bd" + (alarmType and 0xff).toString(16).padStart(2, '0') +
                    (alarmMinutes and 0xffff).toString(16).padStart(4, '0') + "03ff" + "0195" + "00" + "00000000" + "670a"
            )

        /** A bolus command: 0x1a (program insulin), then 0x17 after the 0x1a part. */
        fun isBolus(command: ByteArray): Boolean = isProgramInsulinWith(command, CommandType.PROGRAM_BOLUS.value)

        /** A temp basal command: 0x1a (program insulin), then 0x16 after the 0x1a part. */
        fun isTempBasal(command: ByteArray): Boolean = isProgramInsulinWith(command, CommandType.PROGRAM_TEMP_BASAL.value)

        /** A basal program: 0x1a (program insulin), then 0x13 after the 0x1a part. */
        fun isBasalProgram(command: ByteArray): Boolean = isProgramInsulinWith(command, CommandType.PROGRAM_BASAL.value)

        /** A stop (0x1f) whose delivery type has [bit]; the type is the low nibble of byte 12. */
        fun isStopOf(command: ByteArray, bit: Int): Boolean =
            command.size > 12 && command[6] == CommandType.STOP_DELIVERY.value && (command[12].toInt() and bit) != 0

        private fun isProgramInsulinWith(command: ByteArray, next: Byte): Boolean {
            if (command.size < 8 || command[6] != CommandType.PROGRAM_INSULIN.value) return false
            val at = 8 + (command[7].toInt() and 0xff)
            return at < command.size && command[at] == next
        }
    }
}
