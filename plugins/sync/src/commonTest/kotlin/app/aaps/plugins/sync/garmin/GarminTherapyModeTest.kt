package app.aaps.plugins.sync.garmin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Garmin `/mode` sport + FCL temporary target.
 *
 * Locks the decision in `GarminPlugin.onPostMode` / `LoopHubImpl.postTherapyMode` from
 * `origin/dev_OAPSAIMI` @ `166ddb6db0`. Study source set: [GarminTherapyMode] is commonMain and has
 * no mocks, so the tests live in `commonTest` (`kotlin.test`).
 *
 * The HTTP tests on the reference (`testOnPostMode_Lunch`, `testOnPostMode_FclDefaultDuration`,
 * `testOnPostMode_Sport`, `testOnPostMode_InvalidRejected`, `testOnPostMode_Stop`) are the same
 * inputs and the same JSON.
 */
class GarminTherapyModeTest {

    private fun accepted(modeQuery: String?, durationQuery: String? = null): GarminTherapyMode.Accepted {
        val decision = GarminTherapyMode.decide(modeQuery, durationQuery)
        assertTrue(decision is GarminTherapyMode.Accepted, "expected accept, was $decision")
        return decision as GarminTherapyMode.Accepted
    }

    @Test
    fun lunchWithDuration60PostsTheKeywordAndNoTempTarget() {
        val decision = accepted("lunch", "60")
        assertEquals("""{"ok":true,"mode":"lunch","duration":60}""", decision.json)
        assertEquals("lunch", decision.mode)
        assertEquals("lunch", decision.note)
        assertEquals(60, decision.reportedDurationMin)
        assertEquals(60, GarminTherapyMode.storedDurationMin(decision.mode, decision.reportedDurationMin))
        assertNull(decision.tempTarget)
        assertFalse(decision.durationInvalid)
    }

    @Test
    fun fclWithoutDurationUses30MinutesAndPosts80MgdlFor30Minutes() {
        val decision = accepted("FCL")
        assertEquals("""{"ok":true,"mode":"fcl","duration":30}""", decision.json)
        assertEquals("fcl", decision.note)
        assertEquals(30, decision.reportedDurationMin)
        assertEquals(30, GarminTherapyMode.storedDurationMin("fcl", 30))
        assertEquals(GarminTherapyMode.FCL_TEMP_TARGET_MGDL, decision.tempTarget?.mgdl)
        assertEquals(GarminTherapyMode.FCL_TEMP_TARGET_DURATION_MIN, decision.tempTarget?.durationMin)
        assertEquals(80.0, decision.tempTarget?.mgdl)
        assertEquals(30, decision.tempTarget?.durationMin)
    }

    @Test
    fun fclCustomDurationDoesNotChangeTheCompanionTarget() {
        val decision = accepted("fcl", "45")
        assertEquals(45, decision.reportedDurationMin)
        assertEquals(80.0, decision.tempTarget?.mgdl)
        assertEquals(30, decision.tempTarget?.durationMin)
    }

    @Test
    fun sportWithDuration120PostsTheKeywordAndNoTempTarget() {
        val decision = accepted("sport", "120")
        assertEquals("""{"ok":true,"mode":"sport","duration":120}""", decision.json)
        assertEquals("sport", decision.note)
        assertEquals(120, decision.reportedDurationMin)
        assertEquals(120, GarminTherapyMode.storedDurationMin("sport", 120))
        assertNull(decision.tempTarget)
    }

    @Test
    fun sportWithoutDurationDefaultsTo120Minutes() {
        val decision = accepted("SPORT")
        assertEquals("""{"ok":true,"mode":"sport","duration":120}""", decision.json)
        assertEquals("sport", decision.note)
        assertNull(decision.tempTarget)
    }

    @Test
    fun sportWithSurroundingSpacesIsTheKeywordOnly() {
        val decision = accepted(" sport ", "90")
        assertEquals("sport", decision.note)
        assertEquals(90, decision.reportedDurationMin)
        assertNull(decision.tempTarget)
    }

