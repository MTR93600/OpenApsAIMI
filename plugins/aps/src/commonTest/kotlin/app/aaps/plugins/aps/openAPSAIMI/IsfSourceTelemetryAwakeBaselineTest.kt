package app.aaps.plugins.aps.openAPSAIMI

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The stress-floor export must stay readable when the signature is active and when the awake
 * baseline is missing.
 *
 * Source: `origin/dev_OAPSAIMI` @ `6a6561caab` hunks of `IsfSourceTelemetryTest`. Study: commonTest,
 * because [IsfSourceTelemetry] is commonMain.
 */
class IsfSourceTelemetryAwakeBaselineTest {

    @BeforeTest
    fun reset() {
        IsfSourceTelemetry.recordStressIsfFloor(
            active = false,
            reason = "reset",
            flooredIsfMgdl = null,
            awakeRestingBpm = null,
        )
    }

    @Test
    fun `the stress floor value is written on an active tick armed or not`() {
        // It used to be dropped whenever it equalled the commanded value, and while the key is armed
        // those two are equal by construction: the field was null on 0 of 159, 0 of 953 and 0 of 710
        // active ticks of three packages.
        IsfSourceTelemetry.recordStressIsfFloor(
            active = true,
            reason = "active hr=92 rest=69 excess=23 steps15=0 held=12.0min",
            flooredIsfMgdl = 60.0,
            awakeRestingBpm = 69,
        )

        assertTrue(IsfSourceTelemetry.lastStressIsfFloorActive == true)
        assertEquals(60.0, IsfSourceTelemetry.lastStressIsfFloorIsfMgdl!!, absoluteTolerance = 1e-9)
        assertEquals(69, IsfSourceTelemetry.lastStressIsfFloorAwakeRestingBpm)
    }

    @Test
    fun `a gesture standing down for want of data records no baseline`() {
        IsfSourceTelemetry.recordStressIsfFloor(
            active = false,
            reason = "no_hr hr=80 rest=0 steps15=0",
            flooredIsfMgdl = null,
            awakeRestingBpm = null,
        )

        assertFalse(IsfSourceTelemetry.lastStressIsfFloorActive == true)
        assertNull(IsfSourceTelemetry.lastStressIsfFloorIsfMgdl)
        assertNull(IsfSourceTelemetry.lastStressIsfFloorAwakeRestingBpm)
    }
}
