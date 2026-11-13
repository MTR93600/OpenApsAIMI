package app.aaps.pump.omnipod.dashctl.session

import app.aaps.pump.omnipod.dashctl.parity.parityHex
import app.aaps.pump.omnipod.dashctl.pod.definition.ActivationProgress
import app.aaps.pump.omnipod.dashctl.pod.definition.AlarmType
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.DeliveryStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.PodStatus
import app.aaps.pump.omnipod.dashctl.pod.definition.SoftwareVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * The stored bytes of each [DashPodStateCodec] version. Each version is pinned by hand here, so a
 * state a phone stored before keeps decoding after the format grows. The version 3 bytes are the
 * ones the version 3 codec wrote on 2026-11-13, before the activation fields.
 */
class DashPodStateCodecVersionsTest {

    /** A full state as the version 3 codec wrote it on 2026-11-13, before the activation fields. */
    private val v3State = DashPodState(
        ltk = ByteArray(16) { it.toByte() },
        eapAkaSequenceNumber = 5L,
        messageSequenceNumber = 7,
        uniqueId = 37879809L,
        activationProgress = ActivationProgress.COMPLETED,
        bluetoothAddress = "AA:BB:CC:DD:EE:FF",
        podStatus = DashPodStatus(
            podStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
            deliveryStatus = DeliveryStatus.BASAL_ACTIVE,
            totalPulsesDelivered = 100,
            reservoirPulsesRemaining = 200,
            minutesSinceActivation = 1440,
            bolusPulsesRemaining = 0,
            activeAlerts = emptySet(),
            lastProgrammingCommandSequence = 7,
            receivedAtMs = 0x0000018000000000L,
        ),
        statusResponseCount = 9L,
        activeCommand = DashActiveCommand(
            sequence = 7,
            kind = DashCommandKind.BOLUS,
            historyId = 42L,
            statusCountAtCreation = 8L,
            sent = true,
            requestedBolusUnits = 5.0,
        ),
        pulseRate = 16,
    )

    private val v3Bytes = parityHex("03" + V3_BODY)

    /** [v3State] with every version 4 field: activation, command time, last bolus, basal programs, alerts. */
    private val v4State = v3State.copy(
        primePulseRate = 8,
        firstPrimeBolusVolume = 52,
        secondPrimeBolusVolume = 10,
        podLifeInHours = 80,
        firmwareVersion = SoftwareVersion(4, 10, 0),
        bleVersion = SoftwareVersion(1, 3, 0),
        lotNumber = 135556289L,
        podSequenceNumber = 611540L,
        reportedPodStatus = PodStatus.RUNNING_ABOVE_MIN_VOLUME,
        activationTime = 0x0000017000000000L,
        lastUpdatedSystemMs = 0x0000018000000000L,
        activeCommand = v3State.activeCommand?.copy(
            createdAtMs = 0x0000018000001000L,
            basalProgram = BasalProgram(listOf(BasalProgram.Segment(0, 10, 50), BasalProgram.Segment(10, 48, 305))),
        ),
        lastBolus = DashLastBolus(
            startTimeMs = 0x0000018000002000L,
            requestedUnits = 2.0,
            bolusUnitsRemaining = 1.5,
            deliveryComplete = false,
            historyId = 43L,
            createdAtMs = 0x0000018000001000L,
        ),
        basalProgram = BasalProgram(listOf(BasalProgram.Segment(0, 48, 100))),
        activationStepInFlight = ActivationProgress.INSERTING_CANNULA,
        suspendAlertsEnabled = true,
        expirationAlertSettings = DashExpirationAlertSettings(reminderEnabled = true, reminderHours = 9, alarmEnabled = false, alarmHours = 7),
        lowReservoirAlertSettings = DashLowReservoirAlertSettings(enabled = true, units = 20),
        tempBasal = DashTempBasal(startTimeMs = 0x0000018000003000L, rate = 1.5, durationInMinutes = 30),
        alarmType = AlarmType.ALARM_OCCLUDED,
        alarmSynced = true,
        pulsesRemaining = 200,
    )

    private val v4Bytes = parityHex("04" + V3_BODY + ACTIVATION_PART + COMMAND_TIME_AND_BOLUS_PART + PROGRAMS_PART + IN_FLIGHT_PART + ALERTS_PART + POD_PART)

