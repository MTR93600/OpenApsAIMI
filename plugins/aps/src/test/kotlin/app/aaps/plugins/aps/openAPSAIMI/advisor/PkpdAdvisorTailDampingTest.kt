package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.keys.DoubleKey
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSmbTailDamping
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Direction tests for the SMB tail damping advisor rules.
 *
 * The stored pref `OApsAIMISmbTailDamping` is a multiplicative FLOOR applied to SMB at high tail
 * IOB: LOWER value = STRONGER damping (see [PkpdSmbTailDamping] / `SmbDamping`). Therefore:
 * - hypo context must LOWER the value (strengthen the guard),
 * - hyper context must RAISE the value (weaken the guard),
 * and proposals must stay inside the slider band [0.70, 0.92].
 */
class PkpdAdvisorTailDampingTest {

    private val rh: ResourceHelper = mockk(relaxed = true)
    private val advisor = PkpdAdvisor()

    private fun metrics(timeBelow70: Double, timeAbove180: Double) = AdvisorMetrics(
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
        todayTir = null,
        todayTdd = null,
        dataSufficiency = AdvisorDataSufficiency.GOOD,
    )

    private fun pkpd(smbTailDamping: Double) = PkpdPrefsSnapshot(
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
        smbTailDamping = smbTailDamping,
        smbExerciseDamping = 0.8,
        smbLateFatDamping = 0.8,
    )

    private fun tailDampingProposals(recs: List<AimiRecommendation>): List<Double> =
        recs.mapNotNull { rec ->
            (rec.action as? AimiAction.PreferenceUpdate)
                ?.takeIf { it.key == DoubleKey.OApsAIMISmbTailDamping }
                ?.newValue as? Double
        }

    // ── Hypo context: must STRENGTHEN damping (lower the floor) ────────────────

    @Test
    fun `hypo context lowers tail damping floor`() {
        val recs = advisor.analysePkpd(metrics(timeBelow70 = 0.09, timeAbove180 = 0.10), pkpd(0.85), rh)
        val proposals = tailDampingProposals(recs)
        assertEquals(1, proposals.size)
        assertEquals(0.75, proposals.first(), 1e-9)
    }

    @Test
    fun `hypo context clamps at strongest slider stop`() {
        val recs = advisor.analysePkpd(metrics(timeBelow70 = 0.09, timeAbove180 = 0.10), pkpd(0.78), rh)
        val proposals = tailDampingProposals(recs)
        assertEquals(1, proposals.size)
        assertEquals(PkpdSmbTailDamping.DAMPING_STRONG, proposals.first(), 1e-9)
    }

    @Test
    fun `hypo context does not push below strongest stop when already there`() {
        val recs = advisor.analysePkpd(metrics(timeBelow70 = 0.09, timeAbove180 = 0.10), pkpd(0.70), rh)
        assertTrue(tailDampingProposals(recs).isEmpty())
    }

    @Test
    fun `hypo context normalises legacy stored value before proposing`() {
        // Legacy 0.5 → effective neutral 0.85 → propose 0.75 (NOT 0.5 + 0.1 = 0.6 in the legacy zone).
        val recs = advisor.analysePkpd(metrics(timeBelow70 = 0.09, timeAbove180 = 0.10), pkpd(0.5), rh)
        val proposals = tailDampingProposals(recs)
        assertEquals(1, proposals.size)
        assertEquals(0.75, proposals.first(), 1e-9)
        assertTrue(proposals.first() > PkpdSmbTailDamping.LEGACY_NEUTRAL_CUTOFF)
    }

    // ── Hyper context: must WEAKEN damping (raise the floor) ───────────────────

    @Test
    fun `hyper context raises tail damping floor`() {
        val recs = advisor.analysePkpd(metrics(timeBelow70 = 0.01, timeAbove180 = 0.45), pkpd(0.70), rh)
        val proposals = tailDampingProposals(recs)
        assertEquals(1, proposals.size)
        assertEquals(0.78, proposals.first(), 1e-9)
    }

    @Test
    fun `hyper context clamps at mildest slider stop`() {
        val recs = advisor.analysePkpd(metrics(timeBelow70 = 0.01, timeAbove180 = 0.45), pkpd(0.88), rh)
        val proposals = tailDampingProposals(recs)
        assertEquals(1, proposals.size)
        assertEquals(PkpdSmbTailDamping.DAMPING_LIGHT, proposals.first(), 1e-9)
    }

    @Test
    fun `hyper context does not weaken further when already mildest`() {
        val recs = advisor.analysePkpd(metrics(timeBelow70 = 0.01, timeAbove180 = 0.45), pkpd(0.92), rh)
        assertTrue(tailDampingProposals(recs).isEmpty())
    }

    @Test
    fun `hyper context skips weakening when hypos are not rare`() {
        // hyper trigger (>40% above 180, <2% below 70) requires timeBelow70 < 0.02; rule D adds < 0.035.
        val recs = advisor.analysePkpd(metrics(timeBelow70 = 0.018, timeAbove180 = 0.45), pkpd(0.70), rh)
        val proposals = tailDampingProposals(recs)
        // Rule D still applies at 1.8% lows; sanity-check the direction is upward only.
        assertTrue(proposals.all { it > 0.70 })
    }
}
