package app.aaps.pump.omnipod.dashctl.driver

import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.command.base.CommandType
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus

/**
 * The pod side of an activation, at the level of pod commands: what a new pod answers and how its
 * pod status moves (FILLED, UID_SET, CLUTCH_DRIVE_ENGAGED, BASAL_PROGRAM_SET, RUNNING_ABOVE_MIN_VOLUME).
 * It counts the prime and the cannula boluses it took, so a test can check that none went twice.
 *
 * The Version and SetUniqueId answers are the Android test vectors (`VersionResponseTest`,
 * `SetUniqueIdResponseTest`); the Version answer carries the pod status of the model and the
 * SetUniqueId answer the unique id of the command, as a pod answers. A status is [podDefaultStatus].
 */
class ActivatingPodModel {

    var podStatus: PodStatus = PodStatus.FILLED

    /** The pod answers the prime with ENGAGING_CLUTCH_DRIVE, and goes on to CLUTCH_DRIVE_ENGAGED at the next status. */
    var engagingAfterPrime = false

    /** The sequence of the last programming command the pod took. */
    var lastProgrammingSequence = 0x0f

    /** Prime boluses the pod took (a bolus on a pod in UID_SET). */
    var primes = 0

    /** Cannula boluses the pod took (a bolus on a pod in BASAL_PROGRAM_SET). */
    var cannulaInsertions = 0

    /** Any other bolus. */
    var otherBoluses = 0

    /** Basal programs the pod took. */
    var basalPrograms = 0

    /** The command type (byte 6) of every command, in order. */
    val commandTypes = mutableListOf<Byte>()

    /** The answer (response bytes without header and CRC) to [command], and the change of pod state. */
    fun answer(command: ByteArray): ByteArray {
        val type = command[6]
        val sequence = (command[4].toInt() and 0xff shr 2) and 0x0f
        commandTypes += type
        when (type) {
            CommandType.GET_VERSION.value     -> return versionAnswer()

            CommandType.SET_UNIQUE_ID.value   -> {
                podStatus = PodStatus.UID_SET
                // The pod answers with the unique id it got (bytes 8 to 11 of the command).
                val answer = parityHex(SET_UNIQUE_ID_RESPONSE)
                command.copyInto(answer, destinationOffset = answer.size - 4, startIndex = 8, endIndex = 12)
                return answer
            }

            CommandType.GET_STATUS.value      -> {
                if (podStatus == PodStatus.ENGAGING_CLUTCH_DRIVE) {
                    val answer = podDefaultStatus(podStatus.value.toInt(), lastProgrammingSequence)
                    podStatus = PodStatus.CLUTCH_DRIVE_ENGAGED
                    return answer
                }
            }

            CommandType.PROGRAM_INSULIN.value -> {
                lastProgrammingSequence = sequence
                val next = command[8 + (command[7].toInt() and 0xff)]
                if (next == CommandType.PROGRAM_BOLUS.value) {
                    when (podStatus) {
                        PodStatus.UID_SET           -> {
                            primes++
                            podStatus = if (engagingAfterPrime) PodStatus.ENGAGING_CLUTCH_DRIVE else PodStatus.CLUTCH_DRIVE_ENGAGED
                        }

                        PodStatus.BASAL_PROGRAM_SET -> {
                            cannulaInsertions++
                            podStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME
                        }

                        else                        -> otherBoluses++
                    }
                }
                if (next == CommandType.PROGRAM_BASAL.value) {
                    basalPrograms++
                    if (podStatus == PodStatus.CLUTCH_DRIVE_ENGAGED) podStatus = PodStatus.BASAL_PROGRAM_SET
                }
            }

            else                              -> lastProgrammingSequence = sequence
        }
        return podDefaultStatus(podStatus.value.toInt(), lastProgrammingSequence)
    }

    /** The Android Version vector with the pod status of the model in byte 9. */
    private fun versionAnswer(): ByteArray =
        parityHex(VERSION_RESPONSE).also { it[9] = podStatus.value }

    companion object {

        /** Android `VersionResponseTest`: lot 135556289, pod sequence 611540, pod status FILLED. */
        const val VERSION_RESPONSE = "0115040A00010300040208146CC1000954D400FFFFFFFF"

        /**
         * Android `SetUniqueIdResponseTest`: pulse rate 16, prime pulse rate 8, 52 + 10 prime pulses,
         * pod life 80 h, unique id 37879809 (0x02420001), pod status UID_SET.
         */
        const val SET_UNIQUE_ID_RESPONSE = "011B13881008340A50040A00010300040308146CC1000954D402420001"

        /** The unique id the pod takes in [SET_UNIQUE_ID_RESPONSE]. */
        const val UNIQUE_ID = 0x02420001L

        /** `PodStatus.RUNNING_ABOVE_MIN_VOLUME`. */
        const val RUNNING = 0x08

        /** The delivery status of the Android capture (0, `SUSPENDED`). */
        const val CAPTURE_DELIVERY = 0x0

        /**
         * A default status (the layout of `DefaultStatusResponse`): the Android capture
         * `1d050016b00000000bff` (2 minutes since activation, reservoir above 50 U) with the pod
         * status, the last programming sequence, the bolus pulses left and the delivery status set, and
         * the active alerts ([activeAlerts], the `AlertUtil.encodeAlertSet` byte, none by default) and
         * the reservoir pulses ([reservoirPulses], 1023 by default: more than 50 U, as in the capture).
         */
        fun podDefaultStatus(
            podStatus: Int,
            lastProgrammingSequence: Int,
            bolusPulsesRemaining: Int = 0,
            deliveryStatus: Int = CAPTURE_DELIVERY,
            activeAlerts: Int = 0,
            reservoirPulses: Int = 0x3ff,
        ): ByteArray {
            val totalPulses = 0x2d
            val first = (totalPulses shl 15) or ((lastProgrammingSequence and 0x0f) shl 11) or (bolusPulsesRemaining and 0x7ff)
            // 2 minutes since activation (bits 10 to 22), then the reservoir pulses (bits 0 to 9).
            val last = ((activeAlerts and 0xff) shl 23) or (2 shl 10) or (reservoirPulses and 0x3ff)
            return byteArrayOf(0x1d, ((deliveryStatus and 0x0f) shl 4 or (podStatus and 0x0f)).toByte()) +
                ByteArray(4) { (first shr (8 * (3 - it))).toByte() } +
                ByteArray(4) { (last shr (8 * (3 - it))).toByte() }
        }
    }
}