    /**
     * A full version 4 state with no basal program, no step in flight, no alert part and no pod part:
     * the last bolus part ends 3 + [EMPTY_TAIL] bytes before the end.
     */
    private val v4BytesWithoutPrograms = parityHex("04" + V3_BODY + ACTIVATION_PART + COMMAND_TIME_AND_BOLUS_PART + "00" + "00" + "00" + "000000" + "00000000")

    @Test
    fun `the version 3 bytes of 2026-11-13 still decode`() {
        assertEquals(v3State, DashPodStateCodec.decode(v3Bytes))
    }

    @Test
    fun `a version 3 state has none of the version 4 fields`() {
        val state = DashPodStateCodec.decode(v3Bytes)
        assertNull(state.primePulseRate)
        assertNull(state.podLifeInHours)
        assertNull(state.firmwareVersion)
        assertNull(state.lotNumber)
        assertNull(state.reportedPodStatus)
        assertNull(state.activationTime)
        assertEquals(0L, state.lastUpdatedSystemMs)
        assertNull(state.activeCommand?.createdAtMs)
        assertNull(state.lastBolus)
        assertNull(state.basalProgram)
        assertNull(state.activeCommand?.basalProgram)
    }

    @Test
    fun `a version 3 state is written again as version 4 with nothing lost`() {
        val again = DashPodStateCodec.encode(DashPodStateCodec.decode(v3Bytes))
        assertEquals(4, again[0].toInt())
        assertEquals(v3State, DashPodStateCodec.decode(again))
    }

    @Test
    fun `every truncated version 3 state fails closed`() {
        for (length in 0 until v3Bytes.size) {
            assertFailsWith<IllegalArgumentException>("length $length") { DashPodStateCodec.decode(v3Bytes.copyOf(length)) }
        }
    }

    @Test
    fun `the encoder writes the pinned version 4 bytes`() {
        assertEquals(v4Bytes.toList(), DashPodStateCodec.encode(v4State).toList())
    }

    @Test
    fun `the pinned version 4 bytes decode`() {
        assertEquals(v4State, DashPodStateCodec.decode(v4Bytes))
    }

    @Test
    fun `every truncated version 4 state fails closed`() {
        for (length in 0 until v4Bytes.size) {
            assertFailsWith<IllegalArgumentException>("length $length") { DashPodStateCodec.decode(v4Bytes.copyOf(length)) }
        }
    }

