package app.aaps.ui.widget.state

import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.data.time.T
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ui.UiStrings
import kotlin.math.abs

/**
 * The decisions behind [WidgetState], as plain functions so they can be tested on every platform.
 * [WidgetStateBuilder] gathers the inputs and calls these.
 */
object WidgetStateRules {

    /** How far back the small chart goes. */
    const val CHART_WINDOW_MS: Long = 3L * 60 * 60 * 1000

    /** At most this many chart points, so the state stays small (a Live Activity allows about 4 KB). */
    const val CHART_MAX_POINTS: Int = 36

    /**
     * A reading is too old to trust from this age on. The same 9 minutes as the overview and
     * `LastBgData.isActualBg`, from [Constants.OLD_BG_MINUTES], so every surface agrees.
     */
    val STALE_AFTER_MS: Long = T.mins(Constants.OLD_BG_MINUTES).msecs()

    /**
     * True when the reading taken at [bgTimestamp] is too old at [now], or when there is no reading.
     * Fresh only while strictly younger than [STALE_AFTER_MS], like `LastBgData.isActualBg`.
     */
    fun isStale(bgTimestamp: Long?, now: Long): Boolean =
        bgTimestamp == null || bgTimestamp <= now - STALE_AFTER_MS

    fun bgRange(isLow: Boolean, isHigh: Boolean): WidgetBgRange = when {
        isLow  -> WidgetBgRange.LOW
        isHigh -> WidgetBgRange.HIGH
        else   -> WidgetBgRange.IN_RANGE
    }

    /** The age wins over the range: an old reading, or none, is never painted as a fresh one. */
    fun bgColorClass(range: WidgetBgRange, isStale: Boolean, hasReading: Boolean): WidgetBgColorClass = when {
        isStale || !hasReading   -> WidgetBgColorClass.STALE
        range == WidgetBgRange.LOW  -> WidgetBgColorClass.LOW
        range == WidgetBgRange.HIGH -> WidgetBgColorClass.HIGH
        else                     -> WidgetBgColorClass.IN_RANGE
    }

    /**
     * With no temp target the chip shows the profile target. That is not a manual target, so it gets
     * the plain target, not the one with the pencil (#5340).
     */
    fun targetIcon(tempTarget: TT?): WidgetTargetIcon = when {
        tempTarget == null                          -> WidgetTargetIcon.PROFILE_TARGET
        tempTarget.reason == TT.Reason.EATING_SOON  -> WidgetTargetIcon.EATING_SOON
        tempTarget.reason == TT.Reason.ACTIVITY     -> WidgetTargetIcon.ACTIVITY
        tempTarget.reason == TT.Reason.HYPOGLYCEMIA -> WidgetTargetIcon.HYPOGLYCEMIA
        else                                        -> WidgetTargetIcon.MANUAL
    }

    fun sensitivityIcon(isEnabled: Boolean, ratio: Double): WidgetSensitivityIcon =
        if (isEnabled)
            when {
                ratio > 1.0 -> WidgetSensitivityIcon.ABOVE
                ratio < 1.0 -> WidgetSensitivityIcon.BELOW
                else        -> WidgetSensitivityIcon.EQUAL
            }
        else
            when {
                ratio > 1.0 -> WidgetSensitivityIcon.OFF_ABOVE
                ratio < 1.0 -> WidgetSensitivityIcon.OFF_BELOW
                else        -> WidgetSensitivityIcon.OFF_EQUAL
            }

    /** Same three states as the overview chip. A temp basal equal to the profile basal is none. */
    fun tbrState(hasProfile: Boolean, isTempBasalRunning: Boolean, tempBasalAbsolute: Double, basal: Double): WidgetTbrState = when {
        !hasProfile                             -> WidgetTbrState.NONE
        !isTempBasalRunning                     -> WidgetTbrState.NONE
        abs(tempBasalAbsolute - basal) < 0.01   -> WidgetTbrState.NONE
        tempBasalAbsolute > basal               -> WidgetTbrState.ABOVE_PROFILE
        else                                    -> WidgetTbrState.BELOW_PROFILE
    }

