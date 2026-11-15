package app.aaps.ui.widget.state

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.TT
import app.aaps.core.ui.CoreUiStrings
import app.aaps.ui.UiStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WidgetStateRulesTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L

    @Test
    fun bgRangeFollowsTheMarks() {
        assertEquals(WidgetBgRange.LOW, WidgetStateRules.bgRange(isLow = true, isHigh = false))
        assertEquals(WidgetBgRange.HIGH, WidgetStateRules.bgRange(isLow = false, isHigh = true))
        assertEquals(WidgetBgRange.IN_RANGE, WidgetStateRules.bgRange(isLow = false, isHigh = false))
    }

    @Test
    fun lowWinsWhenBothMarksSayYes() {
        assertEquals(WidgetBgRange.LOW, WidgetStateRules.bgRange(isLow = true, isHigh = true))
    }

    @Test
    fun freshReadingKeepsItsRangeColour() {
        assertEquals(WidgetBgColorClass.LOW, WidgetStateRules.bgColorClass(WidgetBgRange.LOW, isStale = false, hasReading = true))
        assertEquals(WidgetBgColorClass.IN_RANGE, WidgetStateRules.bgColorClass(WidgetBgRange.IN_RANGE, isStale = false, hasReading = true))
        assertEquals(WidgetBgColorClass.HIGH, WidgetStateRules.bgColorClass(WidgetBgRange.HIGH, isStale = false, hasReading = true))
    }

    @Test
    fun staleReadingNeverLooksFresh() {
        for (range in WidgetBgRange.entries) {
            assertEquals(WidgetBgColorClass.STALE, WidgetStateRules.bgColorClass(range, isStale = true, hasReading = true))
        }
    }

    @Test
    fun missingReadingIsStaleEvenIfNotMarkedStale() {
        for (range in WidgetBgRange.entries) {
            assertEquals(WidgetBgColorClass.STALE, WidgetStateRules.bgColorClass(range, isStale = false, hasReading = false))
        }
    }

    @Test
    fun targetIconFollowsTheReason() {
        assertEquals(WidgetTargetIcon.PROFILE_TARGET, WidgetStateRules.targetIcon(null))
        assertEquals(WidgetTargetIcon.EATING_SOON, WidgetStateRules.targetIcon(tt(TT.Reason.EATING_SOON)))
        assertEquals(WidgetTargetIcon.ACTIVITY, WidgetStateRules.targetIcon(tt(TT.Reason.ACTIVITY)))
        assertEquals(WidgetTargetIcon.HYPOGLYCEMIA, WidgetStateRules.targetIcon(tt(TT.Reason.HYPOGLYCEMIA)))
        assertEquals(WidgetTargetIcon.MANUAL, WidgetStateRules.targetIcon(tt(TT.Reason.CUSTOM)))
        assertEquals(WidgetTargetIcon.MANUAL, WidgetStateRules.targetIcon(tt(TT.Reason.AUTOMATION)))
        assertEquals(WidgetTargetIcon.MANUAL, WidgetStateRules.targetIcon(tt(TT.Reason.WEAR)))
    }

    @Test
    fun sensitivityIconShowsDirectionAndOnOff() {
        assertEquals(WidgetSensitivityIcon.ABOVE, WidgetStateRules.sensitivityIcon(true, 1.2))
        assertEquals(WidgetSensitivityIcon.BELOW, WidgetStateRules.sensitivityIcon(true, 0.8))
        assertEquals(WidgetSensitivityIcon.EQUAL, WidgetStateRules.sensitivityIcon(true, 1.0))
        assertEquals(WidgetSensitivityIcon.OFF_ABOVE, WidgetStateRules.sensitivityIcon(false, 1.2))
        assertEquals(WidgetSensitivityIcon.OFF_BELOW, WidgetStateRules.sensitivityIcon(false, 0.8))
        assertEquals(WidgetSensitivityIcon.OFF_EQUAL, WidgetStateRules.sensitivityIcon(false, 1.0))
    }

    @Test
    fun tbrStateComparesWithProfileBasal() {
        assertEquals(WidgetTbrState.NONE, WidgetStateRules.tbrState(hasProfile = false, isTempBasalRunning = true, tempBasalAbsolute = 2.0, basal = 1.0))
        assertEquals(WidgetTbrState.NONE, WidgetStateRules.tbrState(hasProfile = true, isTempBasalRunning = false, tempBasalAbsolute = 2.0, basal = 1.0))
        assertEquals(WidgetTbrState.NONE, WidgetStateRules.tbrState(hasProfile = true, isTempBasalRunning = true, tempBasalAbsolute = 1.005, basal = 1.0))
        assertEquals(WidgetTbrState.ABOVE_PROFILE, WidgetStateRules.tbrState(hasProfile = true, isTempBasalRunning = true, tempBasalAbsolute = 1.5, basal = 1.0))
        assertEquals(WidgetTbrState.BELOW_PROFILE, WidgetStateRules.tbrState(hasProfile = true, isTempBasalRunning = true, tempBasalAbsolute = 0.0, basal = 1.0))
    }

    @Test
    fun tbrDescriptionMatchesState() {
        assertEquals(CoreUiStrings.tbr_state_none, WidgetStateRules.tbrDescription(WidgetTbrState.NONE))
        assertEquals(CoreUiStrings.tbr_state_above_profile, WidgetStateRules.tbrDescription(WidgetTbrState.ABOVE_PROFILE))
        assertEquals(CoreUiStrings.tbr_state_below_profile, WidgetStateRules.tbrDescription(WidgetTbrState.BELOW_PROFILE))
    }

    @Test
    fun everyRunningModeHasTextAndIcon() {
        assertEquals(WidgetLoopIcon.CLOSED, WidgetStateRules.loopIcon(RM.Mode.CLOSED_LOOP))
        assertEquals(WidgetLoopIcon.CLOSED, WidgetStateRules.loopIcon(RM.Mode.RESUME))
        assertEquals(WidgetLoopIcon.OPEN, WidgetStateRules.loopIcon(RM.Mode.OPEN_LOOP))
        assertEquals(WidgetLoopIcon.LGS, WidgetStateRules.loopIcon(RM.Mode.CLOSED_LOOP_LGS))
        assertEquals(WidgetLoopIcon.DISABLED, WidgetStateRules.loopIcon(RM.Mode.DISABLED_LOOP))
        assertEquals(WidgetLoopIcon.SUPER_BOLUS, WidgetStateRules.loopIcon(RM.Mode.SUPER_BOLUS))
        assertEquals(WidgetLoopIcon.DISCONNECTED, WidgetStateRules.loopIcon(RM.Mode.DISCONNECTED_PUMP))
        assertEquals(WidgetLoopIcon.PAUSED, WidgetStateRules.loopIcon(RM.Mode.SUSPENDED_BY_PUMP))
        assertEquals(WidgetLoopIcon.PAUSED, WidgetStateRules.loopIcon(RM.Mode.SUSPENDED_BY_USER))
        assertEquals(WidgetLoopIcon.PAUSED, WidgetStateRules.loopIcon(RM.Mode.SUSPENDED_BY_DST))

        assertEquals(CoreUiStrings.closedloop, WidgetStateRules.runningModeText(RM.Mode.CLOSED_LOOP))
        assertEquals(UiStrings.widget_rm_lgs, WidgetStateRules.runningModeText(RM.Mode.CLOSED_LOOP_LGS))
        assertEquals(UiStrings.widget_rm_suspended, WidgetStateRules.runningModeText(RM.Mode.SUSPENDED_BY_DST))
        assertEquals(UiStrings.widget_rm_resume, WidgetStateRules.runningModeText(RM.Mode.RESUME))
    }

    @Test
    fun onlyClosedLoopNeedsNoAttention() {
        for (mode in RM.Mode.entries) {
            assertEquals(mode != RM.Mode.CLOSED_LOOP, WidgetStateRules.runningModeActive(mode), mode.name)
        }
    }

    @Test
    fun carbsRequiredOnlyAfterTheNewestCarbs() {
        assertTrue(WidgetStateRules.carbsRequired(carbsReq = 15, newestCarbsTime = now - 30 * min, lastApsRun = now))
        assertFalse(WidgetStateRules.carbsRequired(carbsReq = 15, newestCarbsTime = now, lastApsRun = now - min))
        assertFalse(WidgetStateRules.carbsRequired(carbsReq = 0, newestCarbsTime = 0L, lastApsRun = now))
    }

    @Test
    fun chartIsEmptyWithoutData() {
        assertEquals(emptyList(), WidgetStateRules.chartPoints(null, now))
        assertEquals(emptyList(), WidgetStateRules.chartPoints(emptyList(), now))
    }

    @Test
    fun chartKeepsOnlyRealRecentReadingsOldestFirst() {
        val data = listOf(
            // newest first, as the bucketed table holds them
            gv(now, 120.0),
            gv(now - 5 * min, 118.0, filledGap = true),
            gv(now - 10 * min, Double.NaN),
            gv(now - 15 * min, 0.0),
            gv(now - 20 * min, 110.0),
            gv(now - WidgetStateRules.CHART_WINDOW_MS - min, 100.0),
            gv(now + 5 * min, 130.0)
        )
        assertEquals(
            listOf(WidgetChartPoint(now - 20 * min, 110.0), WidgetChartPoint(now, 120.0)),
            WidgetStateRules.chartPoints(data, now)
        )
    }

    @Test
    fun chartUsesTheCorrectedValueInMgdl() {
        val smoothed = InMemoryGlucoseValue(timestamp = now, value = 100.0, smoothed = 104.0)
        assertEquals(listOf(WidgetChartPoint(now, 104.0)), WidgetStateRules.chartPoints(listOf(smoothed), now))
    }

    @Test
    fun chartIsCappedToTheNewestPoints() {
        // One-minute readings over the whole window: far more than the cap.
        val data = (0 until 180).map { gv(now - it * min, 100.0 + it) }
        val points = WidgetStateRules.chartPoints(data, now)
        assertEquals(WidgetStateRules.CHART_MAX_POINTS, points.size)
        assertEquals(now, points.last().timestamp)
        assertEquals(points.sortedBy { it.timestamp }, points)
    }

    private fun gv(timestamp: Long, value: Double, filledGap: Boolean = false) =
        InMemoryGlucoseValue(timestamp = timestamp, value = value, filledGap = filledGap)

    private fun tt(reason: TT.Reason) =
        TT(timestamp = now, reason = reason, lowTarget = 80.0, highTarget = 80.0, duration = 60 * min)
}
