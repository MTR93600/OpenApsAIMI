package app.aaps.pump.omnipod.dashctl.pod.command

import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.command.base.NonceEnabledCommand
import app.aaps.pump.omnipod.dashctl.pod.command.base.builder.NonceEnabledCommandBuilder
import app.aaps.pump.omnipod.dashctl.pod.definition.*
import app.aaps.pump.omnipod.dashctl.pod.util.PodByteBuffer

// StopDelivery.ALL followed by ProgramAlerts
class SuspendDeliveryCommand private constructor(
    uniqueId: Int,
    sequenceNumber: Short,
    multiCommandFlag: Boolean,
    private val beepType: BeepType,
    nonce: Int
) : NonceEnabledCommand(CommandType.STOP_DELIVERY, uniqueId, sequenceNumber, multiCommandFlag, nonce) {

    override val encoded: ByteArray
        get() {
            val alerts = listOf(
                AlertConfiguration(
                    AlertType.SUSPEND_ENDED,
                    enabled = true,
                    durationInMinutes = 0,
                    autoOff = false,
                    AlertTrigger.TimerTrigger(
                        20
                    ),
                    BeepType.FOUR_TIMES_BIP_BEEP,
                    BeepRepetitionType.EVERY_MINUTE_AND_EVERY_15_MIN
                ),
            )
            val programAlerts = ProgramAlertsCommand.Builder()
                .setNonce(nonce)
                .setUniqueId(uniqueId)
                .setAlertConfigurations(alerts)
                .setSequenceNumber(sequenceNumber)
                .setMultiCommandFlag(false)
                .build()
                .encodedWithoutHeaderAndCRC32

            val byteBuffer = PodByteBuffer.allocate(LENGTH + HEADER_LENGTH + programAlerts.size)
                .put(encodeHeader(uniqueId, sequenceNumber, (LENGTH + programAlerts.size).toShort(), multiCommandFlag))
                .put(commandType.value)
                .put(BODY_LENGTH)
                .putInt(nonce)
                .put((beepType.value.toInt() shl 4 or DeliveryType.ALL.encoded[0].toInt()).toByte())
                .put(programAlerts)
                .array()

            return appendCrc(
                byteBuffer
            )
        }

    override fun toString(): String {
        return "SuspendDeliveryCommand{" +
            "deliveryType=" + DeliveryType.ALL +
            ", beepType=" + beepType +
            ", nonce=" + nonce +
            ", commandType=" + commandType +
            ", uniqueId=" + uniqueId +
            ", sequenceNumber=" + sequenceNumber +
            ", multiCommandFlag=" + multiCommandFlag +
            '}'
    }

    enum class DeliveryType(
        private val basal: Boolean,
        private val tempBasal: Boolean,
        private val bolus: Boolean
    ) : Encodable {

        BASAL(true, false, false), TEMP_BASAL(false, true, false), BOLUS(false, false, true), ALL(true, true, true);

        override val encoded: ByteArray
            get() {
                var flags = 0
                if (basal) flags = flags or 0x01
                if (tempBasal) flags = flags or 0x02
                if (bolus) flags = flags or 0x04
                return byteArrayOf(flags.toByte())
            }
    }

    class Builder : NonceEnabledCommandBuilder<Builder, SuspendDeliveryCommand>() {

        private var beepType: BeepType? = BeepType.LONG_SINGLE_BEEP

        fun setBeepType(beepType: BeepType): Builder {
            this.beepType = beepType
            return this
        }

        override fun buildCommand(): SuspendDeliveryCommand {
            requireNotNull(beepType) { "beepType can not be null" }

            return SuspendDeliveryCommand(
                uniqueId!!,
                sequenceNumber!!,
                multiCommandFlag,
                beepType!!,
                nonce!!
            )
        }
    }

    companion object {

        private const val LENGTH: Short = 7
        private const val BODY_LENGTH: Byte = 5
    }
}
