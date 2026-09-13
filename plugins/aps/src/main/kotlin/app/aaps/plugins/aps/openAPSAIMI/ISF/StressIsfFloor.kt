package app.aaps.plugins.aps.openAPSAIMI.ISF

import java.util.Locale

/**
 * Answers one question: does the stress signature hold right now, and has it held long enough?
 *
 * ## Why this exists
 *
 * The user's profile sensitivity is **120 mg/dL/U from 00:00 to 11:00 and 50 from 11:00 to 00:00**.
 * The 120 is a deliberate safety choice for sleep and for the morning cortisol peak. Measured on the
 * support-package corpus, the dynamic chain commands about **0.56 x that profile on 3858 night ticks
 * out of 3870 (100 %)**. The existing bound [DynamicSensitivityPolicy.PROFILE_RELATIVE_FLOOR] caps the
 * drift at half the profile, it does not undo it.
 *
 * On **15 morning stress episodes** found in the same corpus (heart rate at least 20 bpm over resting,
 * fewer than 100 steps in 15 min, held at least 10 min), the commanded sensitivity sat at **0.50 to
 * 0.68 x profile, median 0.57**. A floor at 1.0 x profile during those episodes would have withheld
 * **3.10 U per episode on the 2 that ended below 80 mg/dL**, against **0.72 U** on the 13 that ended
 * well — a separation ratio of **4.29** (5.23 at the 70 mg/dL threshold). Seven other candidate
 * gestures measured that same week separated between 0.64 and 1.25 and were dropped.
 *
 * ## What this object is, and is not
 *
 * It is pure: it holds no state, reads no clock and touches no preference. The caller keeps
 * [Verdict.signatureSinceMs] and [Verdict.lastEvaluatedMs] and feeds them back on the next tick. It
 * decides nothing about insulin either — it only reports the signature. The dose side of the gesture
 * is a floor multiplier handed to [DynamicSensitivityPolicy.floorAgainstProfile], and raising the
 * commanded sensitivity can only make a dose smaller, never larger.
 *
 * There is **no time window**: the signature is evaluated 24 hours a day.
 */
object StressIsfFloor {

    /**
     * How far the heart rate must sit above the resting rate, in bpm.
     *
     * The 15 measured episodes were selected with this threshold. On the real case of 2026-09-12 the
     * heart rate was 82 against a resting rate of 50, an excess of 32.
     */
    const val HR_ABOVE_RESTING_BPM: Int = 20

    /**
     * Steps in the last 15 minutes that still count as "not walking".
     *
     * This is what separates a cortisol rise from exercise. Above it the heart rate is explained by
     * movement, and movement is already handled by the effort and activity path.
     *
     * The first value was 100, taken from the 15 selected episodes. Measured afterwards on 6950 ticks
     * over 8 days, 100 was too low for an ordinary morning: on morning ticks with the heart rate at
     * least 20 bpm over resting, the median step count is 58 but the 90th centile is 303, so 100
     * covered only **62 %** of them. On 2026-09-13 the signature broke and reformed four times
     * between 08:38 and 09:14 as the count crossed 100 (91, 119, 171, 248) while blood glucose fell
     * to 56, and the floor was active on only 8 of the 420 ticks of that morning.
     *
     * 250 covers **87 %** of those ticks and stays under the 375 that
     * `PhysiologicalTree` already treats as sustained walking, so a real walk is still excluded.
     * Replayed on the same corpus the separation on morning episodes rises from 55.1 to 63.0.
     */
    const val MAX_STEPS_LAST_15M: Int = 250

    /**
     * How long the signature must hold, in minutes, before the floor is allowed to act.
     *
     * A single elevated heart-rate sample is noise — a stair climb, a bad contact, a startle. Ten
     * minutes is two CGM ticks, and it is the hold time used to select the 15 measured episodes.
     */
    const val MIN_HELD_MINUTES: Double = 10.0

    /**
     * Longest gap, in ms, that two evaluations may have between them and still count as continuous.
     *
     * A larger gap means the loop was not running: a restart, a data hole, a phone asleep. Nothing was
     * observed during it, so the hold time cannot be claimed. Two CGM ticks, same as
     * [MIN_HELD_MINUTES], so one missed tick is tolerated and two are not.
     */
    const val MAX_GAP_BETWEEN_EVALUATIONS_MS: Long = 10 * 60 * 1000L

