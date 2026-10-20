package app.aaps.pump.medtrum.comm.packets

import app.aaps.pump.medtrum.comm.MedtrumLogger
import app.aaps.pump.medtrum.comm.MedtrumProtocolState
import app.aaps.pump.medtrum.comm.enums.CommandType.SET_TIME_ZONE
import app.aaps.pump.medtrum.extension.toByteArray
import app.aaps.pump.medtrum.util.MedtrumTimeUtil

/**
 * Set timezone packet — ported from the Android driver (pump/medtrum).
 *
 * The Android version read the timezone offset via `dateUtil.getTimeZoneOffsetMinutes(dateUtil.now())`;
 * here it is an explicit constructor parameter [timeZoneOffsetMinutes] (a platform seam — the M3 layer
 * supplies the device value, tests pin it). `T.hours(12).mins()` / `T.hours(24).mins()` are inlined
 * as 720 / 1440.
 */
class SetTimeZonePacket(
    private val state: MedtrumProtocolState,
    private val timeZoneOffsetMinutes: Int,
    private val timeUtil: MedtrumTimeUtil = MedtrumTimeUtil(),
    logger: MedtrumLogger = MedtrumLogger.NO_OP
) : MedtrumPacket(logger) {

    private val offsetMinutes = timeZoneOffsetMinutes

    init {
        opCode = SET_TIME_ZONE.code
    }

    override fun getRequest(): ByteArray {
        val time = timeUtil.getCurrentTimePumpSeconds()
        var calcOffset = offsetMinutes
        logger.debug("PUMPCOMM", "Requested offset: $calcOffset minutes")
        // Workaround for bug where it fails to set timezone > GMT + 12
        // if offset is > 12 hours, subtract 24 hours
        if (calcOffset > 720) {
            calcOffset -= 1440
            logger.debug("PUMPCOMM", "Modifying requested offset to: $calcOffset minutes")
        }
        // Pump expects this for negative offsets
        if (calcOffset < 0) calcOffset += 65536
        return byteArrayOf(opCode) + calcOffset.toByteArray(2) + time.toByteArray(4)
    }

    override fun handleResponse(data: ByteArray): Boolean {
        val success = super.handleResponse(data)
        if (success) {
            state.pumpTimeZoneOffset = offsetMinutes
        }
        return success
    }
}
