package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step S2 removed the Control Center. TPO used its draft to read the autonomy mode, so the gate now
 * reads the real Autodrive switches directly, with the same mapping as before: TPO may start a
 * session only from "assisted" up (hyper trajectory release, authoritative Autodrive or recursive
 * belief authority, each with Autodrive on).
 */
class TpoAutonomyGateTest {

    private fun prefs(
        active: Boolean,
        authoritative: Boolean = false,
        hyperTrajectoryRelease: Boolean = false,
        recursiveBeliefAuthority: Boolean = false,
    ) = MapPreferences().apply {
        booleans[BooleanKey.OApsAIMIautoDriveActive] = active
        booleans[BooleanKey.OApsAIMIautoDriveAuthoritative] = authoritative
        booleans[BooleanKey.OApsAIMIHyperTrajectoryRelease] = hyperTrajectoryRelease
        booleans[BooleanKey.OApsAIMIRecursiveBeliefAuthority] = recursiveBeliefAuthority
    }

    @Test
    fun `TPO needs Autodrive with at least the hyper trajectory release`() {
        assertFalse(TpoOrchestrator.autonomyAllowsSession(prefs(active = false, authoritative = true, hyperTrajectoryRelease = true)))
        assertFalse(TpoOrchestrator.autonomyAllowsSession(prefs(active = true)))
        assertTrue(TpoOrchestrator.autonomyAllowsSession(prefs(active = true, hyperTrajectoryRelease = true)))
        assertTrue(TpoOrchestrator.autonomyAllowsSession(prefs(active = true, authoritative = true)))
        assertTrue(TpoOrchestrator.autonomyAllowsSession(prefs(active = true, recursiveBeliefAuthority = true)))
        // The key defaults (all on) allow TPO, as before.
        assertTrue(TpoOrchestrator.autonomyAllowsSession(MapPreferences()))
    }

    @Test
    fun `the gate does not depend on dose limits or other display inputs`() {
        val low = prefs(active = true).apply { DoubleKey.entries.forEach { doubles[it] = it.min } }
        val high = prefs(active = true).apply { DoubleKey.entries.forEach { doubles[it] = it.max } }
        assertEquals(TpoOrchestrator.autonomyAllowsSession(low), TpoOrchestrator.autonomyAllowsSession(high))
    }
}