    /**
     * How long, in minutes, the signature may stop holding before an active floor is dropped.
     *
     * Entering and leaving are deliberately not the same test. Entering needs [MIN_HELD_MINUTES] of
     * unbroken signature, because a single elevated sample is noise. Leaving needs this much time
     * out of signature, because a single step burst is noise too: on 2026-09-13 the count crossed
     * 100 for one tick at a time while the heart rate stayed 24 to 44 bpm over resting throughout.
     *
     * Five minutes is one CGM tick. Replayed on the corpus it lifts the floor from 8 to 112 active
     * ticks on the morning of 2026-09-13 and leaves the separation on morning episodes at 63.0. Ten
     * minutes was also measured and gives no further separation, only more exposure.
     */
    const val EXIT_GRACE_MINUTES: Double = 5.0

    /**
     * Floor multiplier the caller passes to [DynamicSensitivityPolicy.floorAgainstProfile] while the
     * signature is active and the key is armed: the commanded sensitivity may not fall under the
     * profile sensitivity of this time of day.
     */
    const val ARMED_FLOOR_MULTIPLIER: Double = 1.0

    /** Heart rate or resting heart rate missing from the export. Never activates anything. */
    const val REASON_NO_HR: String = "no_hr"

    /** The signature does not hold at this instant. */
    const val REASON_NO_SIGNATURE: String = "no_signature"

    /** The signature holds, but a gap between two evaluations reset the hold time. */
    const val REASON_GAP_RESTART: String = "gap_restart"

    /** The signature holds and the hold time is still building up. */
    const val REASON_HOLDING: String = "holding"

    /** The signature holds and has held for at least [MIN_HELD_MINUTES]. */
    const val REASON_ACTIVE: String = "active"

    /**
     * The signature has stopped holding, but the floor stays on for up to [EXIT_GRACE_MINUTES].
     *
     * [Verdict.active] is still true here. Only the code differs, so a support package can tell a
     * floor held through a short break from a floor held by a live signature.
     */
    const val REASON_EXIT_GRACE: String = "exit_grace"

    /**
     * What the signature looks like at one instant.
     *
     * @param active true only when the signature holds **and** has held for at least
     *   [MIN_HELD_MINUTES] without a break.
     * @param heldMinutes how long the signature has held, in minutes. 0.0 whenever it does not hold.
     * @param signatureSinceMs the instant the current unbroken signature started, or null when there
     *   is none. The caller stores it and feeds it back on the next tick.
     * @param lastEvaluatedMs the instant of this evaluation. The caller stores it and feeds it back on
     *   the next tick so a gap can be detected.
     * @param breakStartedMs the instant the signature stopped holding while the floor was still on,
     *   or null when it holds. The caller stores it and feeds it back so the grace time can be
     *   measured across ticks.
     * @param reason a code taken from [REASON_NO_HR], [REASON_NO_SIGNATURE], [REASON_GAP_RESTART],
     *   [REASON_HOLDING], [REASON_ACTIVE] or [REASON_EXIT_GRACE], followed by the live values that
     *   produced it.
     */
    data class Verdict(
        val active: Boolean,
        val heldMinutes: Double,
        val signatureSinceMs: Long?,
        val lastEvaluatedMs: Long,
        val reason: String,
        val breakStartedMs: Long? = null,
    )

