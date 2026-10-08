package app.aaps.pump.omnipod.dashctl.pod.command

import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.command.base.NonceEnabledCommand
import app.aaps.pump.omnipod.dashctl.pod.command.base.builder.NonceEnabledCommandBuilder
import app.aaps.pump.omnipod.dashctl.pod.definition.AlertType
import app.aaps.pump.omnipod.dashctl.pod.util.AlertUtil
import app.aaps.pump.omnipod.dashctl.pod.util.PodByteBuffer

class SilenceAlertsCommand private constructor(
    uniqueId: Int,
    sequenceNumber: Short,
    multiCommandFlag: Boolean,
    private val alertTypes: Set<AlertType>,
    nonce: Int
) : NonceEnabledCommand(CommandType.SILENCE_ALERTS, uniqueId, sequenceNumber, multiCommandFlag, nonce) {

    override val encoded: ByteArray
        get() =
            appendCrc(
                PodByteBuffer.allocate(LENGTH + HEADER_LENGTH)
                    .put(encodeHeader(uniqueId, sequenceNumber, LENGTH, multiCommandFlag))
                    .put(commandType.value)
                    .put(BODY_LENGTH)
                    .putInt(nonce)
                    .put(AlertUtil.encodeAlertSet(alertTypes))
                    .array()
            )

    override fun toString(): String {
        return "SilenceAlertsCommand{" +
            "alertTypes=" + alertTypes +
            ", nonce=" + nonce +
            ", commandType=" + commandType +
            ", uniqueId=" + uniqueId +
            ", sequenceNumber=" + sequenceNumber +
            ", multiCommandFlag=" + multiCommandFlag +
            '}'
    }

    class Builder : NonceEnabledCommandBuilder<Builder, SilenceAlertsCommand>() {

        private var alertTypes: Set<AlertType>? = null

        fun setAlertTypes(alertTypes: Set<AlertType>): Builder {
            this.alertTypes = alertTypes
            return this
        }

        override fun buildCommand(): SilenceAlertsCommand {
            requireNotNull(alertTypes) { "alertTypes can not be null" }
            return SilenceAlertsCommand(uniqueId!!, sequenceNumber!!, multiCommandFlag, alertTypes!!, nonce!!)
        }
    }

    companion object {

        private const val LENGTH = 7.toShort()
        private const val BODY_LENGTH = 5.toByte()
    }
}
