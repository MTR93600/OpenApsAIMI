package app.aaps.plugins.aps.openAPSAIMI.physio

import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.openAPSAIMI.MapPreferences
import app.aaps.plugins.aps.openAPSAIMI.NoOpAapsLogger
import app.aaps.plugins.aps.openAPSAIMI.TherapyNotePersistence
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.gate.CosineTrajectoryGate
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiHealthContext
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiPhysioSource
import app.aaps.plugins.aps.openAPSAIMI.tpo.InMemoryAimiStorage
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStudyLocations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Step S3b: a physio context stored while sleep was on (it stays valid for 20 hours) must not keep
 * acting once the person turns sleep off. Runs through the real decision adapter and the real context
 * store, the read every dosing path makes.
 */
class EffectiveContextSwitchesTest {

    private object NoHealthContext : AimiHealthContext {
        override fun fetchSnapshot(): HealthContextSnapshot = HealthContextSnapshot.EMPTY
        override fun fetchSnapshotForAutodriveGater(): HealthContextSnapshot = HealthContextSnapshot.EMPTY
        override fun getLastSnapshot(): HealthContextSnapshot = HealthContextSnapshot.EMPTY
    }

    private object NoPhysioSource : AimiPhysioSource {
        override suspend fun fetchLastHeartRate(): Int = 0
        override suspend fun fetchStepsData(daysBack: Int, ignoreUnifiedSourceMode: Boolean): Int = 0
    }

    private object NoStudyLocations : AimiStudyLocations {
        override fun studyDirectories(): List<AimiPath> = emptyList()
    }

    private val poorSleepContext = PhysioContextMTR(
        state = PhysioStateMTR.RECOVERY_NEEDED,
        confidence = 0.7,
        poorSleepDetected = true,
        sleepDeviationZ = -2.0,
        recommendReduceSMB = true,
        timestamp = aimiWallClockMs(),
        validUntil = aimiWallClockMs() + 20 * 60 * 60 * 1000L,
    )

    private fun adapter(preferences: MapPreferences): AIMIInsulinDecisionAdapterMTR {
        val store = AIMIPhysioContextStoreMTR(NoOpAapsLogger, InMemoryAimiStorage(), NoStudyLocations)
        store.updateContext(poorSleepContext)
        return AIMIInsulinDecisionAdapterMTR(
            repo = NoHealthContext,
            persistenceLayer = TherapyNotePersistence(emptyList()),
            dataRepository = NoPhysioSource,
            contextStore = store,
            relevanceGate = CosineTrajectoryGate(preferences, NoOpAapsLogger),
            preferences = preferences,
            aapsLogger = NoOpAapsLogger,
        )
    }

    @Test
    fun `with sleep on the stored poor sleep verdict is used`() {
        val context = adapter(MapPreferences()).getEffectiveContext()
        assertTrue(context.poorSleepDetected)
        assertEquals(PhysioStateMTR.RECOVERY_NEEDED, context.state)
        assertTrue(context.recommendReduceSMB)
    }

    @Test
    fun `with sleep off the stored poor sleep verdict no longer acts`() {
        val preferences = MapPreferences().apply { booleans[BooleanKey.AimiPhysioSleepDataEnable] = false }
        val context = adapter(preferences).getEffectiveContext()
        assertFalse(context.poorSleepDetected)
        assertEquals(0.0, context.sleepDeviationZ)
        assertEquals(PhysioStateMTR.OPTIMAL, context.state)
        assertFalse(context.recommendReduceSMB)
    }
}
