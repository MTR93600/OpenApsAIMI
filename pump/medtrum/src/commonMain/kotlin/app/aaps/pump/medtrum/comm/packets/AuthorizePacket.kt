package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumProtocolState
import app.aaps.pump.medtrum.comm.enums.CommandType.AUTH_REQ
import app.aaps.pump.medtrum.encryption.Crypt
import app.aaps.pump.medtrum.extension.toByteArray
import app.aaps.pump.medtrum.extension.toInt

/**
 * Authorize packet — ported from the Android driver (pump/medtrum).
 * The Dagger `HasAndroidInjector` is replaced by explicit [state] and [logger] parameters.
 */
class AuthorizePacket(
    private val state: MedtrumProtocolState,
    logger: MedtrumLogger = MedtrumLogger.NO_OP
) : MedtrumPacket(logger) {

    companion object {

        private const val RESP_DEVICE_TYPE_START = 7
        private const val RESP_DEVICE_TYPE_END = RESP_DEVICE_TYPE_START + 1
        private const val RESP_VERSION_X_START = 8
        private const val RESP_VERSION_X_END = RESP_VERSION_X_START + 1
        private const val RESP_VERSION_Y_START = 9
        private const val RESP_VERSION_Y_END = RESP_VERSION_Y_START + 1
        private const val RESP_VERSION_Z_START = 10
        private const val RESP_VERSION_Z_END = RESP_VERSION_Z_START + 1
    }

    init {
        opCode = AUTH_REQ.code
        expectedMinRespLength = RESP_VERSION_Z_END
    }

    override fun getRequest(): ByteArray {
        val role = 2 // Fixed to 2 for pump
        val key = Crypt().keyGen(state.pumpSN)
        return byteArrayOf(opCode) + byteArrayOf(role.toByte()) + state.patchSessionToken.toByteArray(4) + key.toByteArray(4)
    }

    override fun handleResponse(data: ByteArray): Boolean {
        val success = super.handleResponse(data)
        if (success) {
            val deviceType = data.copyOfRange(RESP_DEVICE_TYPE_START, RESP_DEVICE_TYPE_END).toInt()
            val swVersion = "" + data.copyOfRange(RESP_VERSION_X_START, RESP_VERSION_X_END).toInt() + "." + data.copyOfRange(RESP_VERSION_Y_START, RESP_VERSION_Y_END).toInt() + "." + data.copyOfRange(
                RESP_VERSION_Z_START, RESP_VERSION_Z_END
            ).toInt()

            if (state.deviceType != deviceType) {
                logger.warn("PUMPCOMM", "GetDeviceTypeState: deviceType changed from ${state.deviceType} to $deviceType")
                state.deviceType = deviceType
            }
            if (state.swVersion != swVersion) {
                state.swVersion = swVersion
            }
            logger.debug("PUMPCOMM", "GetDeviceTypeState: deviceType: ${deviceType}, swVersion: $swVersion")
        }
        return success
    }
}
