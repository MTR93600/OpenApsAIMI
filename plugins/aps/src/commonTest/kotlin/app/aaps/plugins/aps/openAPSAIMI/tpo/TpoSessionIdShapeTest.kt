package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The shape of the session id a time-period override writes.
 *
 * The id used to come from `java.util.UUID.randomUUID()`, which does not exist outside the JVM. It
 * now comes from `kotlin.uuid.Uuid.random()`. The id is written to the session file as `session_id`
 * and read back from it, so the printed shape has to be the one older builds stored: 36 characters,
 * lowercase hex, grouped 8-4-4-4-12. That is what these tests pin, and they pin it on the real
 * write path rather than on the generator, so a later change of generator is caught here.
 *
 * Study source set: [TpoSessionManager] moved to commonMain in this change, and the write path needs
 * no `Preferences`, so these live in `commonTest` (`kotlin.test`) and run on Native too. Storage is
 * an [InMemoryAimiStorage], because Mockito is JVM only. Names are camelCase (no backtick commas).
 */
class TpoSessionIdShapeTest {

    /** 8-4-4-4-12 lowercase hex, and nothing else. */
    private val uuidShape = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    private val plan = TpoApplyPlan(
        proposal = TpoProposal(
            packId = TpoPackId.POST_HYPO_RECOVERY,
            tier = TuningStepTier.MODERATE,
            algoConfidence = 0.8,
            reasonCodes = listOf("test"),
        ),
        changes = emptyList(),
    )

    private fun savedSessionId(): String {
        val storage = InMemoryAimiStorage()
        val manager = TpoSessionManager(TpoPersistence(storage))
        manager.savePendingSession(plan, nowMs = 1_000L)
        val saved = TpoPersistence(storage).loadSession()
        return requireNotNull(saved) { "the pending session was not written" }.sessionId
    }

    @Test
    fun theSessionIdIsPrintedAsALowercaseHexUuid() {
        val id = savedSessionId()
        assertTrue(uuidShape.matches(id), "session id is not an 8-4-4-4-12 lowercase hex uuid: $id")
    }

    @Test
    fun theSessionIdIsThirtySixCharactersLong() {
        assertEquals(36, savedSessionId().length)
    }

    @Test
    fun twoSessionsDoNotShareAnId() {
        assertNotEquals(savedSessionId(), savedSessionId())
    }

    @Test
    fun theSessionIdSurvivesTheJsonRoundTrip() {
        val storage = InMemoryAimiStorage()
        val manager = TpoSessionManager(TpoPersistence(storage))
        manager.savePendingSession(plan, nowMs = 1_000L)
        val written = requireNotNull(TpoPersistence(storage).loadSession()).sessionId
        // Read a second time through a fresh reader: the id is stored as text and must come back
        // byte for byte, which is what makes an id from an older build still match.
        assertEquals(written, requireNotNull(TpoPersistence(storage).loadSession()).sessionId)
    }
}
