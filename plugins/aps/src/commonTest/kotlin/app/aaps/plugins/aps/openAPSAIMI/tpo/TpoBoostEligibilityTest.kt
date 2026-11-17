package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.interfaces.aps.AimiBoostUnavailableReason
import app.aaps.plugins.aps.openAPSAIMI.advisor.tuning.TuningStepTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every guard of the manual Boost must block on its own. */
class TpoBoostEligibilityTest {

    private val now = 1_800_000_000_000L
    private val goodTick = TpoBoostTick(nowMs = now - 60_000L, bgMgdl = 165.0, deltaMgdl5m = 4.0, minBgLookback75m = 120.0, reboundGuardActive = false)

    private fun check(
        isAimi: Boolean = true,
        loopClosed: Boolean = true,
        tick: TpoBoostTick? = goodTick,
        target: Double? = 100.0,
        session: TpoSessionDocument? = null,
    ) = TpoBoostEligibility.check(isAimi, loopClosed, tick, now, target, session)

    private fun session(pack: TpoPackId, status: TpoSessionStatus = TpoSessionStatus.ACTIVE) = TpoSessionDocument(
        sessionId = "s",
        packId = pack,
        tier = TuningStepTier.MICRO,
        status = status,
        startedAtMs = now - 60_000L,
        expiresAtMs = now + 60_000L,
        triggerAlgoConfidence = 1.0,
        triggerReasonCodes = emptyList(),
        baseline = emptyMap(),
        overlay = emptyMap(),
    )

    @Test
    fun all_good_allows_the_boost() {
        assertNull(check())
    }

    @Test
    fun not_aimi_or_open_loop_blocks() {
        assertEquals(AimiBoostUnavailableReason.NOT_AIMI, check(isAimi = false))
        assertEquals(AimiBoostUnavailableReason.LOOP_NOT_CLOSED, check(loopClosed = false))
    }

    @Test
    fun only_a_running_boost_blocks_a_second_one() {
        assertEquals(AimiBoostUnavailableReason.ALREADY_ACTIVE, check(session = session(TpoPackId.MANUAL_BOOST)))
        // A protective session does not block: the Boost replaces it
        assertNull(check(session = session(TpoPackId.POST_HYPO_RECOVERY)))
        assertNull(check(session = session(TpoPackId.POOR_SLEEP_WINDOW, TpoSessionStatus.PENDING_LLM)))
        assertNull(check(session = session(TpoPackId.MANUAL_BOOST, TpoSessionStatus.REVERTED)))
    }

    @Test
    fun a_running_boost_ends_only_under_65() {
        assertTrue(TpoBoostEligibility.shouldEndForLow(64.0))
        assertFalse(TpoBoostEligibility.shouldEndForLow(65.0))
        assertFalse(TpoBoostEligibility.shouldEndForLow(68.0))
        assertFalse(TpoBoostEligibility.shouldEndForLow(Double.NaN))
    }

    @Test
    fun an_old_or_missing_tick_blocks() {
        assertEquals(AimiBoostUnavailableReason.NO_RECENT_TICK, check(tick = null))
        assertEquals(AimiBoostUnavailableReason.NO_RECENT_TICK, check(tick = goodTick.copy(nowMs = now - 11 * 60_000L)))
        assertEquals(AimiBoostUnavailableReason.NO_RECENT_TICK, check(tick = goodTick.copy(bgMgdl = Double.NaN)))
    }

    @Test
    fun a_recent_low_or_a_rebound_blocks() {
        assertEquals(AimiBoostUnavailableReason.RECENT_LOW, check(tick = goodTick.copy(minBgLookback75m = 69.0)))
        assertEquals(AimiBoostUnavailableReason.REBOUND_GUARD, check(tick = goodTick.copy(reboundGuardActive = true)))
    }

    @Test
    fun under_target_or_under_80_blocks() {
        assertEquals(AimiBoostUnavailableReason.BELOW_TARGET, check(tick = goodTick.copy(bgMgdl = 95.0)))
        assertEquals(AimiBoostUnavailableReason.BELOW_TARGET, check(tick = goodTick.copy(bgMgdl = 78.0), target = 70.0))
        assertEquals(AimiBoostUnavailableReason.BELOW_TARGET, check(tick = goodTick.copy(bgMgdl = 79.0), target = null))
    }

    @Test
    fun falling_glucose_blocks() {
        assertEquals(AimiBoostUnavailableReason.FALLING, check(tick = goodTick.copy(deltaMgdl5m = -2.0)))
        assertNull(check(tick = goodTick.copy(deltaMgdl5m = -1.9)))
    }
}
