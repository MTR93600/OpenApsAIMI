package app.aaps.plugins.aps.openAPSAIMI.advisor

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.model.AimiAction
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Step A1: the text of a PK/PD advisor proposal must show the value that the proposal really writes.
 *
 * Before, the ISF fusion text said "Max x2.0" while the action only added one step of 0.1, and the
 * tail damping reason was built without its value, so the stored reason showed a raw "%1$s".
 */
class PkpdAdvisorReasonTextTest {

    /** Writes which string was asked for and with which values, so the test can read them back. */
    private object RecordingText : TextResolver {
        override fun gs(ref: TextRef): String = name(ref)
        override fun gs(ref: TextRef, vararg args: Any?): String = name(ref) + args.joinToString(prefix = "(", postfix = ")")
        override fun gsNotLocalised(ref: TextRef): String = name(ref)

        private fun name(ref: TextRef): String = when (ref) {
            ApsStrings.aimi_pkpd_hyper_isf     -> "hyper_isf"
            ApsStrings.aimi_pkpd_hypo_damping -> "hypo_damping"
            else                               -> "other"
        }
    }

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

    private fun pkpd(isfFusionMaxFactor: Double, smbTailDamping: Double) = PkpdPrefsSnapshot(
        pkpdEnabled = true,
        learnedDiaH = 7.0,
        learnedPeakMin = 60.0,
        maxDiaChangePerDayH = 0.5,
        maxPeakChangePerDayMin = 5.0,
        isfFusionMinFactor = 0.7,
        isfFusionMaxFactor = isfFusionMaxFactor,
        isfFusionMaxChangePerTick = 0.05,
        smbTailThreshold = 0.6,
        smbTailDamping = smbTailDamping,
        smbExerciseDamping = 0.8,
        smbLateFatDamping = 0.8,
    )

    private fun update(recs: List<AimiRecommendation>, key: DoubleKey): Pair<AimiRecommendation, AimiAction.PreferenceUpdate> =
        recs.mapNotNull { rec -> (rec.action as? AimiAction.PreferenceUpdate)?.takeIf { it.key == key }?.let { rec to it } }.single()

    @Test
    fun theIsfFusionTextShowsTheOneStepValueItWrites() {
        // 1.1 + 0.1 is 1.2000000000000002 as a double; the text shows 1.2.
        val recs = PkpdAdvisor().analysePkpd(metrics(timeBelow70 = 0.01, timeAbove180 = 0.45), pkpd(isfFusionMaxFactor = 1.1, smbTailDamping = 0.85), RecordingText)

        val (rec, action) = update(recs, DoubleKey.OApsAIMIIsfFusionMaxFactor)

        assertEquals(1.2, action.newValue as Double, 1e-9)
        assertEquals("hyper_isf(1.2)", action.reason)
        assertEquals(listOf("1.2"), rec.descriptionArgs)
    }

    @Test
    fun theTailDampingReasonShowsTheNewFloor() {
        val recs = PkpdAdvisor().analysePkpd(metrics(timeBelow70 = 0.09, timeAbove180 = 0.10), pkpd(isfFusionMaxFactor = 1.5, smbTailDamping = 0.85), RecordingText)

        val (rec, action) = update(recs, DoubleKey.OApsAIMISmbTailDamping)

        assertEquals(0.75, action.newValue as Double, 1e-9)
        assertEquals("hypo_damping(0.75)", action.reason)
        assertEquals(listOf("0.75"), rec.descriptionArgs)
    }
}
