package app.aaps.ui.compose.dashboard.aimi

import app.aaps.core.interfaces.aps.AimiAdaptationModuleId
import app.aaps.core.interfaces.aps.AimiAdaptationModuleStatus
import app.aaps.core.interfaces.aps.AimiAdaptationPhase
import app.aaps.core.interfaces.aps.AimiAdaptationReasonCode
import app.aaps.core.interfaces.aps.AimiAdaptationStatus
import app.aaps.core.ui.compose.formatKmp
import app.aaps.ui.UiStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AimiDashboardRulesTest {

    @Test
    fun graphRangeKeepsDevValuesAndCyclesOnDoubleTap() {
        assertEquals(listOf(6, 9, 12, 18, 24), listOf(6, 9, 12, 18, 24).map { AimiDashboardGraphRange.clamp(it) })
        // A stored value that is not a range gives 6 h, as DEV syncGraphRange did.
        assertEquals(6, AimiDashboardGraphRange.clamp(7))
        assertEquals(6, AimiDashboardGraphRange.clamp(0))
        assertEquals(9, AimiDashboardGraphRange.next(6))
        assertEquals(12, AimiDashboardGraphRange.next(9))
        assertEquals(24, AimiDashboardGraphRange.next(18))
        assertEquals(6, AimiDashboardGraphRange.next(24))
    }

    @Test
    fun noseAngleFollowsTheDevSteps() {
        assertEquals(0f, AimiDashboardHeroRules.noseAngle(null))
        assertEquals(0f, AimiDashboardHeroRules.noseAngle(1.5))
        assertEquals(10f, AimiDashboardHeroRules.noseAngle(3.0))
        assertEquals(20f, AimiDashboardHeroRules.noseAngle(6.0))
        assertEquals(45f, AimiDashboardHeroRules.noseAngle(11.0))
        assertEquals(-10f, AimiDashboardHeroRules.noseAngle(-3.0))
        assertEquals(-20f, AimiDashboardHeroRules.noseAngle(-6.0))
        assertEquals(-45f, AimiDashboardHeroRules.noseAngle(-11.0))
    }

    @Test
    fun timeInRangeUsesTheFiveDevBands() {
        assertNull(AimiDashboardRules.tir(emptyList()))
        val tir = AimiDashboardRules.tir(listOf(50.0, 60.0, 100.0, 200.0, 300.0))!!
        assertEquals(20.0, tir.veryLow)
        assertEquals(20.0, tir.low)
        assertEquals(20.0, tir.target)
        assertEquals(20.0, tir.high)
        assertEquals(20.0, tir.veryHigh)
        assertEquals(142.0, tir.averageMgdl)
        assertEquals((142.0 + 46.7) / 28.7, tir.a1c)
    }

    @Test
    fun bandLimitsAreInRange() {
        val tir = AimiDashboardRules.tir(listOf(70.0, 180.0))!!
        assertEquals(100.0, tir.target)
    }

    @Test
    fun safetyLevelHasTheDevLimits() {
        assertEquals(AimiDashboardRules.SafetyLevel.CRITICAL, AimiDashboardRules.safetyLevel(69.0))
        assertEquals(AimiDashboardRules.SafetyLevel.LIMITED, AimiDashboardRules.safetyLevel(70.0))
        assertEquals(AimiDashboardRules.SafetyLevel.LIMITED, AimiDashboardRules.safetyLevel(89.0))
        assertEquals(AimiDashboardRules.SafetyLevel.NORMAL, AimiDashboardRules.safetyLevel(90.0))
    }

    @Test
    fun predictionHorizonIsAtLeastTwoHours() {
        val now = 1_000_000_000L
        assertEquals(120L, AimiDashboardRules.predictionHorizonMinutes(now + 30 * 60_000L, now))
        assertEquals(180L, AimiDashboardRules.predictionHorizonMinutes(now + 180 * 60_000L, now))
        // A last point that is not in the future gives the 4 h AIMI horizon.
        assertEquals(240L, AimiDashboardRules.predictionHorizonMinutes(now - 1, now))
    }

    @Test
    fun reasonBecomesOnePlainLine() {
        assertEquals("BG 120 SMB 0.3", AimiDashboardRules.plainText("<b>BG</b>&nbsp;120<br/>\n SMB   0.3"))
        assertEquals("abcd…", AimiDashboardRules.clip("abcdefgh", maxLength = 5))
        assertEquals("abc", AimiDashboardRules.clip("abc", maxLength = 5))
    }

    @Test
    fun modeNotesMatchTheDevKeywords() {
        assertEquals(UiStrings.dashboard_mode_lunch, AimiDashboardRules.modeForNote("Lunch with friends"))
        assertEquals(UiStrings.dashboard_mode_breakfast, AimiDashboardRules.modeForNote("bfast"))
        assertNull(AimiDashboardRules.modeForNote("site change"))
        assertNull(AimiDashboardRules.modeForNote(null))
    }

    @Test
    fun knownTrajectoryTypesHaveALabel() {
        listOf("OPEN_DIVERGING", "SLOW_DRIFT", "CLOSING_CONVERGING", "TIGHT_SPIRAL", "STABLE_ORBIT", "HOVERING", "UNCERTAIN")
            .forEach { assertTrue(AimiDashboardRules.trajectoryLabel(it) != null, it) }
        assertNull(AimiDashboardRules.trajectoryLabel("SOMETHING_NEW"))
        assertNull(AimiDashboardRules.trajectoryLabel(null))
    }

    @Test
    fun adaptationStatusMarksOldLearnersStaleAndSortsAttentionFirst() {
        val now = 10_000_000L
        val status = AimiAdaptationStatus(
            updatedAt = now - 60_000L,
            modules = listOf(
                AimiAdaptationModuleStatus(AimiAdaptationModuleId.PKPD, AimiAdaptationPhase.READY, AimiAdaptationReasonCode.READY, updatedAt = now - 60_000L),
                AimiAdaptationModuleStatus(AimiAdaptationModuleId.WCYCLE, AimiAdaptationPhase.ACTIVE, AimiAdaptationReasonCode.ACTIVE, updatedAt = now - 3_600_000L),
                AimiAdaptationModuleStatus(AimiAdaptationModuleId.DIA_GOVERNOR, AimiAdaptationPhase.DISABLED, AimiAdaptationReasonCode.FEATURE_DISABLED, updatedAt = now - 3_600_000L),
            ),
            activeCount = 1, readyCount = 1, waitingCount = 0, learningCount = 0, blockedCount = 0, staleCount = 0, disabledCount = 1,
        )

        val state = AimiAdaptationStatusPresenter.present(status, now, staleAfterMs = 15 * 60_000L)

        assertTrue(state.hasStatus)
        assertEquals(60_000L, state.snapshotAgeMillis)
        // The active learner is an hour old: stale, and first in the list.
        assertEquals(AimiAdaptationModuleId.WCYCLE, state.modules.first().status.moduleId)
        assertEquals(AimiAdaptationPhase.STALE, state.modules.first().status.phase)
        assertEquals(AimiAdaptationReasonCode.DATA_STALE, state.modules.first().status.reason)
        // A disabled learner never becomes stale.
        assertEquals(AimiAdaptationPhase.DISABLED, state.modules.last().status.phase)
        assertEquals(1, state.staleCount)
        assertEquals(1, state.readyCount)
        assertEquals(0, state.activeCount)
        assertEquals(1, state.attentionCount)
    }

    @Test
    fun noAdaptationStatusGivesAnEmptyState() {
        assertFalse(AimiAdaptationStatusPresenter.present(null, 0L, 0L).hasStatus)
    }

    @Test
    fun formatKmpFillsPositionalAndSequentialPlaceholders() {
        assertEquals("3× Pump low", "%1\$d× %2\$s".formatKmp(3, "Pump low"))
        assertEquals("b a", "%2\$s %1\$s".formatKmp("a", "b"))
        assertEquals("x 5", "%s %d".formatKmp("x", 5))
        assertEquals("80%", "%1\$s%%".formatKmp("80"))
        // A placeholder with no argument is kept.
        assertEquals("Ends %1\$s", "Ends %1\$s".formatKmp())
    }
}
