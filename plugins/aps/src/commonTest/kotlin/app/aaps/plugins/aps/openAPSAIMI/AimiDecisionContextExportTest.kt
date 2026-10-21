package app.aaps.plugins.aps.openAPSAIMI

import app.aaps.plugins.aps.openAPSAIMI.pkpd.PkpdSoftFloorTelemetry
import app.aaps.plugins.aps.openAPSAIMI.utils.JsonObj
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The quarter-basal scene stores raw 39, soft 39, hybrid 39, floor hit, not applied,
 * endogenous reversion off. The export field is that object. Same function on Android and iOS.
 */
class AimiDecisionContextExportTest {

    @Test
    fun quarterBasalExportsThePkpdSoftFloor() {
        val floor = PkpdSoftFloorTelemetry(
            rawPathMinMgdl = 39.0,
            softPathMinMgdl = 39.0,
            hybridTerminalMgdl = 39.0,
            hitNumericFloor = true,
            applied = false,
            endogenousReversionEnabled = false,
            suppressedByFallingTrend = false,
            reason = "endo_reversion_disabled",
        )
        val ctx = AimiDecisionContext(
            event_id = "quarter-basal",
            timestamp = 1_700_000_000_000L,
            trigger = "low-prediction",
            baseline_state = AimiDecisionContext.BaselineState(
                profile_isf_mgdl = 50.0,
                profile_basal_uph = 1.0,
                current_bg_mgdl = 100.0,
                cob_g = 0.0,
                iob_u = 2.0,
            ),
        )
        ctx.adjustments.pkpd_soft_floor = floor.toJsonObject()
        val json = ctx.toMedicalJson()
        assertTrue(json.contains("\"pkpd_soft_floor\""), json)
        assertTrue(json.contains("\"raw_path_min_mgdl\":39"), json)
        assertTrue(json.contains("\"soft_path_min_mgdl\":39"), json)
        assertTrue(json.contains("\"hybrid_terminal_mgdl\":39"), json)
        assertTrue(json.contains("\"hit_numeric_floor\":true"), json)
        assertTrue(json.contains("\"applied\":false"), json)
        assertTrue(json.contains("\"endogenous_reversion_enabled\":false"), json)
        assertTrue(json.contains("\"reason\":\"endo_reversion_disabled\""), json)
    }

    @Test
    fun mealAndMcerDiagnosticsAreExported() {
        val ctx = AimiDecisionContext(
            event_id = "meal-diagnostics",
            timestamp = 1_700_000_000_000L,
            trigger = "meal",
            baseline_state = AimiDecisionContext.BaselineState(
                profile_isf_mgdl = 50.0,
                profile_basal_uph = 1.0,
                current_bg_mgdl = 180.0,
                cob_g = 20.0,
                iob_u = 2.0,
            ),
        )
        ctx.adjustments.meal_absorption_phase = AimiDecisionContext.MealAbsorptionPhaseExport(
            phase = "FIRST_WAVE",
            belief = 0.8,
            reason = "test",
            memory_active = true,
            wave_count = 1,
            meal_delivery_priority = true,
            chrono_prior = 0.8,
            kinetic_score = 0.7,
            trajectory_score = 0.6,
            physio_score = 0.0,
            raw_phase = "FIRST_WAVE",
            meal_intent_source = "DECLARED",
            belief_base = 0.4,
            bonus_intent = 0.25,
            bonus_cob = 0.15,
            bonus_physio_meal = 0.0,
            bonus_ra = 0.0,
            fast_rise = true,
            moderate_rise = true,
        )
        ctx.adjustments.mcer = JsonObj().apply {
            put("armed", true)
            put("tail_by_iob", false)
        }.build()

        val json = ctx.toMedicalJson()
        assertTrue(json.contains("\"raw_phase\":\"FIRST_WAVE\""), json)
        assertTrue(json.contains("\"meal_intent_source\":\"DECLARED\""), json)
        assertTrue(json.contains("\"bonus_physio_meal\":0"), json)
        assertTrue(json.contains("\"mcer\""), json)
        assertTrue(json.contains("\"tail_by_iob\":false"), json)
    }
}