    @Test
    fun anUnknownModeIsRejectedAndDoesNotSelectATarget() {
        val decision = GarminTherapyMode.decide("hack", "10")
        assertTrue(decision is GarminTherapyMode.Rejected)
        assertEquals("""{"ok":false,"error":"invalid_mode"}""", decision.json)
        assertEquals("hack", (decision as GarminTherapyMode.Rejected).rawMode)
    }

    @Test
    fun aMissingModeIsRejected() {
        val decision = GarminTherapyMode.decide(null, "10")
        assertTrue(decision is GarminTherapyMode.Rejected)
        assertEquals("", (decision as GarminTherapyMode.Rejected).rawMode)
    }

    @Test
    fun walkIsNotAGarminSportMode() {
        val decision = GarminTherapyMode.decide("walk", null)
        assertTrue(decision is GarminTherapyMode.Rejected)
    }

    @Test
    fun stopWithoutDurationReportsOneMinute() {
        val decision = accepted("stop")
        assertEquals("""{"ok":true,"mode":"stop","duration":1}""", decision.json)
        assertEquals("stop", decision.note)
        assertEquals(1, GarminTherapyMode.storedDurationMin("stop", decision.reportedDurationMin))
        assertNull(decision.tempTarget)
    }

    @Test
    fun aZeroDurationIsReportedAsZeroAndStoredAsOneMinute() {
        val decision = accepted("lunch", "0")
        assertEquals(0, decision.reportedDurationMin)
        assertEquals("""{"ok":true,"mode":"lunch","duration":0}""", decision.json)
        assertEquals(1, GarminTherapyMode.storedDurationMin("lunch", 0))
        assertEquals(1, GarminTherapyMode.storedDurationMin("stop", 0))
    }

    @Test
    fun aDurationAbove480MinutesIsClampedOnTheWatchReplyAndInStorage() {
        val decision = accepted("sport", "900")
        assertEquals(480, decision.reportedDurationMin)
        assertEquals(480, GarminTherapyMode.storedDurationMin("sport", decision.reportedDurationMin))
        assertNull(decision.tempTarget)
    }

    @Test
    fun aNegativeDurationIsReportedAsZeroAndStoredAsOneMinute() {
        val decision = accepted("meal", "-5")
        assertEquals(0, decision.reportedDurationMin)
        assertEquals(1, GarminTherapyMode.storedDurationMin("meal", decision.reportedDurationMin))
    }

    @Test
    fun anUnparseableDurationKeepsTheModeDefault() {
        val decision = accepted("sport", "abc")
        assertTrue(decision.durationInvalid)
        assertEquals("abc", decision.durationRaw)
        assertEquals(120, decision.reportedDurationMin)
        assertNull(decision.tempTarget)
    }

    @Test
    fun defaultsMatchTheReferenceMap() {
        assertEquals(60, accepted("bfast").reportedDurationMin)
        assertEquals(60, accepted("lunch").reportedDurationMin)
        assertEquals(60, accepted("dinner").reportedDurationMin)
        assertEquals(90, accepted("highcarb").reportedDurationMin)
        assertEquals(30, accepted("fcl").reportedDurationMin)
        assertEquals(120, accepted("sport").reportedDurationMin)
        assertEquals(60, accepted("meal").reportedDurationMin)
        assertEquals(30, accepted("snack").reportedDurationMin)
        assertEquals(1, accepted("stop").reportedDurationMin)
    }

    @Test
    fun onlyFclCarriesTheCompanionTarget() {
        for (mode in GarminTherapyMode.allowedModes) {
            val target = accepted(mode).tempTarget
            if (mode == "fcl") {
                assertEquals(80.0, target?.mgdl)
                assertEquals(30, target?.durationMin)
            } else {
                assertNull(target, mode)
            }
        }
    }
}
