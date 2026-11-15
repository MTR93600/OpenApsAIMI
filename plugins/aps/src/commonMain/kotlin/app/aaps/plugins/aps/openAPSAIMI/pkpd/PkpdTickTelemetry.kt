package app.aaps.plugins.aps.openAPSAIMI.pkpd

import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import kotlin.concurrent.Volatile
import kotlin.math.roundToInt

/**
 * What the PK/PD part of the loop did on the last ticks, kept in memory for the PK/PD status card
 * and the console. It never feeds a dose calculation.
 *
 * Until step P3 these values were preferences that only the loop wrote: the five peak branch values,
 * the dominant branch and the two `PEAK_GOV` log lines. A preference that only the loop writes is
 * not a setting, so they live here now. They are lost when the app restarts; the next tick writes
 * them again. The intelligence snapshot export still carries the governor results of each tick.
 *
 * Same pattern as `IsfSourceTelemetry`: one object for the process, written by the loop, read by
 * the screen.
 */
object PkpdTickTelemetry {

    /**
     * DIA and peak of one tick.
     *
     * @property effectiveDiaHours DIA that dosed on this tick, after the rate limit
     * @property effectivePeakMinutes peak that dosed on this tick, after the rate limit
     * @property profileDiaHours DIA of the insulin in the running profile
     * @property profilePeakMinutes peak of the insulin in the running profile
     * @property peakPrior peak governor: the insulin prior
     * @property peakPhysio peak governor: the physiology offset, minutes
     * @property peakSite peak governor: the cannula site offset, minutes
     * @property peakTrajectory peak governor: the trajectory nudge, minutes
     * @property dominantBranch peak governor: the branch that decided the peak
     * @property atMs time of the tick
     */
    data class Kinetics(
        val effectiveDiaHours: Double,
        val effectivePeakMinutes: Double,
        val profileDiaHours: Double,
        val profilePeakMinutes: Double,
        val peakPrior: Double,
        val peakPhysio: Double,
        val peakSite: Double,
        val peakTrajectory: Double,
        val dominantBranch: String,
        val atMs: Long,
    )

    /** Last tick seen by the plugin, or null before the first tick of this process. */
    @Volatile
    var kinetics: Kinetics? = null
        private set

    /** The learning ticks of the last 24 h (only the learner call records here). */
    val learning: PkpdLearningTickLog = PkpdLearningTickLog()

    /** The last `PEAK_GOV` line and the one already shown in the console. */
    val peakGovConsole: PeakGovConsoleEcho = PeakGovConsoleEcho()

    fun recordKinetics(value: Kinetics) {
        kinetics = value
    }

    /** Kinetics older than this are not shown as "used on the last loop" (three missed 5-minute loops). */
    const val KINETICS_FRESH_MS: Long = 15L * 60L * 1000L

    /** True when a tick recorded at [atMs] is recent enough to show as the last loop run at [nowMs]. */
    fun isFresh(atMs: Long, nowMs: Long): Boolean = nowMs - atMs <= KINETICS_FRESH_MS
}

/**
 * Counts, over the last 24 h, how often the PK/PD learner ran, how many learning steps it accepted
 * and how many ticks did not learn, with the most frequent reason. A tick is blocked when a gate
 * stopped it before the estimator, or when the estimator refused the step (low IOB, carbs, ...).
 *
 * A tick is one loop run, so a 1-minute CGM records about 1440 ticks a day and a 5-minute CGM about
 * 288. The log keeps at most [MAX_ENTRIES] ticks.
 */
class PkpdLearningTickLog(private val windowMs: Long = WINDOW_MS) {

    private class Entry(val atMs: Long, val accepted: Boolean, val blockedBy: String?)

    private val lock = AapsLock()
    private val entries = ArrayDeque<Entry>()

    /**
     * Records one learning tick.
     *
     * @param accepted the estimator accepted a learning step on this tick
     * @param blockedBy why this tick did not learn (a gate or the estimator), or null when a step was accepted
     */
    fun record(atMs: Long, accepted: Boolean, blockedBy: String?) {
        lock.withLock {
            entries.addLast(Entry(atMs, accepted, blockedBy))
            while (entries.size > MAX_ENTRIES) entries.removeFirst()
        }
    }

    fun summary(nowMs: Long): PkpdLearningSummary = lock.withLock {
        val from = nowMs - windowMs
        while (entries.isNotEmpty() && entries.first().atMs < from) entries.removeFirst()
        val inWindow = entries.filter { it.atMs in from..nowMs }
        val blocked = inWindow.mapNotNull { it.blockedBy }
        val topReason = blocked
            .groupingBy { PkpdLearningSummary.reasonCategory(it) }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .firstOrNull()
            ?.key
        PkpdLearningSummary(
            learningTicks = inWindow.size,
            acceptedSteps = inWindow.count { it.accepted },
            blockedTicks = blocked.size,
            topBlockReason = topReason,
        )
    }

    fun clear() {
        lock.withLock { entries.clear() }
    }

    companion object {

        const val WINDOW_MS: Long = 24L * 60L * 60L * 1000L

        /** About two days of a 1-minute CGM, so the 24 h window is never cut short. */
        const val MAX_ENTRIES = 3000
    }
}

/**
 * Learning over the last 24 h.
 *
 * @property learningTicks ticks on which the learner was called
 * @property acceptedSteps ticks on which the estimator accepted a learning step
 * @property blockedTicks ticks that did not learn because a gate or the estimator refused them
 * @property topBlockReason the most frequent block reason, as [reasonCategory] gives it, or null
 */
data class PkpdLearningSummary(
    val learningTicks: Int,
    val acceptedSteps: Int,
    val blockedTicks: Int,
    val topBlockReason: String?,
) {

    /** Blocked ticks in percent of the learning ticks, rounded; 0 with no tick. */
    val blockedPercent: Int
        get() = if (learningTicks == 0) 0 else (blockedTicks * 100.0 / learningTicks).roundToInt()

    companion object {

        /**
         * `causal_unclean` and `estimator_iob_too_low` stay as they are; `curve:recent_rise` and
         * `causal_modulator:x` keep the part before the colon.
         */
        fun reasonCategory(blockedBy: String): String = blockedBy.substringBefore(':')
    }
}

/**
 * Shows each new `PEAK_GOV` line once in the APS console.
 *
 * The plugin records the line of every tick; the early PK/PD step of `determine_basal` asks for the
 * line to show. A line that was shown already is not shown again until the governor goes quiet
 * (a blank line) or writes another line.
 */
class PeakGovConsoleEcho {

    @Volatile
    private var line: String = ""

    @Volatile
    private var echoed: String = ""

    fun record(logLine: String?) {
        line = logLine.orEmpty()
        if (line.isBlank()) echoed = ""
    }

    /** The line to show now, or null when there is none or it was shown already. */
    fun lineToEcho(): String? {
        val current = line
        if (current.isBlank() || current == echoed) return null
        echoed = current
        return current
    }
}
