package app.aaps.pump.omnipod.dashctl.pod.command

import app.aaps.pump.omnipod.dashctl.pod.definition.ProgramReminder
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Every bolus step of 0.05 U must be accepted, also where a cut of the double would miss the step (16.15 U). */
class ProgramBolusCommandStepTest {

    @Test
    fun everyStepOf005UpTo30UIsTaken() {
        for (pulses in 1..600) {
            val command = bolus(pulses * 0.05)
            assertTrue(command.toString().contains("numberOfTenthPulses=${pulses * 10},"), "pulses=$pulses")
        }
        assertTrue(bolus(16.15).toString().contains("numberOfTenthPulses=3230,"))
    }

    @Test
    fun anAmountOffTheStepIsStillRefused() {
        assertFailsWith<IllegalArgumentException> { bolus(0.07) }
        assertFailsWith<IllegalArgumentException> { bolus(0.0749) }
    }

    private fun bolus(units: Double): ProgramBolusCommand = ProgramBolusCommand.Builder()
        .setNumberOfUnits(units)
        .setProgramReminder(ProgramReminder(false, true, 0.toByte()))
        .setDelayBetweenPulsesInEighthSeconds(16.toByte())
        .setUniqueId(37879809)
        .setSequenceNumber(14.toShort())
        .setNonce(1229869870)
        .build()
}
