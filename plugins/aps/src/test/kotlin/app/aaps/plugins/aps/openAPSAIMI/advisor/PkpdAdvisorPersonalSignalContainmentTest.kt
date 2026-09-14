package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefAnalysisReport
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefDataSufficiency
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefGlycemicPriority
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefMlStatus
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefPersonalMlStatus
import app.aaps.plugins.aps.openAPSAIMI.advisor.oref.OrefPersonalSignalGate
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Containment tests for the personal on-device signal.
 *
 * The personal head reports an uncalibrated score that can never fall below 50 % and sits above 52 % for almost
 * every patient (see `OrefPersonalMlTrainer`). It used to be an `||` branch in three advisor gates, so it could
 * open them alone. Each test below puts the advisor in the exact band where only that third branch could decide,
 * pushes the personal signal to its maximum, and checks that nothing comes out — then repeats the same scenario
 * with a calibrated input to show the gate itself is still alive.
 */
class PkpdAdvisorPersonalSignalContainmentTest {

    private val rh: ResourceHelper = mockk(relaxed = true)
    private val advisor = PkpdAdvisor()

    private fun metrics(
        timeBelow70: Double,
        timeAbove180: Double,
        todayTir: Double? = null,
    ) = AdvisorMetrics(
        periodLabel = "7d",
        tir70_180 = 0.60,
        tir70_140 = 0.45,
        timeBelow70 = timeBelow70,
        timeBelow54 = 0.0,
        timeAbove180 = timeAbove180,
        timeAbove250 = 0.05,
        meanBg = 160.0,
        variabilityCv = 0.30,
        gmi = 7.0,
        tdd = 40.0,
        basalPercent = 0.45,
        hypoEvents = 3,
        severeHypoEvents = 0,
        hyperEvents = 5,
        todayTir = todayTir,
        todayTdd = null,
        dataSufficiency = AdvisorDataSufficiency.GOOD,
    )

    private fun pkpd() = PkpdPrefsSnapshot(
        pkpdEnabled = true,
        initialDiaH = 7.0,
        initialPeakMin = 60.0,
        boundsDiaMinH = 5.0,
        boundsDiaMaxH = 9.0,
        boundsPeakMinMin = 45.0,
        boundsPeakMinMax = 75.0,
        maxDiaChangePerDayH = 0.5,
        maxPeakChangePerDayMin = 5.0,
        isfFusionMinFactor = 0.7,
        isfFusionMaxFactor = 1.5,
        isfFusionMaxChangePerTick = 0.05,
        smbTailThreshold = 0.6,
        smbTailDamping = 0.85,
        smbExerciseDamping = 0.8,
        smbLateFatDamping = 0.8,
    )

    /**
     * @param actualHypoPct measured 4h hypo exposure — a calibrated input
     * @param actualHyperPct measured 4h hyper exposure — a calibrated input
     * @param personalPct the uncalibrated personal score, written into both heads
     */
    private fun oref(
        priority: OrefGlycemicPriority,
        actualHypoPct: Double = 0.0,
        actualHyperPct: Double = 0.0,
        personalPct: Double? = null,
    ) = OrefAnalysisReport(
        windowDays = 7,
        mergedRowCount = 2000,
        validOutcomeRows = 1500,
        timeBelow70Pct = 6.0,
        timeAbove180Pct = 30.0,
        timeInRange70180Pct = 64.0,
        actualHypo4hPct = actualHypoPct,
        actualHyper4hPct = actualHyperPct,
        priority = priority,
        mlStatus = OrefMlStatus.OK,
        featureMissingPct = emptyMap(),
        hints = emptyList(),
        meanCalHypoRiskPct = 0.0,
        meanCalHyperRiskPct = 0.0,
        dataSufficiency = OrefDataSufficiency.GOOD,
        personalMlStatus = if (personalPct == null) OrefPersonalMlStatus.OFF else OrefPersonalMlStatus.TRAINED_AND_USED,
        personalMeanHypoSignalPct = personalPct,
        personalMeanHyperSignalPct = personalPct,
    )

