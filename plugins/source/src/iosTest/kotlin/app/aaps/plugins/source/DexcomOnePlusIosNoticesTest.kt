package app.aaps.plugins.source

import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Which warm-up states reach the person on iPhone, and how often.
 *
 * Every post on iPhone is a banner, so the rule under test is "post when the message changes".
 * No test name here may contain a comma: these run on Kotlin/Native.
 */
class DexcomOnePlusIosNoticesTest {

    private val notices = DexcomOnePlusIosNotices()
    private val now = 1_000_000L

    private fun state(
        phase: OnePlusWarmupState.Phase,
        remainingMs: Long? = null,
        endsAtEpochMs: Long? = null,
        advSilenceMinutes: Long? = null,
        staleMacSuspected: Boolean = false,
    ) = OnePlusWarmupState(
        phase = phase,
        remainingMs = remainingMs,
        endsAtEpochMs = endsAtEpochMs,
        advSilenceMinutes = advSilenceMinutes,
        staleMacSuspected = staleMacSuspected,
    )

    private fun decide(state: OnePlusWarmupState) = notices.decide(state, now)

    @Test
    fun pairingIsToldOnceSoThePersonLooksForTheIphoneDialog() {
        val first = decide(state(OnePlusWarmupState.Phase.PAIRING))
        val second = decide(state(OnePlusWarmupState.Phase.PAIRING))

        assertEquals(SourceStrings.dexcom_oneplus_ios_notice_pairing, assertIs<DexcomOnePlusIosNotices.Decision.Post>(first).text)
        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, second)
    }

    @Test
    fun theNormalFiveMinuteConnectPostsNothing() {
        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, decide(state(OnePlusWarmupState.Phase.CONNECTING)))
        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, decide(state(OnePlusWarmupState.Phase.RECONNECTING)))
        assertEquals(DexcomOnePlusIosNotices.Decision.Dismiss, decide(state(OnePlusWarmupState.Phase.READY)))
        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, decide(state(OnePlusWarmupState.Phase.CONNECTING)))
    }

    @Test
    fun warmingCarriesItsEndTimeFromTheProtocolClock() {
        val post = assertIs<DexcomOnePlusIosNotices.Decision.Post>(
            decide(state(OnePlusWarmupState.Phase.WARMING, remainingMs = 600_000L)),
        )

        assertEquals(SourceStrings.dexcom_oneplus_ios_notice_warming_until, post.text)
        assertEquals(now + 600_000L, post.endsAtMs)
    }

    @Test
    fun warmingIsPostedAgainOnlyWhenTheEndTimeBecomesKnown() {
        val noEnd = decide(state(OnePlusWarmupState.Phase.WARMING))
        val sameAgain = decide(state(OnePlusWarmupState.Phase.WARMING))
        val withEnd = decide(state(OnePlusWarmupState.Phase.WARMING, endsAtEpochMs = now + 60_000L))
        val endLostAgain = decide(state(OnePlusWarmupState.Phase.WARMING))

        assertEquals(SourceStrings.dexcom_oneplus_ios_notice_warming, assertIs<DexcomOnePlusIosNotices.Decision.Post>(noEnd).text)
        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, sameAgain)
        assertIs<DexcomOnePlusIosNotices.Decision.Post>(withEnd)
        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, endLostAgain)
    }

    @Test
    fun aWarmupCardStaysUpThroughADutyCycleReconnect() {
        decide(state(OnePlusWarmupState.Phase.WARMING, remainingMs = 600_000L))

        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, decide(state(OnePlusWarmupState.Phase.CONNECTING)))
        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, decide(state(OnePlusWarmupState.Phase.WARMING, remainingMs = 300_000L)))
    }

    @Test
    fun aShortSilenceIsNotToldButALongOneIsAndThenRepeatedLater() {
        val short = decide(state(OnePlusWarmupState.Phase.RECONNECTING, advSilenceMinutes = 5L))
        val long = decide(state(OnePlusWarmupState.Phase.RECONNECTING, advSilenceMinutes = 12L))
        val bitLonger = decide(state(OnePlusWarmupState.Phase.RECONNECTING, advSilenceMinutes = 20L))
        val muchLonger = decide(state(OnePlusWarmupState.Phase.RECONNECTING, advSilenceMinutes = 27L))

        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, short)
        assertEquals(12, assertIs<DexcomOnePlusIosNotices.Decision.Post>(long).minutes)
        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, bitLonger)
        assertEquals(27, assertIs<DexcomOnePlusIosNotices.Decision.Post>(muchLonger).minutes)
    }

    @Test
    fun aWrongSensorNearbyIsToldOnce() {
        val first = decide(state(OnePlusWarmupState.Phase.RECONNECTING, staleMacSuspected = true))
        val second = decide(state(OnePlusWarmupState.Phase.RECONNECTING, staleMacSuspected = true))

        assertEquals(SourceStrings.dexcom_oneplus_ios_notice_stale_sensor, assertIs<DexcomOnePlusIosNotices.Decision.Post>(first).text)
        assertEquals(DexcomOnePlusIosNotices.Decision.Keep, second)
    }

    @Test
    fun aFailureIsToldAndAReadingTakesTheCardDown() {
        val failed = decide(state(OnePlusWarmupState.Phase.FAILED))
        val ready = decide(state(OnePlusWarmupState.Phase.READY))
        val failedAgain = decide(state(OnePlusWarmupState.Phase.FAILED))

        assertEquals(SourceStrings.dexcom_oneplus_ios_notice_failed, assertIs<DexcomOnePlusIosNotices.Decision.Post>(failed).text)
        assertEquals(DexcomOnePlusIosNotices.Decision.Dismiss, ready)
        // After the card came down, the same message is new again.
        assertIs<DexcomOnePlusIosNotices.Decision.Post>(failedAgain)
    }

    @Test
    fun aResetMakesTheNextMessageNewAgain() {
        decide(state(OnePlusWarmupState.Phase.PAIRING))
        notices.reset()

        assertIs<DexcomOnePlusIosNotices.Decision.Post>(decide(state(OnePlusWarmupState.Phase.PAIRING)))
    }
}