    @Test
    fun `a trailing byte after version 4 fails closed`() {
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(v4Bytes + byteArrayOf(0)) }
    }

    @Test
    fun `a command basal program without a command fails closed`() {
        val bytes = DashPodStateCodec.encode(DashPodState())
        // Empty state: before the alert part, the command program flag, the running program flag
        // and the step in flight flag.
        val withProgram = bytes.copyOf(bytes.size - 3 - EMPTY_TAIL) + parityHex("01" + "0001" + "00" + "30" + "00000064" + "00" + "00" + "000000" + "00000000")
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(withProgram) }
    }

    @Test
    fun `an unknown basal program flag fails closed`() {
        val bytes = DashPodStateCodec.encode(DashPodState())
        // Empty state: the two program flags, then the step in flight flag and the alert part.
        bytes[bytes.size - 2 - EMPTY_TAIL] = 2
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(bytes) }
        val command = DashPodStateCodec.encode(DashPodState())
        command[command.size - 3 - EMPTY_TAIL] = 2
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(command) }
    }

    @Test
    fun `a running program alone roundtrips`() {
        val state = DashPodState(basalProgram = BasalProgram(listOf(BasalProgram.Segment(0, 2, 0), BasalProgram.Segment(2, 48, 3000))))
        assertEquals(state, DashPodStateCodec.decode(DashPodStateCodec.encode(state)))
    }

    @Test
    fun `a last bolus that ended and has no command time roundtrips`() {
        val state = DashPodState(
            lastBolus = DashLastBolus(startTimeMs = 5L, requestedUnits = 0.05, bolusUnitsRemaining = 0.0, deliveryComplete = true)
        )
        assertEquals(state, DashPodStateCodec.decode(DashPodStateCodec.encode(state)))
    }

    @Test
    fun `an unknown last bolus flag fails closed`() {
        val bytes = DashPodStateCodec.encode(DashPodState())
        // Empty state: the last bolus flag, then the two basal program flags, the step in flight flag
        // and the alert part.
        bytes[bytes.size - 4 - EMPTY_TAIL] = 2
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(bytes) }
    }

    @Test
    fun `an unknown bolus complete flag fails closed`() {
        val bytes = v4BytesWithoutPrograms.copyOf()
        // From the end: the alert part, the step in flight flag (1), the two program flags (2), the bolus
        // time (8), its flag (1), historyId (8).
        bytes[bytes.size - EMPTY_TAIL - 1 - 2 - 8 - 1 - 8 - 1] = 2
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(bytes) }
    }

    @Test
    fun `a command time without a command fails closed`() {
        val bytes = DashPodStateCodec.encode(DashPodState())
        // Empty state: the command time flag (0), the last bolus flag (0), the two basal program
        // flags (0), the step in flight flag (0), the alert part. Say a time follows.
        val withTime = bytes.copyOf(bytes.size - 4 - EMPTY_TAIL) + parityHex("0000000000000001" + "00000000" + "000000" + "00000000")
        withTime[bytes.size - 5 - EMPTY_TAIL] = 1
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(withTime) }
    }

    @Test
    fun `an unknown command time flag fails closed`() {
        val bytes = parityHex("04" + V3_BODY + ACTIVATION_PART + "01" + "0000018000001000" + "00" + "00" + "00" + "00" + "000000" + "00000000")
        assertEquals(v3State.activeCommand?.copy(createdAtMs = 0x0000018000001000L), DashPodStateCodec.decode(bytes).activeCommand)
        // From the end: the alert part, the step in flight flag, the two program flags and the last
        // bolus flag (4), the time (8), then the flag.
        bytes[bytes.size - EMPTY_TAIL - 4 - 8 - 1] = 2
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(bytes) }
    }

    @Test
    fun `each activation field roundtrips alone`() {
        val states = listOf(
            DashPodState(primePulseRate = 8),
            DashPodState(firstPrimeBolusVolume = 52),
            DashPodState(secondPrimeBolusVolume = 10),
            DashPodState(podLifeInHours = 80),
            DashPodState(firmwareVersion = SoftwareVersion(4, 10, 0)),
            DashPodState(bleVersion = SoftwareVersion(1, 3, 0)),
            DashPodState(lotNumber = 0xffffffffL),
            DashPodState(podSequenceNumber = 611540L),
            DashPodState(reportedPodStatus = PodStatus.UID_SET),
            DashPodState(activationTime = 1L),
            DashPodState(lastUpdatedSystemMs = 2L),
        )
        states.forEach { assertEquals(it, DashPodStateCodec.decode(DashPodStateCodec.encode(it))) }
    }

    @Test
    fun `an unknown activation flag fails closed`() {
        val bytes = DashPodStateCodec.encode(DashPodState())
        // Empty state: the activation flags are the u16 before the last u64, the command time flag,
        // the last bolus flag, the two basal program flags, the step in flight flag and the alert part.
        val flagsAt = bytes.size - EMPTY_TAIL - 1 - 2 - 1 - 1 - 8 - 2
        bytes[flagsAt] = 0x04
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(bytes) }
    }

    @Test
    fun `each activation step in flight roundtrips`() {
        listOf(ActivationProgress.PRIMING, ActivationProgress.INSERTING_CANNULA).forEach {
            val state = DashPodState(activationStepInFlight = it)
            assertEquals(state, DashPodStateCodec.decode(DashPodStateCodec.encode(state)))
        }
    }

    @Test
    fun `a step in flight that sends no bolus fails closed`() {
        val bytes = DashPodStateCodec.encode(DashPodState(activationStepInFlight = ActivationProgress.PRIMING))
        // The last byte before the alert part is the ordinal of the step. GOT_POD_VERSION (1) sends no
        // bolus; 13 is no step.
        listOf(1, 13).forEach { ordinal ->
            val bad = bytes.copyOf()
            bad[bad.size - 1 - EMPTY_TAIL] = ordinal.toByte()
            assertFailsWith<IllegalArgumentException>("ordinal $ordinal") { DashPodStateCodec.decode(bad) }
        }
    }

    @Test
    fun `an unknown step in flight flag fails closed`() {
        val bytes = DashPodStateCodec.encode(DashPodState())
        bytes[bytes.size - 1 - EMPTY_TAIL] = 2
        assertFailsWith<IllegalArgumentException> { DashPodStateCodec.decode(bytes) }
    }

    @Test
    fun `a version 3 state has no step in flight`() {
        assertNull(DashPodStateCodec.decode(v3Bytes).activationStepInFlight)
    }

    @Test
    fun `a version 3 state has no alert settings`() {
        val state = DashPodStateCodec.decode(v3Bytes)
        assertFalse(state.suspendAlertsEnabled)
        assertNull(state.expirationAlertSettings)
        assertNull(state.lowReservoirAlertSettings)
    }

    @Test
    fun `each alert field roundtrips alone`() {
        val states = listOf(
            DashPodState(suspendAlertsEnabled = true),
            DashPodState(expirationAlertSettings = DashExpirationAlertSettings(reminderEnabled = false, reminderHours = 0, alarmEnabled = true, alarmHours = 2)),
            DashPodState(lowReservoirAlertSettings = DashLowReservoirAlertSettings(enabled = false, units = 50)),
        )
        states.forEach { assertEquals(it, DashPodStateCodec.decode(DashPodStateCodec.encode(it))) }
    }

    @Test
    fun `an empty state ends with the empty alert part and the empty pod part`() {
        val bytes = DashPodStateCodec.encode(DashPodState())
        assertEquals(listOf<Byte>(0, 0, 0), bytes.copyOfRange(bytes.size - EMPTY_TAIL, bytes.size - EMPTY_POD_PART).toList())
        assertEquals(listOf<Byte>(0, 0, 0, 0), bytes.copyOfRange(bytes.size - EMPTY_POD_PART, bytes.size).toList())
    }

    @Test
    fun `an unknown alert flag fails closed`() {
        // Empty state: suspend alerts, expiration present, low reservoir present.
        for (index in 1..EMPTY_ALERTS) {
            val bytes = DashPodStateCodec.encode(DashPodState())
            bytes[bytes.size - EMPTY_POD_PART - index] = 2
            assertFailsWith<IllegalArgumentException>("index $index from the end") { DashPodStateCodec.decode(bytes) }
        }
        // The enabled flags inside the settings of [v4State], from the end: low reservoir enabled (after
        // it the units, 4), expiration alarm enabled (then its hours, the low reservoir part 1 + 1 + 4),
        // expiration reminder enabled (then its hours and the alarm part). The pod part comes after them.
        val full = DashPodStateCodec.encode(v4State)
        val podPart = parityHex(POD_PART).size
        listOf(4 + 1, 4 + 1 + 1 + 4 + 1, 4 + 1 + 1 + 4 + 1 + 4 + 1).forEach { fromEnd ->
            val bad = full.copyOf()
            bad[bad.size - podPart - fromEnd] = 2
            assertFailsWith<IllegalArgumentException>("$fromEnd from the end") { DashPodStateCodec.decode(bad) }
        }
    }

    @Test
    fun `a version 3 state has no temp basal and no alarm`() {
        val state = DashPodStateCodec.decode(v3Bytes)
        assertNull(state.tempBasal)
        assertNull(state.alarmType)
        assertFalse(state.alarmSynced)
        assertNull(state.pulsesRemaining, "nothing guessed from the stored status")
    }

    @Test
    fun `each pod part field roundtrips alone`() {
        val states = listOf(
            DashPodState(tempBasal = DashTempBasal(startTimeMs = 1L, rate = 0.0, durationInMinutes = 720)),
            DashPodState(alarmType = AlarmType.NONE),
            DashPodState(alarmType = AlarmType.UNKNOWN),
            DashPodState(alarmSynced = true),
            DashPodState(pulsesRemaining = 0),
            DashPodState(pulsesRemaining = 1022),
            DashPodState(pulsesRemaining = -1),
        )
        states.forEach { assertEquals(it, DashPodStateCodec.decode(DashPodStateCodec.encode(it))) }
    }

    @Test
    fun `an unknown pod part flag fails closed`() {
        // Empty state, from the end: reservoir reading present, alarm synced, alarm type present, temp basal present.
        for (index in 1..EMPTY_POD_PART) {
            val bytes = DashPodStateCodec.encode(DashPodState())
            bytes[bytes.size - index] = 2
            assertFailsWith<IllegalArgumentException>("index $index from the end") { DashPodStateCodec.decode(bytes) }
        }
    }

    @Test
    fun `an alarm type the codec does not know reads as unknown`() {
        val bytes = DashPodStateCodec.encode(DashPodState(alarmType = AlarmType.NONE))
        // From the end: reservoir reading present (1), alarm synced (1), then the alarm type value.
        bytes[bytes.size - 3] = 0xfe.toByte()
        assertEquals(AlarmType.UNKNOWN, DashPodStateCodec.decode(bytes).alarmType)
    }

    private companion object {

        /** The alert part of an empty state: suspend alerts off, no expiration and no low reservoir settings. */
        const val EMPTY_ALERTS = 3

        /** The pod part of an empty state: no temp basal, no alarm type, alarm not synced, no reservoir reading. */
        const val EMPTY_POD_PART = 4

        /** What follows the step in flight in an empty state: the alert part, then the pod part. */
        const val EMPTY_TAIL = EMPTY_ALERTS + EMPTY_POD_PART

        /** Everything of [v3State] after the version byte, as the version 3 codec wrote it. */
        const val V3_BODY =
            "07" + // flags: ltk, unique id, address
            "000102030405060708090a0b0c0d0e0f" + // ltk
            "0000000000000005" + // eapAkaSequenceNumber 5
            "00000007" + // messageSequenceNumber 7
            "0000000002420001" + // uniqueId 37879809
            "0000000c" + // activationProgress COMPLETED (ordinal 12)
            "00000011" + "41413a42423a43433a44443a45453a4646" + // "AA:BB:CC:DD:EE:FF"
            "0000000000000009" + // statusResponseCount 9
            "01" + "08" + "01" + // status present, RUNNING_ABOVE_MIN_VOLUME, BASAL_ACTIVE
            "00000064" + "000000c8" + "000005a0" + "00000000" + // 100, 200, 1440 min, 0
            "00" + "07" + "0000018000000000" + // no alert, last sequence 7, received at
            "01" + "07" + "00" + // command present, sequence 7, BOLUS
            "000000000000002a" + "0000000000000008" + // historyId 42, statusCountAtCreation 8
            "01" + "01" + "4014000000000000" + // sent, bolus present, 5.0 U
            "01" + "0010" // pulse rate 16

        /** The activation part of [v4State], after [V3_BODY]. */
        const val ACTIVATION_PART =
            "03ff" + // every activation field present
                "0008" + "0034" + "000a" + "0050" + // prime pulse rate 8, prime volumes 52 and 10, pod life 80 h
                "0004" + "000a" + "0000" + // firmware 4.10.0
                "0001" + "0003" + "0000" + // BLE 1.3.0
                "0000000008146cc1" + // lot 135556289
                "00000000000954d4" + // pod sequence 611540
                "08" + // reported pod status RUNNING_ABOVE_MIN_VOLUME
                "0000017000000000" + // activation time
                "0000018000000000" // last update

        /** The command time and the last bolus of [v4State], after [ACTIVATION_PART]. */
        const val COMMAND_TIME_AND_BOLUS_PART =
            "01" + "0000018000001000" + // command time present, then the time
                "01" + // last bolus present
                "0000018000002000" + // start time
                "4000000000000000" + // 2.0 U asked
                "3ff8000000000000" + // 1.5 U left
                "00" + // not complete
                "000000000000002b" + // historyId 43
                "01" + "0000018000001000" // command time of the bolus present, then the time

        /** The two basal programs of [v4State], after [COMMAND_TIME_AND_BOLUS_PART]. */
        const val PROGRAMS_PART =
            "01" + "0002" + // command program present, 2 segments
                "00" + "0a" + "00000032" + // slots 0..10, 0.50 U/h
                "0a" + "30" + "00000131" + // slots 10..48, 3.05 U/h
                "01" + "0001" + // running program present, 1 segment
                "00" + "30" + "00000064" // slots 0..48, 1.00 U/h

        /** The activation step in flight of [v4State], after [PROGRAMS_PART]. */
        const val IN_FLIGHT_PART =
            "01" + "0a" // present, INSERTING_CANNULA (ordinal 10)

        /** The alerts of [v4State], after [IN_FLIGHT_PART]. */
        const val ALERTS_PART =
            "01" + // suspend alerts enabled
                "01" + "01" + "00000009" + "00" + "00000007" + // expiration: reminder on 9 h, alarm off 7 h
                "01" + "01" + "00000014" // low reservoir: on, 20 U

        /** The temp basal and the alarm of [v4State], after [ALERTS_PART]. */
        const val POD_PART =
            "01" + "0000018000003000" + "3ff8000000000000" + "0000001e" + // temp basal: start, 1.5 U/h, 30 min
                "01" + "14" + // alarm type present, ALARM_OCCLUDED
                "01" + // alarm synced
                "01" + "000000c8" // last reservoir reading below 1023 present, 200 pulses
    }
}
