package app.aaps.plugins.aps.openAPSAIMI.prediction

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PredictionDivergenceAuditorTest {

    @Test
    fun `clamp disagreement when pkpd low but scenario high in zone 2`() {
        // BG 145 (zone 2), PKPD sees 95 (would clamp to low max), scenario sees 160.
        val audit = PredictionDivergenceAuditor.audit(bgMgdl = 145.0, pkpdEventualMgdl = 95.0, scenarioBestMgdl = 160.0)

        assertTrue(audit.pkpdTriggersLowClamp)
        assertEquals(false, audit.scenarioTriggersLowClamp)
        assertTrue(audit.lowClampDisagreement)
        assertEquals(65.0, audit.divergenceMgdl!!, 1e-9)
    }

    @Test
    fun `no disagreement when both predictions agree on the clamp`() {
        val audit = PredictionDivergenceAuditor.audit(bgMgdl = 145.0, pkpdEventualMgdl = 95.0, scenarioBestMgdl = 100.0)

        assertTrue(audit.pkpdTriggersLowClamp)
        assertEquals(true, audit.scenarioTriggersLowClamp)
        assertFalse(audit.lowClampDisagreement)
    }

    @Test
    fun `clamp flags stay false outside zone 2`() {
        // BG 200 is above the zone-2 band: the eventual-driven clamp does not apply.
        val audit = PredictionDivergenceAuditor.audit(bgMgdl = 200.0, pkpdEventualMgdl = 95.0, scenarioBestMgdl = 90.0)

        assertFalse(audit.pkpdTriggersLowClamp)
        assertEquals(false, audit.scenarioTriggersLowClamp)
        assertFalse(audit.lowClampDisagreement)
    }

    @Test
    fun `missing or non-finite scenario yields null fields and no disagreement`() {
        val absent = PredictionDivergenceAuditor.audit(bgMgdl = 145.0, pkpdEventualMgdl = 95.0, scenarioBestMgdl = null)
        assertNull(absent.scenarioBestMgdl)
        assertNull(absent.divergenceMgdl)
        assertNull(absent.scenarioTriggersLowClamp)
        assertFalse(absent.lowClampDisagreement)

        val nonFinite = PredictionDivergenceAuditor.audit(bgMgdl = 145.0, pkpdEventualMgdl = 95.0, scenarioBestMgdl = Double.NaN)
        assertNull(nonFinite.scenarioBestMgdl)
        assertFalse(nonFinite.lowClampDisagreement)
    }

    @Test
    fun `log line is compact and flags disagreement`() {
        val audit = PredictionDivergenceAuditor.audit(bgMgdl = 145.0, pkpdEventualMgdl = 95.0, scenarioBestMgdl = 160.0)
        val line = PredictionDivergenceAuditor.formatLogLine(audit, physioPhase = "CORTISOL_MORNING_RISE", mealPhase = "FIRST_WAVE")

        assertEquals(
            "PRED_DIVERGENCE: bg=145 evPkpd=95 bestScn=160 Δ=65 phase=CORTISOL_MORNING_RISE meal=FIRST_WAVE " +
                "clampPkpd=true clampScn=false ⚠️CLAMP_DISAGREE",
            line,
        )
    }

    @Test
    fun `json export carries the audit fields`() {
        val audit = PredictionDivergenceAuditor.audit(bgMgdl = 145.0, pkpdEventualMgdl = 95.0, scenarioBestMgdl = 160.0)
        val json = PredictionDivergenceAuditor.toJsonObject(audit, physioPhase = "CORTISOL_MORNING_RISE", mealPhase = null)

        assertEquals(145.0, json.getDouble("bg_mgdl"), 1e-9)
        assertEquals(95.0, json.getDouble("pkpd_eventual_mgdl"), 1e-9)
        assertEquals(160.0, json.getDouble("scenario_best_mgdl"), 1e-9)
        assertEquals(65.0, json.getDouble("divergence_mgdl"), 1e-9)
        assertEquals("CORTISOL_MORNING_RISE", json.getString("physio_phase"))
        assertTrue(json.isNull("meal_phase"))
        assertTrue(json.getBoolean("pkpd_triggers_low_clamp"))
        assertFalse(json.getBoolean("scenario_triggers_low_clamp"))
        assertTrue(json.getBoolean("low_clamp_disagreement"))
    }

    @Test
    fun `log line handles missing scenario and phases`() {
        val audit = PredictionDivergenceAuditor.audit(bgMgdl = 100.0, pkpdEventualMgdl = 110.0, scenarioBestMgdl = null)
        val line = PredictionDivergenceAuditor.formatLogLine(audit, physioPhase = null, mealPhase = null)

        assertEquals(
            "PRED_DIVERGENCE: bg=100 evPkpd=110 bestScn=- Δ=- phase=- meal=- clampPkpd=false clampScn=-",
            line,
        )
    }
}
