package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.SoftwareVersion
import app.aaps.pump.omnipod.dashctl.pod.util.AlertUtil
import app.aaps.pump.omnipod.dashctl.pod.util.byValue

/**
 * [DashPodState] as bytes, for a platform store (the iOS Keychain).
 *
 * Common code so it is tested on the JVM and on iOS. Binary, big-endian, versioned:
 *
 * Versions 1 (the first iOS store), 2, 3 and 4 start the same:
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
 * Version 3 adds, after the version 2 part:
 * - u8 pulse rate present; if so: u16 pulse rate
 *
 * Version 4 (written today) adds, after the version 3 part:
 * - the activation fields: u16 flags: bit0 prime pulse rate, bit1 first prime bolus volume, bit2
 *   second prime bolus volume, bit3 pod life in hours, bit4 firmware version, bit5 BLE version, bit6
 *   lot number, bit7 pod sequence number, bit8 reported pod status, bit9 activation time. Any other
 *   bit is an error. Then the present ones, in that order: u16, u16, u16, u16, 3 x u16 (major,
 *   minor, interim), 3 x u16, u64, u64, u8 pod status value, u64. Then u64 lastUpdatedSystemMs.
 * - the time the active command was made ([DashActiveCommand.createdAtMs]): u8 present (0 or 1);
 *   if so: u64 createdAtMs. Present without an active command is an error.
 * - the last bolus ([DashLastBolus]): u8 present (0 or 1); if so: u64 startTimeMs, u64
 *   requestedUnits (double bits), u64 bolusUnitsRemaining (double bits), u8 deliveryComplete (0 or
 *   1), u64 historyId, u8 createdAtMs present (0 or 1) and, if so, u64 createdAtMs.
 * - the basal programs: u8 present (0 or 1); if so: the program of the active command
 *   ([DashActiveCommand.basalProgram]); present without an active command is an error. Then u8
 *   present (0 or 1); if so: the running program ([DashPodState.basalProgram]). A program is: u16
 *   segment count, then for each segment u8 start slot, u8 end slot, u32 rate in hundredths of a
 *   unit per hour. The values are not checked when read: whatever the encoder wrote reads back the
 *   same, because a state that does not decode loses the pod key. The program comes from
 *   `mapProfileToBasalProgram`, which gives 1 to 48 segments with slots 0..48.
 * - the activation step in flight ([DashPodState.activationStepInFlight]): u8 present (0 or 1); if
 *   so: u8 activation progress ordinal, which must be PRIMING or INSERTING_CANNULA.
 * - the alerts: u8 [DashPodState.suspendAlertsEnabled] (0 or 1); u8 present (0 or 1) and, if so, the
 *   expiration alert settings ([DashExpirationAlertSettings]): u8 reminder enabled (0 or 1), u32
 *   reminder hours, u8 alarm enabled (0 or 1), u32 alarm hours; u8 present (0 or 1) and, if so, the
 *   low reservoir alert settings ([DashLowReservoirAlertSettings]): u8 enabled (0 or 1), u32 units.
 *
 * Versions 1 to 3 are still read, so a pod paired before keeps its key and its open command. They
 * have none of the version 4 fields: the alert settings stay null and
 * [DashPodState.suspendAlertsEnabled] false, the activation fields and the activation step in flight stay
 * null (and [DashPodState.lastUpdatedSystemMs] 0) until the next answer sets them, the command time stays
 * null (unknown), the last bolus stays null until the next bolus, and both basal programs stay null
 * (Android parity for a pod with no confirmed program: `isThisProfileSet` is then false, and the
 * next profile is sent). Nothing is guessed from the stored status. Anything malformed throws: the
 * store then gives no state, and with no key every command is refused.
 */
object DashPodStateCodec {

    private const val VERSION_1: Byte = 1
    private const val VERSION_2: Byte = 2
    private const val VERSION_3: Byte = 3
    private const val VERSION_4: Byte = 4

    private const val FLAG_LTK = 0x01
    private const val FLAG_UNIQUE_ID = 0x02
    private const val FLAG_BT_ADDRESS = 0x04

