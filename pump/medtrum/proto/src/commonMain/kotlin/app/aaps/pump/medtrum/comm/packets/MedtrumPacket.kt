package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.extension.toInt

/**
 * Base class for all Medtrum protocol packets.
 *
 * Ported from the Android driver (pump/medtrum). The Android version took a
 * Dagger `HasAndroidInjector` and injected the `AAPSLogger`; this version takes
 * a [MedtrumLogger] directly (default no-op). The packet framing, opcode checks,
 * and response-code handling are unchanged.
 */
open class MedtrumPacket(
    protected val logger: MedtrumLogger = MedtrumLogger.NO_OP
) {

    var opCode: Byte = 0
    var failed = false
    var expectedMinRespLength = RESP_RESULT_END

    companion object {

        const val RESP_OPCODE_START = 1
        const val RESP_OPCODE_END = RESP_OPCODE_START + 1
        const val RESP_RESULT_START = 4
        const val RESP_RESULT_END = RESP_RESULT_START + 2

        private const val RESP_WAITING = 16384
    }

    open fun getRequest(): ByteArray {
        return byteArrayOf(opCode)
    }

    /**  handles a response from the Medtrum pump, returns true if command was successful, returns false if command failed or waiting for response */
    open fun handleResponse(data: ByteArray): Boolean {
        // Check for broken packets
        if (RESP_RESULT_END > data.size) {
            failed = true
            logger.debug("PUMPCOMM", "handleResponse: Unexpected response length, expected: $expectedMinRespLength got: ${data.size}")
            return false
        }

        val incomingOpCode: Byte = data.copyOfRange(RESP_OPCODE_START, RESP_OPCODE_END).first()
        val responseCode = data.copyOfRange(RESP_RESULT_START, RESP_RESULT_END).toInt()

        return when {
            incomingOpCode != opCode     -> {
                failed = true
                logger.error("PUMPCOMM", "handleResponse: Unexpected command, expected: $opCode got: $incomingOpCode")
                false
            }

            responseCode == 0            -> {
                // Check if length is what is expected from this type of packet
                if (expectedMinRespLength > data.size) {
                    failed = true
                    logger.debug("PUMPCOMM", "handleResponse: Unexpected response length, expected: $expectedMinRespLength got: ${data.size}")
                    return false
                }
                logger.debug("PUMPCOMM", "handleResponse: Happy command: $opCode response: $responseCode")
                true
            }

            responseCode == RESP_WAITING -> {
                logger.debug("PUMPCOMM", "handleResponse: Waiting command: $opCode response: $responseCode")
                // Waiting do nothing
                false
            }

            else                         -> {
                failed = true
                logger.warn("PUMPCOMM", "handleResponse: Error in command: $opCode response: $responseCode")
                false
            }
        }
    }
}
