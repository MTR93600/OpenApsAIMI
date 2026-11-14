package app.aaps.pump.omnipod.dashctl.pod.response

import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodConstants
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.response.ResponseType.StatusResponseType
import app.aaps.pump.omnipod.dashctl.pod.util.AlertUtil
import app.aaps.pump.omnipod.dashctl.pod.util.PodByteBuffer
import app.aaps.pump.omnipod.dashctl.pod.util.byValue
import kotlin.experimental.and

class AlarmStatusResponse(
    encoded: ByteArray
) : AdditionalStatusResponseBase(StatusResponseType.ALARM_STATUS, encoded) {

    val messageType: Byte = encoded[0]
    val messageLength: Short = (encoded[1].toInt() and 0xff).toShort()
    val additionalStatusResponseType: Byte = encoded[2]
    val podStatus: PodStatus = byValue((encoded[3] and 0x0f), PodStatus.UNKNOWN)
    val deliveryStatus: DeliveryStatus = byValue((encoded[4] and 0x0f), DeliveryStatus.UNKNOWN)
    val bolusPulsesRemaining: Short = (PodByteBuffer.wrap(byteArrayOf(encoded[5], encoded[6])).short and 2047)
    val sequenceNumberOfLastProgrammingCommand: Short = (encoded[7] and 0x0f).toShort()
    val totalPulsesDelivered: Short = PodByteBuffer.wrap(byteArrayOf(encoded[8], encoded[9])).short
    val alarmType: AlarmType = byValue(encoded[10], AlarmType.UNKNOWN)
    val alarmTime: Short = PodByteBuffer.wrap(byteArrayOf(encoded[11], encoded[12])).short
    val reservoirPulsesRemaining: Short = PodByteBuffer.wrap(byteArrayOf(encoded[13], encoded[14])).short
    val minutesSinceActivation: Short = PodByteBuffer.wrap(byteArrayOf(encoded[15], encoded[16])).short
    val activeAlerts: Set<AlertType> = AlertUtil.decodeAlertSet(encoded[17])
    val occlusionAlarm: Boolean
    val pulseInfoInvalid: Boolean
    val podStatusWhenAlarmOccurred: PodStatus
    val immediateBolusWhenAlarmOccurred: Boolean
    val occlusionType: Byte
    val occurredWhenFetchingImmediateBolusActiveInformation: Boolean
    val rssi: Short
    val receiverLowerGain: Short
    val podStatusWhenAlarmOccurred2: PodStatus
    val returnAddressOfPodAlarmHandlerCaller: Short

    // raw byte 19, kept alongside its decoded sub-fields above so a PDM Ref code can be built from it later
    val rawErrorEventByte: Byte = encoded[19]

    init {
        val alarmFlags = encoded[18]
        occlusionAlarm = (alarmFlags.toInt() and 1) == 1
        pulseInfoInvalid = alarmFlags shr 1 and 1 == 1
        val byte19 = encoded[19]
        val byte20 = encoded[20]
        podStatusWhenAlarmOccurred = byValue((byte19 and 0x0f), PodStatus.UNKNOWN)
        immediateBolusWhenAlarmOccurred = byte19 shr 4 and 1 == 1
        occlusionType = ((byte19 shr 5 and 3).toByte())
        occurredWhenFetchingImmediateBolusActiveInformation = byte19 shr 7 and 1 == 1
        rssi = (byte20 and 0x3f).toShort()
        receiverLowerGain = ((byte20 shr 6 and 0x03).toShort())
        podStatusWhenAlarmOccurred2 = byValue((encoded[21] and 0x0f), PodStatus.UNKNOWN)
        returnAddressOfPodAlarmHandlerCaller = PodByteBuffer.wrap(byteArrayOf(encoded[22], encoded[23])).short
    }

    /**
     * Android `AlarmStatusResponse.pdmRef`: the PDM-style "Ref: TT-VVVHH-IIIRR-FFF" fault reference,
     * ported by Android from OmnipodKit `DetailedStatus.dashPdmRef`. Null when the alarm has no PDM
     * fault category (NONE, UNKNOWN). The reservoir and insulin values are cut to whole units, which
     * gives the "50+ units" value of the PDM as 51, as there.
     */
    val pdmRef: String?
        get() {
            val tt = alarmType.pdmFaultCategory?.tt ?: return null
            val vvv = rawErrorEventByte.toInt() and 0xff
            val alarmMinutes = alarmTime.toInt() and 0xffff
            val activeMinutes = minutesSinceActivation.toInt() and 0xffff
            val hh = (if (alarmMinutes == 0 || alarmMinutes == 0xffff) activeMinutes else alarmMinutes) / 60
            val iii = (totalPulsesDelivered.toInt() * PodConstants.POD_PULSE_BOLUS_UNITS).toInt()
            val rr = (reservoirPulsesRemaining.toInt() * PodConstants.POD_PULSE_BOLUS_UNITS).toInt()
            val fff = alarmType.value.toInt() and 0xff
            // Android: String.format(Locale.ROOT, "%02d-%03d%02d-%03d%02d-%03d", ...). No String.format in
            // common code; padStart gives the same text for the values these fields can have.
            return "${pad(tt, 2)}-${pad(vvv, 3)}${pad(hh, 2)}-${pad(iii, 3)}${pad(rr, 2)}-${pad(fff, 3)}"
        }

    /** Java `%0Nd`: at least [width] digits, zeros on the left, a minus sign first. */
    private fun pad(value: Int, width: Int): String =
        if (value < 0) "-" + (-value).toString().padStart(width - 1, '0') else value.toString().padStart(width, '0')

    override fun toString(): String {
        return "AlarmStatusResponse(" +
            "messageType=$messageType, " +
            "messageLength=$messageLength, " +
            "additionalStatusResponseType=$additionalStatusResponseType, " +
            "podStatus=$podStatus, " +
            "deliveryStatus=$deliveryStatus, " +
            "bolusPulsesRemaining=$bolusPulsesRemaining, " +
            "sequenceNumberOfLastProgrammingCommand=$sequenceNumberOfLastProgrammingCommand, " +
            "totalPulsesDelivered=$totalPulsesDelivered, " +
            "alarmType=$alarmType, " +
            "alarmTime=$alarmTime, " +
            "reservoirPulsesRemaining=$reservoirPulsesRemaining, " +
            "minutesSinceActivation=$minutesSinceActivation, " +
            "activeAlerts=$activeAlerts, " +
            "occlusionAlarm=$occlusionAlarm, " +
            "pulseInfoInvalid=$pulseInfoInvalid, " +
            "podStatusWhenAlarmOccurred=$podStatusWhenAlarmOccurred, " +
            "immediateBolusWhenAlarmOccurred=$immediateBolusWhenAlarmOccurred, " +
            "occlusionType=$occlusionType, " +
            "occurredWhenFetchingImmediateBolusActiveInformation=$occurredWhenFetchingImmediateBolusActiveInformation, " +
            "rssi=$rssi, " +
            "receiverLowerGain=$receiverLowerGain, " +
            "podStatusWhenAlarmOccurred2=$podStatusWhenAlarmOccurred2, " +
            "returnAddressOfPodAlarmHandlerCaller=$returnAddressOfPodAlarmHandlerCaller" +
            ")"
    }

    infix fun Byte.shr(i: Int): Int = toInt() shr i
}
