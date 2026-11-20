package app.aaps.plugins.aps.openAPSAIMI.orchestration

/**
 * Counts loop ticks in a row that ended in a safe hold (an error was caught, no insulin change).
 *
 * A safe hold looks like a working loop from outside: the result is stored and the loop time is
 * refreshed, but no temp basal or SMB is decided, and the running temp basal just continues. In the
 * field report of 2026-11-20 this went on for about 50 minutes during a rise, and nothing told the
 * person. So:
 *
 * - from [noticeThreshold] holds in a row (about 15 min) a silent notice is shown, and posted again
 *   on every further hold, so its count stays current and it comes back if it was swiped away;
 * - at [alarmThreshold] holds in a row (about 30 min) an alarm sounds once. By then a 30-minute temp
 *   basal set before the streak has certainly ended, and the loop has had no control for a whole
 *   temp period: the pump may have run a high temp while glucose fell, or a near-zero one while it
 *   rose.
 *
 * The first normal tick ends the streak, and the caller takes both messages away.
 *
 * Ticks run one at a time while `OApsAIMILoopExclusiveInvocationEnabled` is on (the default). With
 * it off, two overlapping ticks can miscount; that only changes when a message shows, never a dose.
 */
class AimiSafeHoldStreak(
    private val noticeThreshold: Int = DEFAULT_NOTICE_THRESHOLD,
    private val alarmThreshold: Int = DEFAULT_ALARM_THRESHOLD,
) {

    init {
        require(noticeThreshold >= 1) { "noticeThreshold must be at least 1" }
        require(alarmThreshold >= noticeThreshold) { "alarmThreshold must not be below noticeThreshold" }
    }

    /** What the caller must show after a hold. */
    enum class Alert {

        /** Not enough holds in a row yet. */
        NONE,

        /** Post (or post again) the silent notice. */
        NOTICE,

        /** Sound the alarm. Call [markAlarmPosted] once it was really posted. */
        ALARM,
    }

    /** Holds in a row so far. */
    var count: Int = 0
        private set

    private var alarmPosted = false

    /** Records a safe hold and says what to show. */
    fun onHold(): Alert {
        count++
        return when {
            count >= alarmThreshold && !alarmPosted -> Alert.ALARM
            count >= noticeThreshold                -> Alert.NOTICE
            else                                    -> Alert.NONE
        }
    }

    /**
     * The alarm of this streak was posted. Kept apart from [onHold] so that a post that failed is
     * tried again on the next hold instead of being lost for the whole streak.
     */
    fun markAlarmPosted() {
        alarmPosted = true
    }

    /** Records a normal tick. True when a message may be showing and must be taken away. */
    fun onNormalTick(): Boolean {
        val mayShow = count >= noticeThreshold
        count = 0
        alarmPosted = false
        return mayShow
    }

    companion object {

        /** Three ticks, about 15 minutes with a 5-minute CGM. One lost tick alone is not worth a message. */
        const val DEFAULT_NOTICE_THRESHOLD = 3

        /** Six ticks, about 30 minutes: one whole temp basal period without control. */
        const val DEFAULT_ALARM_THRESHOLD = 6

        /** The notice expires by itself after this, so it cannot outlive a loop that stopped ticking. */
        const val NOTICE_VALID_MINUTES = 15
    }
}
