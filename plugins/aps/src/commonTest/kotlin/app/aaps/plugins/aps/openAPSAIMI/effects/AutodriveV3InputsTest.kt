package app.aaps.plugins.aps.openAPSAIMI.effects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Autodrive V3 must not run on an input that is not a number: `AutoDriveState.createSafe` would
 * replace the whole state by a made-up one (IOB 0, maxIOB 3, maxSMB 1) and the controller would dose
 * on it. The branch skips the tick and names the inputs.
 */
class AutodriveV3InputsTest {

    @Test
    fun `finite inputs are all usable`() {
        assertTrue(autodriveV3InputsNotANumber("bg" to 150.0, "iob" to 0.0, "cob" to -1.0).isEmpty())
    }

    @Test
    fun `every input that is not a number is named in order`() {
        assertEquals(
            listOf("cob", "maxSmb"),
            autodriveV3InputsNotANumber(
                "bg" to 150.0,
                "cob" to Double.NaN,
                "iob" to 1.2,
                "maxSmb" to Double.POSITIVE_INFINITY,
            ),
        )
    }
}
