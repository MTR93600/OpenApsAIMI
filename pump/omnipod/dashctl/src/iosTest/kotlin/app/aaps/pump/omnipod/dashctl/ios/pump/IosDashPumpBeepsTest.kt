package app.aaps.pump.omnipod.dashctl.ios.pump

import app.aaps.core.data.model.BS
import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.pump.DetailedBolusInfo
import app.aaps.core.interfaces.pump.PumpSync
import app.aaps.pump.omnipod.dashctl.driver.DashBeepSettings
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBolusCommand
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramTempBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.command.StopDeliveryCommand.DeliveryType
import app.aaps.pump.omnipod.dashctl.pod.command.SuspendDeliveryCommand
import app.aaps.pump.omnipod.dashctl.pod.definition.BeepType
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import app.aaps.pump.omnipod.dashctl.util.BasalOnlyProfile
import app.aaps.pump.omnipod.dashctl.util.mapProfileToBasalProgram
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The beep fields of the delivery commands, as Android fills them from the user settings
 * (`BolusBeepsEnabled`, `SmbBeepsEnabled`, `BasalBeepsEnabled`, `TbrBeepsEnabled`). The settings are a
 * parameter of the pump, all off by default: the other tests check the silent bytes.
 */
class IosDashPumpBeepsTest {

    private var fixture = DashPumpOnFakePod(beeps = ALL_ON)
    private val f get() = fixture

    @AfterTest
    fun closePod() {
        f.pod.close()
        f.pod.assertNoProblems()
    }

    private fun bolusBytes(sequence: Int, units: Double, beep: Boolean): String = ProgramBolusCommand.Builder()
        .setUniqueId(UNIQUE_ID)
        .setSequenceNumber(sequence.toShort())
        .setNonce(IosDashPump.NONCE)
        .setNumberOfUnits(units)
        .setDelayBetweenPulsesInEighthSeconds(PULSE_RATE)
        .setProgramReminder(ProgramReminder(atStart = beep, atEnd = beep, atInterval = 0))
        .build().encoded.toParityHex()

    private fun stopBytes(sequence: Int, type: DeliveryType, beep: BeepType): String = StopDeliveryCommand.Builder()
        .setUniqueId(UNIQUE_ID)
        .setSequenceNumber(sequence.toShort())
        .setNonce(IosDashPump.NONCE)
        .setDeliveryType(type)
        .setBeepType(beep)
        .build().encoded.toParityHex()

    @Test
    fun `beeps are off by default`() {
        assertEquals(DashBeepSettings(false, false, false, false), DashBeepSettings())
    }

    @Test
    fun `a bolus beeps at start and end with the bolus beep setting`() = runTest {
        f.pairAndConnect()

        assertTrue(f.pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 1.0 }).success)

        assertEquals(bolusBytes(0, 1.0, beep = true), f.pod.commands.first().toParityHex())
    }

    /** Android takes `SmbBeepsEnabled` for an SMB, not `BolusBeepsEnabled`. */
    @Test
    fun `an SMB takes the SMB beep setting`() = runTest {
        fixture = DashPumpOnFakePod(beeps = ALL_ON.copy(smbBeeps = false))
        f.pairAndConnect()

        val smb = DetailedBolusInfo().apply {
            insulin = 0.5
            bolusType = BS.Type.SMB
        }
        assertTrue(f.pump.deliverTreatment(smb).success)

        assertEquals(bolusBytes(0, 0.5, beep = false), f.pod.commands.first().toParityHex())
    }

    @Test
    fun `a temp basal and the cancel before it beep with the temp basal beep setting`() = runTest {
        f.pairAndConnect()

        assertTrue(f.pump.setTempBasalAbsolute(1.0, 30, true, PumpSync.TemporaryBasalType.NORMAL).success)

        val tempBasal = ProgramTempBasalCommand.Builder()
            .setSequenceNumber(2)
            .setUniqueId(UNIQUE_ID)
            .setNonce(IosDashPump.NONCE)
            .setProgramReminder(ProgramReminder(atStart = true, atEnd = true, atInterval = 0))
            .setRateInUnitsPerHour(1.0)
            .setDurationInMinutes(30)
            .build().encoded.toParityHex()
        assertEquals(
            listOf(stopBytes(0, DeliveryType.TEMP_BASAL, BeepType.LONG_SINGLE_BEEP), tempBasal),
            f.pod.commands.map { it.toParityHex() },
            "no status known: the cancel goes first, as Android"
        )
    }

    @Test
    fun `a cancel of the temp basal beeps with the temp basal beep setting`() = runTest {
        f.pairAndConnect()

        assertTrue(f.pump.cancelTempBasal(true).success)

        assertEquals(listOf(stopBytes(0, DeliveryType.TEMP_BASAL, BeepType.LONG_SINGLE_BEEP)), f.pod.commands.map { it.toParityHex() })
    }

    @Test
    fun `the suspend and the basal program beep with the basal beep setting`() = runTest {
        f.pairAndConnect()

        assertTrue(f.pump.setNewBasalProfile(PROFILE).success)

        val suspend = SuspendDeliveryCommand.Builder()
            .setSequenceNumber(0)
            .setUniqueId(UNIQUE_ID)
            .setNonce(IosDashPump.NONCE)
            .setBeepType(BeepType.LONG_SINGLE_BEEP)
            .build().encoded.toParityHex()
        val program = ProgramBasalCommand.Builder()
            .setUniqueId(UNIQUE_ID)
            .setSequenceNumber(2)
            .setNonce(IosDashPump.NONCE)
            .setProgramReminder(ProgramReminder(atStart = true, atEnd = false, atInterval = 0))
            .setBasalProgram(mapProfileToBasalProgram(PROFILE, PumpType.OMNIPOD_DASH))
            .setCurrentTime(f.nowMs)
            .build().encoded.toParityHex()
        assertEquals(listOf(suspend, program), f.pod.commands.map { it.toParityHex() })
    }

    @Test
    fun `the stop of a bolus beeps with the bolus beep setting`() = runTest {
        f.pairAndConnect()
        f.bolusPulsesOnNextBolus = 40
        f.onDecided = { f.pump.stopBolusDelivering() }

        assertTrue(f.pump.deliverTreatment(DetailedBolusInfo().apply { insulin = 2.0 }).success)

        val stops = f.pod.commands.filter { DashPumpOnFakePod.isStopOf(it, DashPumpOnFakePod.BOLUS_BIT) }
        assertEquals(1, stops.size, "fixture: the stop was sent")
        assertEquals(stopBytes(2, DeliveryType.BOLUS, BeepType.LONG_SINGLE_BEEP), stops.single().toParityHex())
    }

    private companion object {

        /** The pod id the tests pair with, 0x02420001. */
        const val UNIQUE_ID = 37879809

        /** The pulse rate of the Android SetUniqueId answer the fixture stores. */
        const val PULSE_RATE: Byte = 16

        val ALL_ON = DashBeepSettings(bolusBeeps = true, smbBeeps = true, basalBeeps = true, tempBasalBeeps = true)

        val PROFILE = BasalOnlyProfile(0 to 0.5, 18000 to 1.0, 50400 to 3.05)
    }
}
