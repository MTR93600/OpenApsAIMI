package app.aaps.pump.omnipod.dashctl.util

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.pump.omnipod.dashctl.parity.toParityHex
import app.aaps.pump.omnipod.dashctl.pod.command.ProgramBasalCommand
import app.aaps.pump.omnipod.dashctl.pod.definition.BasalProgram
import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/**
 * [mapProfileToBasalProgram], ported with the Android `ProfileToBasalProgramTest` (same values, same
 * error texts), and the bytes of a mapped profile against an Android vector of
 * `ProgramBasalCommandTest`.
 */
class ProfileToBasalProgramTest {

    private fun map(vararg values: Pair<Int, Double>): BasalProgram =
        mapProfileToBasalProgram(BasalOnlyProfile(*values), PumpType.OMNIPOD_DASH)

    @Test
    fun `a valid profile maps to its segments as Android`() {
        val entries = map(0 to 0.5, 18000 to 1.0, 50400 to 3.05).segments

        assertEquals(3, entries.size)
        assertEquals(BasalProgram.Segment(0, 10, 50), entries[0])
        assertEquals(BasalProgram.Segment(10, 28, 100), entries[1])
        assertEquals(BasalProgram.Segment(28, 48, 305), entries[2])
    }

    @Test
    fun `a profile with no basal values is refused`() {
        val e = assertFailsWith<IllegalArgumentException> { map() }
        assertEquals("Basal values should contain values", e.message)
    }

    @Test
    fun `a first value that does not start at midnight is refused`() {
        val e = assertFailsWith<IllegalArgumentException> { map(1800 to 0.5) }
        assertEquals("First basal segment start time should be 0", e.message)
    }

    @Test
    fun `a value at 24 h or later is refused`() {
        val e = assertFailsWith<IllegalArgumentException> { map(0 to 0.5, 86400 to 0.5) }
        assertEquals("Basal segment start time can not be greater than 86400", e.message)
    }

    @Test
    fun `a negative start is refused`() {
        val e = assertFailsWith<IllegalArgumentException> { map(-1 to 0.5) }
        assertEquals("Basal segment start time can not be less than 0", e.message)
    }

    @Test
    fun `a start that is not on a half hour is refused`() {
        val e = assertFailsWith<IllegalArgumentException> { map(0 to 0.5, 900 to 0.5) }
        assertEquals("Basal segment time should be dividable by 30 minutes", e.message)
    }

    @Test
    fun `a rate is rounded to what the pod takes`() {
        assertEquals(5, map(0 to 0.04).segments[0].basalRateInHundredthUnitsPerHour)
    }

    /**
     * The profile of the Android vector `testProgramBasalCommandDifferentInterval`, mapped here, gives
     * the same segments and so the same bytes as Android, at the same time of day.
     */
    @Test
    fun `a mapped profile gives the bytes of the Android vector`() {
        val program = map(
            0 to 0.5, 3600 to 0.75, 10800 to 0.0, 21600 to 1.35,
            36000 to 2.7, 57600 to 2.9, 72000 to 0.95, 82800 to 0.15,
        )
        val date = LocalDateTime(3921, 9, 7, 11, 13, 50).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

        val encoded = ProgramBasalCommand.Builder()
            .setUniqueId(5)
            .setNonce(1229869870)
            .setSequenceNumber(10.toShort())
            .setBasalProgram(program)
            .setCurrentTime(date)
            .setMultiCommandFlag(false)
            .setProgramReminder(ProgramReminder(atStart = false, atEnd = true, atInterval = 0.toByte()))
            .build()
            .encoded

        assertEquals(ANDROID_DIFFERENT_INTERVAL.lowercase(), encoded.toParityHex())
    }

    private companion object {

        /** Android `ProgramBasalCommandTest.testProgramBasalCommandDifferentInterval`, unchanged. */
        const val ANDROID_DIFFERENT_INTERVAL =
            "0000000528581A1C494E532E00038E161E50000E100538075000780DB01B701D58091801133840040A100032DC82006402255100012C016E36000006EB49D200043800CB73550CA80065B9AA0910005EB5B0023A01211D28001E07270E000065"
    }
}
