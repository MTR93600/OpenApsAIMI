package app.aaps.pump.medtrum.comm

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag

/**
 * Sends the Medtrum protocol log lines to the AAPS log, under [LTag.PUMPBTCOMM].
 *
 * Shared on purpose. The packets and the session take a [MedtrumLogger] whose default is a no-op,
 * and a driver that forgets to pass one simply writes nothing - which is how the pump comms log
 * went quiet in the first port. Both phones build this one, so a line written on an iPhone reads
 * the same as the line written on Android.
 *
 * The `tag` the protocol passes is kept in the message rather than mapped to an [LTag], because
 * the protocol names its own areas ("PUMPCOMM", "PUMPBTCOMM") and those are not all AAPS tags.
 */
class AapsMedtrumLogger(private val aapsLogger: AAPSLogger) : MedtrumLogger {

    override fun debug(tag: String, message: String) = aapsLogger.debug(LTag.PUMPBTCOMM, "$tag $message")
    override fun warn(tag: String, message: String) = aapsLogger.warn(LTag.PUMPBTCOMM, "$tag $message")
    override fun error(tag: String, message: String) = aapsLogger.error(LTag.PUMPBTCOMM, "$tag $message")
}
