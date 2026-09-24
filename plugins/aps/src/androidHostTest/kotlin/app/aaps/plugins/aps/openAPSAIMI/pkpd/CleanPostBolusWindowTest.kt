package app.aaps.plugins.aps.openAPSAIMI.pkpd

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CleanPostBolusWindowTest {

    @Test
    fun `clean window matches trajectory bias gates`() {
        assertTrue(CleanPostBolusWindow.allowsTrajectoryPeakNudge(45, 0.0))
        assertTrue(CleanPostBolusWindow.allowsTrajectoryPeakNudge(90, 12.0))
        assertFalse(CleanPostBolusWindow.allowsTrajectoryPeakNudge(45, 12.01))
        assertFalse(CleanPostBolusWindow.allowsTrajectoryPeakNudge(20, 0.0))
        assertFalse(CleanPostBolusWindow.allowsTrajectoryPeakNudge(120, 0.0))
    }

}
