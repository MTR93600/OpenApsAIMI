package app.aaps.plugins.aps.openAPSAIMI.ISF

import app.aaps.plugins.aps.openAPSAIMI.IsfSourceTelemetry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Holds the order of the last two steps of the commanded sensitivity in place.
 *
 * The witness has to read the value **before** the profile-relative floor. Read after it, its own
 * lower bound has already been applied, so it can never report a hit: on the night of 2026-09-05
 * `isf_profile_relative_bound_hit` was `false` on 709 ticks out of 709 while the floor really set
 * the commanded value on 244 night ticks out of 474.
 *
 * The first test below fails with the old order and passes with the new one. The last test is the
 * non-negotiable part: the commanded number itself must not move.
 *
 * Matching tests from `origin/dev_OAPSAIMI` @ `db21308e6c` (file SHA `5600100dcb`, unchanged on tip
 * `c5db5a0333`). Study source set: [CommandedIsf] is commonMain and has no mocks, so the tests live
 * in `commonTest` (`kotlin.test`) — same layout as [DynIsfCacheTest] / [ObservedSensitivityMeterTest],
 * not `androidHostTest` (mockito/Robolectric). Assertions are the same locks as the Truth/JUnit
 * reference corpus.
 */
class CommandedIsfOrderTest {

    private val profileIsf = 120.0

    /** A tick where the floor really binds: 0.4 x profile, well under the 0.5 x lower bound. */
    private val preFloorBelowBound = 48.0

    /** A tick where nothing binds: 1.0 x profile. */
    private val preFloorInsideBounds = 120.0

    @Test
    fun `the witness sees the value before the floor so it can report a hit`() {
        CommandedIsf.floorAgainstProfileAndRecordShadow(
            preFloorMgdlPerU = preFloorBelowBound,
            profileIsfMgdlPerU = profileIsf,
        )

        assertEquals(true, IsfSourceTelemetry.lastProfileRelativeBoundHit)
        assertEquals(
            profileIsf * DynamicSensitivityPolicy.PROFILE_RELATIVE_FLOOR,
            IsfSourceTelemetry.lastProfileRelativeShadowMgdl!!,
            absoluteTolerance = 1e-9,
        )
    }

    @Test
    fun `a value inside the bounds is not reported as a hit`() {
        CommandedIsf.floorAgainstProfileAndRecordShadow(
            preFloorMgdlPerU = preFloorInsideBounds,
            profileIsfMgdlPerU = profileIsf,
        )

        assertEquals(false, IsfSourceTelemetry.lastProfileRelativeBoundHit)
        assertEquals(
            preFloorInsideBounds,
            IsfSourceTelemetry.lastProfileRelativeShadowMgdl!!,
            absoluteTolerance = 1e-9,
        )
    }

    @Test
    fun `the pre-floor value is kept for the export and is not the commanded one`() {
        val commanded = CommandedIsf.floorAgainstProfileAndRecordShadow(
            preFloorMgdlPerU = preFloorBelowBound,
            profileIsfMgdlPerU = profileIsf,
        )

        assertEquals(preFloorBelowBound, CommandedIsf.lastPreFloorMgdlPerU!!, absoluteTolerance = 1e-9)
        assertTrue(commanded > CommandedIsf.lastPreFloorMgdlPerU!!)
    }

    @Test
    fun `a non-finite pre-floor value is exported as unknown never as a number`() {
        CommandedIsf.floorAgainstProfileAndRecordShadow(
            preFloorMgdlPerU = Double.NaN,
            profileIsfMgdlPerU = profileIsf,
        )

        assertNull(CommandedIsf.lastPreFloorMgdlPerU)
        assertNull(IsfSourceTelemetry.lastProfileRelativeBoundHit)
    }

    @Test
    fun `the commanded sensitivity is exactly what the floor alone returns`() {
        val cases = listOf(
            preFloorBelowBound to profileIsf,
            preFloorInsideBounds to profileIsf,
            4.54 to 30.0,
            300.0 to 120.0,
            0.069 to 120.0,
        )

        cases.forEach { (preFloor, profile) ->
            assertEquals(
                DynamicSensitivityPolicy.floorAgainstProfile(
                    commandedMgdlPerU = preFloor,
                    profileIsfMgdlPerU = profile,
                ),
                CommandedIsf.floorAgainstProfileAndRecordShadow(
                    preFloorMgdlPerU = preFloor,
                    profileIsfMgdlPerU = profile,
                ),
            )
        }

        // A missing profile must fail open on both paths: the input comes back unchanged.
        assertEquals(
            4.54,
            CommandedIsf.floorAgainstProfileAndRecordShadow(
                preFloorMgdlPerU = 4.54,
                profileIsfMgdlPerU = null,
            ),
        )
    }
}
