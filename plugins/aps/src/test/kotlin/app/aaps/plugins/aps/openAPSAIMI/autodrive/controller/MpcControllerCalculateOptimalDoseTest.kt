package app.aaps.plugins.aps.openAPSAIMI.autodrive.controller

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.autodrive.models.AutoDriveState
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MpcControllerCalculateOptimalDoseTest {

    private fun controller(): MpcController {
        val logger = mockk<AAPSLogger>(relaxed = true)
        val preferences = mockk<Preferences> {
            every { get(DoubleKey.OApsAIMIMpcInsulinUPerKgPerStep) } returns 0.065
        }
        return MpcController(logger, preferences)
    }

    /** Matches isHyperPlateauQuiet — only horizon 180 is populated in optimalDoses. */
    private fun hyperPlateauQuietState() = AutoDriveState.createSafe(
        bg = 164.0,
        bgVelocity = 0.1,
        iob = 2.5,
        estimatedRa = 1.0,
        combinedDelta = 0.5,
        isNight = false,
        hour = 14,
    )

    @Test
    fun `hyper plateau quiet mode does not throw when 60 and 120 horizons are skipped`() {
        val command = controller().calculateOptimalDose(
            state = hyperPlateauQuietState(),
            profileBasal = 1.0,
            lgsThreshold = 70.0,
        )
        assertTrue(command.reason.contains("H:[60:n/a|120:n/a|180:"))
    }

    @Test
    fun `standard mode includes 60 and 120 horizon values in reason`() {
        val command = controller().calculateOptimalDose(
            state = AutoDriveState.createSafe(
                bg = 164.0,
                bgVelocity = 2.0,
                iob = 2.5,
                estimatedRa = 1.0,
                combinedDelta = 3.0,
                isNight = false,
            ),
            profileBasal = 1.0,
            lgsThreshold = 70.0,
        )
        assertTrue(command.reason.contains("|120:"))
        assertTrue(command.reason.contains("|180:"))
        assertTrue(!command.reason.contains("|120:n/a|"))
    }
}