    /**
     * Evaluates the stress signature for one tick.
     *
     * Hard rules, in order:
     * 1. A heart rate or a resting heart rate of zero or less is **missing data**, not a calm patient.
     *    In this export an absent heart rate is written as 0. Missing data never activates a gesture,
     *    so the verdict is inactive with [REASON_NO_HR].
     * 2. Getting in and getting out are not the same test. To turn the floor **on**, the signature
     *    must hold without a break for [MIN_HELD_MINUTES]. To turn an already active floor **off**,
     *    the signature must stop holding for [EXIT_GRACE_MINUTES]. In between, the floor stays on
     *    with [REASON_EXIT_GRACE] and the original start instant is kept, so a signature that comes
     *    back does not have to build its hold time again.
     * 3. More than [MAX_GAP_BETWEEN_EVALUATIONS_MS] between two evaluations breaks continuity, because
     *    nothing was observed in between. The hold time restarts from now, and an active floor drops
     *    at once rather than using its grace time.
     *
     * @param hrNowBpm the most recent heart rate, bpm. 0 when unknown.
     * @param rhrRestingBpm the resting heart rate, bpm. 0 when unknown.
     * @param stepsLast15m steps counted in the last 15 minutes.
     * @param nowMs the instant of this evaluation.
     * @param signatureSinceMs the value of [Verdict.signatureSinceMs] returned by the previous call,
     *   or null at start-up.
     * @param lastEvaluatedMs the value of [Verdict.lastEvaluatedMs] returned by the previous call, or
     *   null at start-up. A null means no gap can be measured, so continuity is assumed.
     * @param wasActive the value of [Verdict.active] returned by the previous call. Only an already
     *   active floor may use the grace time; a signature that is still building up never does.
     * @param breakStartedMs the value of [Verdict.breakStartedMs] returned by the previous call.
     */
    fun evaluate(
        hrNowBpm: Int,
        rhrRestingBpm: Int,
        stepsLast15m: Int,
        nowMs: Long,
        signatureSinceMs: Long?,
        lastEvaluatedMs: Long? = null,
        wasActive: Boolean = false,
        breakStartedMs: Long? = null,
    ): Verdict {
        if (hrNowBpm <= 0 || rhrRestingBpm <= 0) {
            return Verdict(
                active = false,
                heldMinutes = 0.0,
                signatureSinceMs = null,
                lastEvaluatedMs = nowMs,
                reason = "$REASON_NO_HR hr=$hrNowBpm rest=$rhrRestingBpm steps15=$stepsLast15m",
                breakStartedMs = null,
            )
        }

        val excessBpm = hrNowBpm - rhrRestingBpm
        val signatureHolds = excessBpm >= HR_ABOVE_RESTING_BPM && stepsLast15m < MAX_STEPS_LAST_15M
        val values = "hr=$hrNowBpm rest=$rhrRestingBpm excess=$excessBpm steps15=$stepsLast15m"

        // A gap, a clock that went backwards, or a first evaluation all mean the hold time restarts.
        val gapMs = lastEvaluatedMs?.let { nowMs - it }
        val continuityBroken = gapMs != null && (gapMs < 0L || gapMs > MAX_GAP_BETWEEN_EVALUATIONS_MS)

        if (!signatureHolds) {
            // An already active floor is given [EXIT_GRACE_MINUTES] before it drops, so one step
            // burst cannot cancel it. A gap still drops it at once: nothing was observed in between.
            val breakStart = breakStartedMs?.takeIf { it <= nowMs } ?: nowMs
            val brokenMinutes = ((nowMs - breakStart) / 60_000.0).coerceAtLeast(0.0)
            val inGrace = wasActive && !continuityBroken && brokenMinutes < EXIT_GRACE_MINUTES
            if (inGrace) {
                val brokenText = String.format(Locale.ROOT, "broken=%.1fmin", brokenMinutes)
                return Verdict(
                    active = true,
                    heldMinutes = 0.0,
                    signatureSinceMs = signatureSinceMs,
                    lastEvaluatedMs = nowMs,
                    reason = "$REASON_EXIT_GRACE $values $brokenText",
                    breakStartedMs = breakStart,
                )
            }
            return Verdict(
                active = false,
                heldMinutes = 0.0,
                signatureSinceMs = null,
                lastEvaluatedMs = nowMs,
                reason = "$REASON_NO_SIGNATURE $values",
                breakStartedMs = null,
            )
        }

        val startedMs = when {
            continuityBroken            -> nowMs
            signatureSinceMs == null    -> nowMs
            signatureSinceMs > nowMs    -> nowMs
            else                        -> signatureSinceMs
        }
        val heldMinutes = ((nowMs - startedMs) / 60_000.0).coerceAtLeast(0.0)
        val active = heldMinutes >= MIN_HELD_MINUTES
        val code = when {
            active            -> REASON_ACTIVE
            continuityBroken  -> REASON_GAP_RESTART
            else              -> REASON_HOLDING
        }
        val heldText = String.format(Locale.ROOT, "held=%.1fmin", heldMinutes)
        return Verdict(
            active = active,
            heldMinutes = heldMinutes,
            signatureSinceMs = startedMs,
            lastEvaluatedMs = nowMs,
            reason = "$code $values $heldText",
            breakStartedMs = null,
        )
    }
}
