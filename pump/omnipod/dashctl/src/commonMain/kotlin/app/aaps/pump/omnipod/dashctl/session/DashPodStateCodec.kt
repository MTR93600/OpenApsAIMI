package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.util.AlertUtil
import app.aaps.pump.omnipod.dashctl.pod.util.byValue

/**
 * [DashPodState] as bytes, for a platform store (the iOS Keychain).
 *
 * Common code so it is tested on the JVM and on iOS. Binary, big-endian, versioned:
 *
 * Version 1 (the first iOS store) and version 2 start the same:
 * - u8 version
 * - u8 flags: bit0 = ltk present, bit1 = uniqueId present, bit2 = bluetoothAddress present
 * - [16] ltk if present
 * - u64 eapAkaSequenceNumber, u32 messageSequenceNumber
 * - u64 uniqueId if present
 * - u32 activationProgress ordinal
 * - u32 + bytes bluetoothAddress (UTF-8) if present
 *
 * Version 2 adds, after that:
 * - u64 statusResponseCount
 * - u8 pod status present; if so: u8 podStatus value, u8 deliveryStatus value, u32 total pulses,
 *   u32 reservoir pulses, u32 minutes since activation, u32 bolus pulses remaining, u8 alert set,
 *   u8 last programming sequence, u64 received at
 * - u8 active command present; if so: u8 sequence, u8 kind ordinal, u64 historyId,
 *   u64 statusCountAtCreation, u8 sent, u8 flags (bit0 bolus, bit1 rate, bit2 minutes), then the
 *   present ones: u64 bolus (double bits), u64 rate (double bits), u32 minutes
 *
 * Version 1 is still read, so a pod paired before this keeps its key. Anything malformed throws: the
 * store then gives no state, and with no key every command is refused.
 */
object DashPodStateCodec {

    private const val VERSION_1: Byte = 1
    private const val VERSION_2: Byte = 2

    private const val FLAG_LTK = 0x01
    private const val FLAG_UNIQUE_ID = 0x02
    private const val FLAG_BT_ADDRESS = 0x04

    private const val FLAG_BOLUS = 0x01
    private const val FLAG_RATE = 0x02
    private const val FLAG_MINUTES = 0x04