    private const val FLAG_BOLUS = 0x01
    private const val FLAG_RATE = 0x02
    private const val FLAG_MINUTES = 0x04

    private const val FLAG_PRIME_PULSE_RATE = 0x001
    private const val FLAG_FIRST_PRIME_VOLUME = 0x002
    private const val FLAG_SECOND_PRIME_VOLUME = 0x004
    private const val FLAG_POD_LIFE = 0x008
    private const val FLAG_FIRMWARE_VERSION = 0x010
    private const val FLAG_BLE_VERSION = 0x020
    private const val FLAG_LOT_NUMBER = 0x040
    private const val FLAG_POD_SEQUENCE_NUMBER = 0x080
    private const val FLAG_REPORTED_POD_STATUS = 0x100
    private const val FLAG_ACTIVATION_TIME = 0x200
    private const val ACTIVATION_FLAGS_KNOWN = 0x3ff

    fun encode(state: DashPodState): ByteArray {
        val out = Writer()
        out.u8(VERSION_4.toInt())
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
        val pulseRate = state.pulseRate
        out.u8(if (pulseRate != null) 1 else 0)
        if (pulseRate != null) out.u16(pulseRate.toInt())

        var present = 0
        if (state.primePulseRate != null) present = present or FLAG_PRIME_PULSE_RATE
        if (state.firstPrimeBolusVolume != null) present = present or FLAG_FIRST_PRIME_VOLUME
        if (state.secondPrimeBolusVolume != null) present = present or FLAG_SECOND_PRIME_VOLUME
        if (state.podLifeInHours != null) present = present or FLAG_POD_LIFE
        if (state.firmwareVersion != null) present = present or FLAG_FIRMWARE_VERSION
        if (state.bleVersion != null) present = present or FLAG_BLE_VERSION
        if (state.lotNumber != null) present = present or FLAG_LOT_NUMBER
        if (state.podSequenceNumber != null) present = present or FLAG_POD_SEQUENCE_NUMBER
        if (state.reportedPodStatus != null) present = present or FLAG_REPORTED_POD_STATUS
        if (state.activationTime != null) present = present or FLAG_ACTIVATION_TIME
        out.u16(present)
        state.primePulseRate?.let { out.u16(it.toInt()) }
        state.firstPrimeBolusVolume?.let { out.u16(it.toInt()) }
        state.secondPrimeBolusVolume?.let { out.u16(it.toInt()) }
        state.podLifeInHours?.let { out.u16(it.toInt()) }
        state.firmwareVersion?.let { out.version(it) }
        state.bleVersion?.let { out.version(it) }
        state.lotNumber?.let { out.u64(it) }
        state.podSequenceNumber?.let { out.u64(it) }
        state.reportedPodStatus?.let { out.u8(it.value.toInt() and 0xff) }
        state.activationTime?.let { out.u64(it) }
        out.u64(state.lastUpdatedSystemMs)

        val createdAt = command?.createdAtMs
        out.u8(if (createdAt != null) 1 else 0)
        if (createdAt != null) out.u64(createdAt)

        val bolus = state.lastBolus
        out.u8(if (bolus != null) 1 else 0)
        if (bolus != null) {
            out.u64(bolus.startTimeMs)
            out.u64(bolus.requestedUnits.toRawBits())
            out.u64(bolus.bolusUnitsRemaining.toRawBits())
            out.u8(if (bolus.deliveryComplete) 1 else 0)
            out.u64(bolus.historyId)
            val bolusCreatedAt = bolus.createdAtMs
            out.u8(if (bolusCreatedAt != null) 1 else 0)
            if (bolusCreatedAt != null) out.u64(bolusCreatedAt)
        }

        out.basalProgram(command?.basalProgram)
        out.basalProgram(state.basalProgram)

        val inFlight = state.activationStepInFlight
        out.u8(if (inFlight != null) 1 else 0)
        if (inFlight != null) out.u8(inFlight.ordinal)

        out.u8(if (state.suspendAlertsEnabled) 1 else 0)
        val expiration = state.expirationAlertSettings
        out.u8(if (expiration != null) 1 else 0)
        if (expiration != null) {
            out.u8(if (expiration.reminderEnabled) 1 else 0)
            out.u32(expiration.reminderHours)
            out.u8(if (expiration.alarmEnabled) 1 else 0)
            out.u32(expiration.alarmHours)
        }
        val lowReservoir = state.lowReservoirAlertSettings
        out.u8(if (lowReservoir != null) 1 else 0)
        if (lowReservoir != null) {
            out.u8(if (lowReservoir.enabled) 1 else 0)
            out.u32(lowReservoir.units)
        }
        return out.toByteArray()
    }

