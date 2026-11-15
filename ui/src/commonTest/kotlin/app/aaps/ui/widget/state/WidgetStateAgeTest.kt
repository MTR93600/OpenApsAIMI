package app.aaps.ui.widget.state

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.RM
import app.aaps.core.interfaces.overview.IsfStep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The age rule. A Live Activity or an iOS widget shows the state long after it was built, so the
 * age must be judged again at the time of showing, with the same rule the app uses (9 minutes).
 */
class WidgetStateAgeTest {

    private val now = 1_800_000_000_000L
    private val min = 60_000L

    @Test
    fun readingYoungerThanNineMinutesIsFresh() {
        assertFalse(WidgetStateRules.isStale(now - 2 * min, now))
        assertFalse(WidgetStateRules.isStale(now - 9 * min + 1, now))
    }

    @Test
    fun readingOfNineMinutesOrOlderIsStale() {
        // Same edge as LastBgData.isActualBg: fresh only while strictly younger than 9 min.
        assertTrue(WidgetStateRules.isStale(now - 9 * min, now))
        assertTrue(WidgetStateRules.isStale(now - 25 * min, now))
    }

    @Test
    fun noReadingIsStale() {
        assertTrue(WidgetStateRules.isStale(null, now))
    }

    @Test
    fun staleAfterIsNineMinutes() {
        assertEquals(9 * min, WidgetStateRules.STALE_AFTER_MS)
    }

    @Test
    fun freshStateTurnsStaleWhenShownLater() {
        val state = state(bgTimestamp = now - 2 * min, isStale = false, range = WidgetBgRange.HIGH)
        assertEquals(WidgetBgColorClass.HIGH, state.bgColorClass(now))
        // The app froze. Nobody built a new state, but the old one is shown 10 minutes later.
        assertEquals(WidgetBgColorClass.STALE, state.bgColorClass(now + 10 * min))
        assertTrue(state.isStaleAt(now + 10 * min))
        assertFalse(state.isStaleAt(now))
    }

    @Test
    fun stateMarkedStaleAtBuildStaysStale() {
        // Built stale stays stale, even if the clock of the reader is behind.
        val state = state(bgTimestamp = now - 2 * min, isStale = true, range = WidgetBgRange.IN_RANGE)
        assertEquals(WidgetBgColorClass.STALE, state.bgColorClass(now - 5 * min))
        assertTrue(state.isStaleAt(now - 5 * min))
    }

    @Test
    fun staleAtIsReadingTimePlusNineMinutes() {
        assertEquals(now - 2 * min + 9 * min, state(bgTimestamp = now - 2 * min, isStale = false).staleAt)
        assertNull(state(bgTimestamp = null, bgMgdl = null, isStale = true).staleAt)
    }

    @Test
    fun stateWithoutReadingIsAlwaysStale() {
        val state = state(bgTimestamp = null, bgMgdl = null, isStale = true)
        assertEquals(WidgetBgColorClass.STALE, state.bgColorClass(now))
    }

    private fun state(
        bgTimestamp: Long?,
        isStale: Boolean,
        range: WidgetBgRange = WidgetBgRange.IN_RANGE,
        bgMgdl: Double? = 120.0
    ) = WidgetState(
        bgText = "120",
        bgMgdl = bgMgdl,
        bgTimestamp = bgTimestamp,
        units = GlucoseUnit.MGDL,
        bgRange = range,
        isStale = isStale,
        trendArrow = null,
        arrowDescription = null,
        bgSpoken = "120",
        deltaText = "",
        deltaMgdl = null,
        timeAgoText = "",
        iobText = "",
        iob = 0.0,
        iobActive = false,
        iobLabel = "",
        cobText = "",
        cob = null,
        cobActive = false,
        cobLabel = "",
        carbsRequired = false,
        carbsRequiredGrams = 0,
        tempTargetText = "",
        targetChip = WidgetTargetChip.NONE,
        tempTargetReason = null,
        targetIcon = WidgetTargetIcon.PROFILE_TARGET,
        profileText = "",
        profileModified = false,
        runningMode = RM.Mode.CLOSED_LOOP,
        runningModeText = "",
        loopIcon = WidgetLoopIcon.CLOSED,
        runningModeActive = false,
        lastLoopTime = null,
        sensitivityIcon = WidgetSensitivityIcon.EQUAL,
        sensitivityText = "",
        isfFromText = "",
        isfToText = "",
        isfStep = IsfStep.SAME,
        isfStepDescription = null,
        tbrState = WidgetTbrState.NONE,
        tbrDescription = "",
        chartPoints = emptyList()
    )
}