    fun encode(state: DashPodState): ByteArray {
        val out = Writer()
        out.u8(VERSION_2.toInt())
        val addressBytes = state.bluetoothAddress?.encodeToByteArray()
        var flags = 0
        if (state.ltk != null) flags = flags or FLAG_LTK
        if (state.uniqueId != null) flags = flags or FLAG_UNIQUE_ID
        if (addressBytes != null) flags = flags or FLAG_BT_ADDRESS
        out.u8(flags)
        state.ltk?.let { out.bytes(it) }
        out.u64(state.eapAkaSequenceNumber)
        out.u32(state.messageSequenceNumber)
        state.uniqueId?.let { out.u64(it) }
        out.u32(state.activationProgress.ordinal)
        addressBytes?.let {
            out.u32(it.size)
            out.bytes(it)
        }

        out.u64(state.statusResponseCount)
        val status = state.podStatus
        out.u8(if (status != null) 1 else 0)
        if (status != null) {
            out.u8(status.podStatus.value.toInt() and 0xff)
            out.u8(status.deliveryStatus.value.toInt() and 0xff)
            out.u32(status.totalPulsesDelivered)
            out.u32(status.reservoirPulsesRemaining)
            out.u32(status.minutesSinceActivation)
            out.u32(status.bolusPulsesRemaining)
            out.u8(AlertUtil.encodeAlertSet(status.activeAlerts).toInt() and 0xff)
            out.u8(status.lastProgrammingCommandSequence)
            out.u64(status.receivedAtMs)
        }
        val command = state.activeCommand
        out.u8(if (command != null) 1 else 0)
        if (command != null) {
            out.u8(command.sequence)
            out.u8(command.kind.ordinal)
            out.u64(command.historyId)
            out.u64(command.statusCountAtCreation)
            out.u8(if (command.sent) 1 else 0)
            var present = 0
            if (command.requestedBolusUnits != null) present = present or FLAG_BOLUS
            if (command.tempBasalRate != null) present = present or FLAG_RATE
            if (command.tempBasalMinutes != null) present = present or FLAG_MINUTES
            out.u8(present)
            command.requestedBolusUnits?.let { out.u64(it.toRawBits()) }
            command.tempBasalRate?.let { out.u64(it.toRawBits()) }
            command.tempBasalMinutes?.let { out.u32(it) }
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): DashPodState {
        val inp = Reader(bytes)
        val version = inp.u8().toByte()
        require(version == VERSION_1 || version == VERSION_2) { "unsupported version $version" }
        val flags = inp.u8()
        val ltk = if (flags and FLAG_LTK != 0) inp.bytes(DashPodState.LTK_SIZE) else null
        val eapSqn = inp.u64()
        val msgSeq = inp.u32()
        val uniqueId = if (flags and FLAG_UNIQUE_ID != 0) inp.u64() else null
        val progressOrdinal = inp.u32()
        val progress = ActivationProgress.entries.getOrNull(progressOrdinal)
            ?: throw IllegalArgumentException("bad progress ordinal $progressOrdinal")
        val address = if (flags and FLAG_BT_ADDRESS != 0) {
            val length = inp.u32()
            require(length >= 0) { "bad address length" }
            inp.bytes(length).decodeToString()
        } else {
            null
        }
        var statusCount = 0L
        var status: DashPodStatus? = null
        var command: DashActiveCommand? = null
        if (version == VERSION_2) {
            statusCount = inp.u64()
            if (inp.u8() == 1) {
                status = DashPodStatus(
                    podStatus = byValue(inp.u8().toByte(), PodStatus.UNKNOWN),
                    deliveryStatus = byValue(inp.u8().toByte(), DeliveryStatus.UNKNOWN),
                    totalPulsesDelivered = inp.u32(),
                    reservoirPulsesRemaining = inp.u32(),
                    minutesSinceActivation = inp.u32(),
                    bolusPulsesRemaining = inp.u32(),
                    activeAlerts = AlertUtil.decodeAlertSet(inp.u8().toByte()),
                    lastProgrammingCommandSequence = inp.u8(),
                    receivedAtMs = inp.u64(),
                )
            }
            if (inp.u8() == 1) {
                val sequence = inp.u8()
                val kind = DashCommandKind.entries.getOrNull(inp.u8())
                    ?: throw IllegalArgumentException("bad command kind")
                val historyId = inp.u64()
                val countAtCreation = inp.u64()
                val sent = inp.u8() == 1
                val present = inp.u8()
                command = DashActiveCommand(
                    sequence = sequence,
                    kind = kind,
                    historyId = historyId,
                    statusCountAtCreation = countAtCreation,
                    sent = sent,
                    requestedBolusUnits = if (present and FLAG_BOLUS != 0) Double.fromBits(inp.u64()) else null,
                    tempBasalRate = if (present and FLAG_RATE != 0) Double.fromBits(inp.u64()) else null,
                    tempBasalMinutes = if (present and FLAG_MINUTES != 0) inp.u32() else null,
                )
            }
        }
        require(inp.atEnd()) { "trailing bytes" }
        return DashPodState(
            ltk = ltk,
            eapAkaSequenceNumber = eapSqn,
            messageSequenceNumber = msgSeq,
            uniqueId = uniqueId,
            activationProgress = progress,
            bluetoothAddress = address,
            podStatus = status,
            statusResponseCount = statusCount,
            activeCommand = command,
        )
    }

    private class Writer {

        private val out = ArrayList<Byte>()

        fun u8(v: Int) {
            out.add(v.toByte())
        }

        fun u32(v: Int) {
            for (i in 3 downTo 0) out.add((v shr (i * 8)).toByte())
        }

        fun u64(v: Long) {
            for (i in 7 downTo 0) out.add((v shr (i * 8)).toByte())
        }

        fun bytes(b: ByteArray) {
            b.forEach { out.add(it) }
        }

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class Reader(private val b: ByteArray) {

        private var p = 0

        private fun need(n: Int) {
            require(n >= 0 && p + n <= b.size) { "truncated" }
        }

        fun u8(): Int {
            need(1)
            return b[p++].toInt() and 0xff
        }

        fun u32(): Int {
            need(4)
            var v = 0
            repeat(4) { v = (v shl 8) or (b[p++].toInt() and 0xff) }
            return v
        }

        fun u64(): Long {
            need(8)
            var v = 0L
            repeat(8) { v = (v shl 8) or (b[p++].toLong() and 0xff) }
            return v
        }

        fun bytes(n: Int): ByteArray {
            need(n)
            return b.copyOfRange(p, p + n).also { p += n }
        }

        fun atEnd(): Boolean = p == b.size
    }
}