    fun tbrDescription(state: WidgetTbrState): TextRef = when (state) {
        WidgetTbrState.NONE          -> CoreUiStrings.tbr_state_none
        WidgetTbrState.ABOVE_PROFILE -> CoreUiStrings.tbr_state_above_profile
        WidgetTbrState.BELOW_PROFILE -> CoreUiStrings.tbr_state_below_profile
    }

    fun runningModeText(mode: RM.Mode): TextRef = when (mode) {
        RM.Mode.OPEN_LOOP         -> CoreUiStrings.openloop
        RM.Mode.CLOSED_LOOP       -> CoreUiStrings.closedloop
        RM.Mode.CLOSED_LOOP_LGS   -> UiStrings.widget_rm_lgs
        RM.Mode.DISABLED_LOOP     -> UiStrings.widget_rm_disabled
        RM.Mode.SUPER_BOLUS       -> CoreUiStrings.superbolus
        RM.Mode.DISCONNECTED_PUMP -> CoreUiStrings.disconnected
        RM.Mode.SUSPENDED_BY_PUMP,
        RM.Mode.SUSPENDED_BY_USER,
        RM.Mode.SUSPENDED_BY_DST  -> UiStrings.widget_rm_suspended

        RM.Mode.RESUME            -> UiStrings.widget_rm_resume
    }

    fun loopIcon(mode: RM.Mode): WidgetLoopIcon = when (mode) {
        RM.Mode.OPEN_LOOP         -> WidgetLoopIcon.OPEN
        RM.Mode.CLOSED_LOOP,
        RM.Mode.RESUME            -> WidgetLoopIcon.CLOSED

        RM.Mode.CLOSED_LOOP_LGS   -> WidgetLoopIcon.LGS
        RM.Mode.DISABLED_LOOP     -> WidgetLoopIcon.DISABLED
        RM.Mode.SUPER_BOLUS       -> WidgetLoopIcon.SUPER_BOLUS
        RM.Mode.DISCONNECTED_PUMP -> WidgetLoopIcon.DISCONNECTED
        RM.Mode.SUSPENDED_BY_PUMP,
        RM.Mode.SUSPENDED_BY_USER,
        RM.Mode.SUSPENDED_BY_DST  -> WidgetLoopIcon.PAUSED
    }

    fun runningModeActive(mode: RM.Mode): Boolean = mode != RM.Mode.CLOSED_LOOP

    /**
     * The loop asks for carbs only while it ran after the newest carbs entry: carbs eaten since then
     * may already answer the request.
     */
    fun carbsRequired(carbsReq: Int, newestCarbsTime: Long, lastApsRun: Long): Boolean =
        carbsReq > 0 && newestCarbsTime < lastApsRun

    /**
     * Readings for the small chart, oldest first.
     *
     * Only real readings: a value made up to fill a gap is left out, so the chart never shows data
     * that was not measured. Values that are not finite or not above 0 are left out too. Only the
     * last [CHART_WINDOW_MS] before [now] count, and at most [CHART_MAX_POINTS] of the newest ones.
     */
    fun chartPoints(bucketed: List<InMemoryGlucoseValue>?, now: Long): List<WidgetChartPoint> {
        if (bucketed.isNullOrEmpty()) return emptyList()
        val from = now - CHART_WINDOW_MS
        return bucketed.asSequence()
            .filter { !it.filledGap && it.timestamp in from..now }
            .map { WidgetChartPoint(it.timestamp, it.recalculated) }
            .filter { it.mgdl.isFinite() && it.mgdl > 0.0 }
            .sortedBy { it.timestamp }
            .toList()
            .takeLast(CHART_MAX_POINTS)
    }
}