    fun decode(bytes: ByteArray): DashPodState {
        val inp = Reader(bytes)
        val version = inp.u8().toByte()
        require(version in VERSION_1..VERSION_4) { "unsupported version $version" }
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
        var pulseRate: Short? = null
        if (version >= VERSION_2) {
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
        if (version >= VERSION_3 && inp.u8() == 1) pulseRate = inp.u16().toShort()
        val activation = if (version >= VERSION_4) readActivation(inp) else Activation()
        if (version >= VERSION_4) {
            val present = inp.u8()
            require(present == 0 || present == 1) { "bad command time flag $present" }
            if (present == 1) {
                val open = command
                require(open != null) { "command time without a command" }
                command = open.copy(createdAtMs = inp.u64())
            }
        }
        val lastBolus = if (version >= VERSION_4) readLastBolus(inp) else null
        var basalProgram: BasalProgram? = null
        if (version >= VERSION_4) {
            if (inp.flag("command basal program")) {
                val open = command
                require(open != null) { "command basal program without a command" }
                command = open.copy(basalProgram = readBasalProgram(inp))
            }
            if (inp.flag("basal program")) basalProgram = readBasalProgram(inp)
        }
        var inFlight: ActivationProgress? = null
        if (version >= VERSION_4 && inp.flag("activation step in flight")) {
            val ordinal = inp.u8()
            inFlight = ActivationProgress.entries.getOrNull(ordinal)
                ?.takeIf { it in DashPodState.STEPS_WITH_A_BOLUS }
                ?: throw IllegalArgumentException("bad activation step in flight $ordinal")
        }
        var suspendAlertsEnabled = false
        var expiration: DashExpirationAlertSettings? = null
        var lowReservoir: DashLowReservoirAlertSettings? = null
        if (version >= VERSION_4) {
            suspendAlertsEnabled = inp.flag("suspend alerts")
            if (inp.flag("expiration alert settings")) {
                expiration = DashExpirationAlertSettings(
                    reminderEnabled = inp.flag("expiration reminder"),
                    reminderHours = inp.u32(),
                    alarmEnabled = inp.flag("expiration alarm"),
                    alarmHours = inp.u32(),
                )
            }
            if (inp.flag("low reservoir alert settings")) {
                lowReservoir = DashLowReservoirAlertSettings(enabled = inp.flag("low reservoir alert"), units = inp.u32())
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
            pulseRate = pulseRate,
            primePulseRate = activation.primePulseRate,
            firstPrimeBolusVolume = activation.firstPrimeBolusVolume,
            secondPrimeBolusVolume = activation.secondPrimeBolusVolume,
            podLifeInHours = activation.podLifeInHours,
            firmwareVersion = activation.firmwareVersion,
            bleVersion = activation.bleVersion,
            lotNumber = activation.lotNumber,
            podSequenceNumber = activation.podSequenceNumber,
            reportedPodStatus = activation.reportedPodStatus,
            activationTime = activation.activationTime,
            lastUpdatedSystemMs = activation.lastUpdatedSystemMs,
            lastBolus = lastBolus,
            basalProgram = basalProgram,
            activationStepInFlight = inFlight,
            suspendAlertsEnabled = suspendAlertsEnabled,
            expirationAlertSettings = expiration,
            lowReservoirAlertSettings = lowReservoir,
        )
    }

    private fun readBasalProgram(inp: Reader): BasalProgram {
        val count = inp.u16()
        val segments = List(count) {
            val start = inp.u8()
            val end = inp.u8()
            BasalProgram.Segment(start.toShort(), end.toShort(), inp.u32())
        }
        return BasalProgram(segments)
    }

    private fun readLastBolus(inp: Reader): DashLastBolus? {
        if (!inp.flag("last bolus")) return null
        val startTimeMs = inp.u64()
        val requested = Double.fromBits(inp.u64())
        val remaining = Double.fromBits(inp.u64())
        val complete = inp.flag("bolus complete")
        val historyId = inp.u64()
        val createdAtMs = if (inp.flag("bolus time")) inp.u64() else null
        return DashLastBolus(
            startTimeMs = startTimeMs,
            requestedUnits = requested,
            bolusUnitsRemaining = remaining,
            deliveryComplete = complete,
            historyId = historyId,
            createdAtMs = createdAtMs,
        )
    }

    /** The activation part of version 4, read in one place. */
    private class Activation(
        val primePulseRate: Short? = null,
        val firstPrimeBolusVolume: Short? = null,
        val secondPrimeBolusVolume: Short? = null,
        val podLifeInHours: Short? = null,
        val firmwareVersion: SoftwareVersion? = null,
        val bleVersion: SoftwareVersion? = null,
        val lotNumber: Long? = null,
        val podSequenceNumber: Long? = null,
        val reportedPodStatus: PodStatus? = null,
        val activationTime: Long? = null,
        val lastUpdatedSystemMs: Long = 0L,
    )

    private fun readActivation(inp: Reader): Activation {
        val present = inp.u16()
        require(present and ACTIVATION_FLAGS_KNOWN.inv() == 0) { "unknown activation flags $present" }
        return Activation(
            primePulseRate = if (present and FLAG_PRIME_PULSE_RATE != 0) inp.u16().toShort() else null,
            firstPrimeBolusVolume = if (present and FLAG_FIRST_PRIME_VOLUME != 0) inp.u16().toShort() else null,
            secondPrimeBolusVolume = if (present and FLAG_SECOND_PRIME_VOLUME != 0) inp.u16().toShort() else null,
            podLifeInHours = if (present and FLAG_POD_LIFE != 0) inp.u16().toShort() else null,
            firmwareVersion = if (present and FLAG_FIRMWARE_VERSION != 0) inp.version() else null,
            bleVersion = if (present and FLAG_BLE_VERSION != 0) inp.version() else null,
            lotNumber = if (present and FLAG_LOT_NUMBER != 0) inp.u64() else null,
            podSequenceNumber = if (present and FLAG_POD_SEQUENCE_NUMBER != 0) inp.u64() else null,
            reportedPodStatus = if (present and FLAG_REPORTED_POD_STATUS != 0) byValue(inp.u8().toByte(), PodStatus.UNKNOWN) else null,
            activationTime = if (present and FLAG_ACTIVATION_TIME != 0) inp.u64() else null,
            lastUpdatedSystemMs = inp.u64(),
        )
    }

    private class Writer {

        private val out = ArrayList<Byte>()

        fun u8(v: Int) {
            out.add(v.toByte())
        }

        fun u16(v: Int) {
            out.add((v shr 8).toByte())
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

        /** u8 present (0 or 1), then the program if present. See the version 4 layout. */
        fun basalProgram(program: BasalProgram?) {
            u8(if (program != null) 1 else 0)
            if (program == null) return
            val segments = program.segments
            u16(segments.size)
            segments.forEach {
                u8(it.startSlotIndex.toInt())
                u8(it.endSlotIndex.toInt())
                u32(it.basalRateInHundredthUnitsPerHour)
            }
        }

        fun version(v: SoftwareVersion) {
            u16(v.major.toInt())
            u16(v.minor.toInt())
            u16(v.interim.toInt())
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

        fun u16(): Int {
            need(2)
            val high = b[p++].toInt() and 0xff
            return (high shl 8) or (b[p++].toInt() and 0xff)
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

        fun version(): SoftwareVersion =
            SoftwareVersion(major = u16().toShort(), minor = u16().toShort(), interim = u16().toShort())

        /** A u8 that must be 0 or 1. */
        fun flag(name: String): Boolean {
            val v = u8()
            require(v == 0 || v == 1) { "bad $name flag $v" }
            return v == 1
        }

        fun atEnd(): Boolean = p == b.size
    }
}