    // ── The gate itself ───────────────────────────────────────────────────────

    @Test
    fun `personal signal is declared uncalibrated`() {
        assertFalse(
            OrefPersonalSignalGate.CALIBRATED,
            "The personal head still trains on raw output and is read through a sigmoid, so it is not calibrated"
        )
    }

    @Test
    fun `gate refuses every personal value while uncalibrated`() {
        for (pct in listOf(null, 0.0, 47.9, 48.0, 52.0, 62.4, 73.1, 100.0)) {
            assertFalse(OrefPersonalSignalGate.tripsDecision(pct, 48.0), "48 % gate opened at $pct")
            assertFalse(OrefPersonalSignalGate.tripsDecision(pct, 52.0), "52 % gate opened at $pct")
        }
    }

    // ── Hypo gate ─────────────────────────────────────────────────────────────

    @Test
    fun `personal signal alone cannot open the hypo gate`() {
        // 6 % below 70 is inside the band (5.5 %..7 %) where the gate asks OREF for a second opinion.
        val m = metrics(timeBelow70 = 0.06, timeAbove180 = 0.10)
        val recs = advisor.analysePkpd(m, pkpd(), rh, oref(OrefGlycemicPriority.HYPO, personalPct = 100.0))
        assertTrue(recs.isEmpty(), "personal signal at 100 % still produced PKPD suggestions: $recs")
    }

    @Test
    fun `measured hypo exposure still opens the hypo gate`() {
        val m = metrics(timeBelow70 = 0.06, timeAbove180 = 0.10)
        val recs = advisor.analysePkpd(m, pkpd(), rh, oref(OrefGlycemicPriority.HYPO, actualHypoPct = 25.0))
        assertTrue(recs.isNotEmpty(), "the calibrated hypo input must still trigger PKPD tuning")
    }

    // ── Hyper gate ────────────────────────────────────────────────────────────

    @Test
    fun `personal signal alone cannot open the hyper gate`() {
        // 30 % above 180 with no lows: below the 40 % hard trigger, so only OREF could decide.
        val m = metrics(timeBelow70 = 0.0, timeAbove180 = 0.30)
        val recs = advisor.analysePkpd(m, pkpd(), rh, oref(OrefGlycemicPriority.HYPER, personalPct = 100.0))
        assertTrue(recs.isEmpty(), "personal signal at 100 % still produced PKPD suggestions: $recs")
    }

    @Test
    fun `measured hyper exposure still opens the hyper gate`() {
        val m = metrics(timeBelow70 = 0.0, timeAbove180 = 0.30)
        val recs = advisor.analysePkpd(m, pkpd(), rh, oref(OrefGlycemicPriority.HYPER, actualHyperPct = 25.0))
        assertTrue(recs.isNotEmpty(), "the calibrated hyper input must still trigger PKPD tuning")
    }

    // ── Quiet period ──────────────────────────────────────────────────────────

    @Test
    fun `personal signal alone cannot break the quiet period`() {
        // todayTir >= 7-day TIR: control is improving, so the advisor stays quiet unless OREF contradicts it.
        val m = metrics(timeBelow70 = 0.06, timeAbove180 = 0.10, todayTir = 0.70)
        val recs = advisor.analysePkpd(m, pkpd(), rh, oref(OrefGlycemicPriority.BOTH, personalPct = 100.0))
        assertTrue(recs.isEmpty(), "personal signal at 100 % broke the quiet period: $recs")
    }

    @Test
    fun `measured exposure still breaks the quiet period`() {
        val m = metrics(timeBelow70 = 0.06, timeAbove180 = 0.10, todayTir = 0.70)
        val recs = advisor.analysePkpd(m, pkpd(), rh, oref(OrefGlycemicPriority.HYPO, actualHypoPct = 25.0))
        assertTrue(recs.isNotEmpty(), "the calibrated hypo input must still break the quiet period")
    }
}
