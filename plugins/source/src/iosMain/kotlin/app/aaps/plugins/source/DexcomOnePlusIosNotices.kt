package app.aaps.plugins.source

import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState

/**
 * Which warm-up and connection states the person is told about on iPhone, and when.
 *
 * Android shows every state in one ongoing, silent status-bar notification that is updated in
 * place (`DexcomOnePlusWarmupNotification`). iPhone has no such thing: every post through the
 * notification manager is a banner and a card. Copying the Android rule one to one would show a
 * banner at every five minute connect, because each connect passes through CONNECTING. So the same
 * states are mapped to the same messages, but a message is only posted when it changes:
 *
 * - PAIRING: once per pairing. This is where iPhone may show its own pairing dialog, which the
 *   person has to accept.
 * - WARMING: once, and once more when the end time becomes known.
 * - RECONNECTING with a wrong sensor nearby: once.
 * - RECONNECTING with a long silence: once at [SILENCE_NOTICE_MINUTES], then again every
 *   [SILENCE_REPEAT_MINUTES] while it goes on, so the minutes in the text do not get too old.
 * - FAILED: once.
 * - CONNECTING and a plain RECONNECTING change nothing: they are the normal duty cycle, and a
 *   warm-up card that is up stays up through them, as the Android notification keeps its countdown.
 * - READY and IDLE take the card down.
 *
 * Plain logic with no iOS call in it, so it runs in a test.
 */
internal class DexcomOnePlusIosNotices {

    /** What the host should do with the card. */
    sealed interface Decision {

        /**
         * Post (or replace) the card with [text]. Its one placeholder, when it has one, is
         * [minutes] of silence or the time of day of [endsAtMs], which the host formats.
         */
        data class Post(val text: TextRef, val minutes: Int? = null, val endsAtMs: Long? = null) : Decision

        /** Take the card down. */
        data object Dismiss : Decision

        /** Leave the card as it is. */
        data object Keep : Decision
    }

    private enum class Kind { PAIRING, WARMING, WARMING_WITH_END, SILENT, STALE_SENSOR, FAILED }

    private var shown: Kind? = null
    private var shownSilenceMinutes = 0L

    /**
     * @param nowMs wall clock now, used only to turn a protocol `remainingMs` into an end time.
     */
    fun decide(state: OnePlusWarmupState, nowMs: Long): Decision = when (state.phase) {
        OnePlusWarmupState.Phase.IDLE,
        OnePlusWarmupState.Phase.READY        -> {
            reset()
            Decision.Dismiss
        }

        OnePlusWarmupState.Phase.PAIRING      ->
            postOnce(Kind.PAIRING, Decision.Post(SourceStrings.dexcom_oneplus_ios_notice_pairing))

        OnePlusWarmupState.Phase.WARMING      -> {
            val endsAtMs = state.endsAtEpochMs ?: state.remainingMs?.let { nowMs + it }
            when {
                endsAtMs != null          ->
                    postOnce(
                        Kind.WARMING_WITH_END,
                        Decision.Post(SourceStrings.dexcom_oneplus_ios_notice_warming_until, endsAtMs = endsAtMs),
                    )
                // An end time once known is not taken back because one packet came without it.
                shown == Kind.WARMING_WITH_END -> Decision.Keep
                else                      ->
                    postOnce(Kind.WARMING, Decision.Post(SourceStrings.dexcom_oneplus_ios_notice_warming))
            }
        }

        OnePlusWarmupState.Phase.RECONNECTING -> reconnecting(state)

        OnePlusWarmupState.Phase.CONNECTING   -> Decision.Keep

        OnePlusWarmupState.Phase.FAILED       ->
            postOnce(Kind.FAILED, Decision.Post(SourceStrings.dexcom_oneplus_ios_notice_failed))
    }

    /** Forget what was shown, when the host has taken the card down by itself. */
    fun reset() {
        shown = null
        shownSilenceMinutes = 0L
    }

    private fun reconnecting(state: OnePlusWarmupState): Decision {
        if (state.staleMacSuspected) {
            return postOnce(Kind.STALE_SENSOR, Decision.Post(SourceStrings.dexcom_oneplus_ios_notice_stale_sensor))
        }
        val minutes = state.advSilenceMinutes ?: return Decision.Keep
        if (minutes < SILENCE_NOTICE_MINUTES) return Decision.Keep
        val repeatDue = shown == Kind.SILENT && minutes >= shownSilenceMinutes + SILENCE_REPEAT_MINUTES
        if (shown == Kind.SILENT && !repeatDue) return Decision.Keep
        shown = Kind.SILENT
        shownSilenceMinutes = minutes
        return Decision.Post(SourceStrings.dexcom_oneplus_ios_notice_silent, minutes = minutes.toInt())
    }

    private fun postOnce(kind: Kind, post: Decision.Post): Decision {
        if (shown == kind) return Decision.Keep
        shown = kind
        return post
    }

    internal companion object {

        /**
         * Silence before the person is told. Shorter than one missed reading would be noise: the
         * sensor sends every five minutes and a normal reconnect can take a few.
         */
        const val SILENCE_NOTICE_MINUTES = 10L

        /** How much longer the silence has to get before the card is posted again. */
        const val SILENCE_REPEAT_MINUTES = 15L
    }
}
