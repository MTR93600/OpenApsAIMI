package app.aaps.plugins.aps.openAPSAIMI

import android.content.Context
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.resources.TextRefIdRegistry
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.ApsStringIds
import app.aaps.plugins.aps.ApsStrings
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Guards every string the AIMI dosing core builds, because nothing else does.
 *
 * That is `DetermineBasalaimiSMB2` and the two neighbours it passes its `rT.reason` to,
 * `AimiUamHandler` and `SmbInstructionExecutor`.
 *
 * These classes write their strings into `rT.reason` and into the console log, and both are uploaded
 * to Nightscout, so the user reads them. Nothing constructs them in a test, so a wrong format
 * argument would only show up on a real pump, inside the dosing loop.
 *
 * The names themselves are safe without a test: they come from the generated `ApsStrings` object, so
 * a name that is not in `strings.xml` does not compile. What is not safe is the number of arguments,
 * which the compiler never checks - `gs(ref, vararg args: Any?)` accepts any count.
 *
 * So this test holds the count each call site passes, reads the template back from the resources,
 * and checks three things for every name:
 *  - the name resolves to real text and not to itself, which is what the resolver returns when the
 *    owner is not registered;
 *  - the template has exactly as many placeholders as the call sites pass arguments;
 *  - filling the template with that many arguments, of the type each placeholder asks for, leaves no
 *    unfilled placeholder behind.
 *
 * When a string gains or loses a placeholder, this test fails and names it. Fix the call sites in
 * the call sites and the count here together.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DetermineBasalAimiStringsTest {

    private lateinit var rh: ResourceHelper

    @Before
    fun setUp() {
        // What MainApp does at startup through GeneratedStringOwners. Without it a TextRef.Named
        // owned by `aps` resolves to its own raw name.
        TextRefIdRegistry.register("aps") { name -> ApsStringIds.idOf(name) }
        rh = ContextResourceHelper(RuntimeEnvironment.getApplication())
    }

    @Test
    fun everyNameResolvesToRealText() {
        val problems = usedStrings.keys.mapNotNull { ref ->
            val name = (ref as TextRef.Named).name
            val text = rh.gs(ref)
            when {
                text == name  -> "$name: does not resolve, the resolver gave the raw name back"
                text.isBlank() -> "$name: resolves to blank text"
                else          -> null
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun everyTemplateIsFilledByTheArgumentsTheCallSitesPass() {
        val problems = mutableListOf<String>()
        usedStrings.forEach { (ref, expectedArgs) ->
            val name = (ref as TextRef.Named).name
            val template = rh.gs(ref)
            val placeholders = placeholdersOf(template)
            if (placeholders.size != expectedArgs) {
                problems += "$name: the template has ${placeholders.size} placeholders but the call " +
                    "sites pass $expectedArgs arguments - template was \"$template\""
                return@forEach
            }
            if (expectedArgs == 0) return@forEach
            val args = Array<Any?>(expectedArgs) { sampleFor(placeholders.getValue(it + 1)) }
            val filled = try {
                rh.gs(ref, *args)
            } catch (e: Exception) {
                problems += "$name: filling the template threw ${e::class.simpleName} - ${e.message}"
                return@forEach
            }
            // A "%%" in the template becomes one plain "%" once the template is filled. Any other
            // "%" left over means a placeholder was not filled.
            val expectedPercent = countOf(template, "%%")
            val actualPercent = filled.count { it == '%' }
            if (actualPercent != expectedPercent) {
                problems += "$name: $actualPercent \"%\" left after filling, expected " +
                    "$expectedPercent - result was \"$filled\""
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** Placeholder index to conversion letter, for example `%2$.1f` gives `2` to `f`. */
    private fun placeholdersOf(template: String): Map<Int, Char> {
        val found = mutableMapOf<Int, Char>()
        SPECIFIER.findAll(template).forEach { match ->
            val explicit = match.groupValues[1]
            val index = if (explicit.isEmpty()) found.size + 1 else explicit.toInt()
            found[index] = match.groupValues[4].first()
        }
        return found
    }

    /** A value of the type [conversion] asks for. `%d` with a String would throw. */
    private fun sampleFor(conversion: Char): Any = when (conversion) {
        'd', 'o', 'x', 'X'                -> 7
        'f', 'e', 'E', 'g', 'G', 'a', 'A' -> 1.5
        else                              -> "sample"
    }

    private fun countOf(text: String, part: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = text.indexOf(part, from)
            if (at < 0) return count
            count++
            from = at + part.length
        }
    }

    /**
     * The resolver used by the test, backed by the Robolectric resources.
     *
     * Only the id overloads are written here. `gs(TextRef)` and `gs(TextRef, vararg)` come from
     * `ResourceHelper` itself, so this test walks the same code the app walks.
     */
    private class ContextResourceHelper(private val context: Context) : ResourceHelper {

        override fun gs(id: Int): String = context.getString(id)
        override fun gs(id: Int, vararg args: Any?): String = context.getString(id, *args)
        override fun gq(id: Int, quantity: Int, vararg args: Any?): String =
            context.resources.getQuantityString(id, quantity, *args)

        override fun gsNotLocalised(id: Int, vararg args: Any?): String = context.getString(id, *args)
        override fun shortTextMode(): Boolean = false
    }

    companion object {

        /** `%s`, `%1$s`, `%.2f`, `%1$.2f` and the rest. `%%` does not match, which is the point. */
        private val SPECIFIER = Regex("""%(?:(\d+)\$)?([-+ 0#,(]*)([\d.]*)([a-zA-Z])""")

        /**
         * Every string the AIMI dosing core resolves, with the number of arguments its call sites
         * pass. 164 names over 175 call sites, read off the files when they stopped using
         * `context.getString`.
         *
         * Three files, because they build one text between them: `DetermineBasalaimiSMB2` itself,
         * and the two neighbours it hands its `rT.reason` to, `AimiUamHandler` and
         * `SmbInstructionExecutor`. All three write into the same `reason`, so a wrong argument
         * count in any of them shows up on the same Nightscout line.
         */
        private val usedStrings: Map<TextRef, Int> = mapOf(
            ApsStrings.adjustments_smb to 2,
            ApsStrings.aimi_iob_surveillance_applied to 0,
            ApsStrings.aimi_prebolus_not_delivered to 2,
            ApsStrings.autodrive_status to 2,
            ApsStrings.autosens_ratio_log to 1,
            ApsStrings.bg_combined_delta_high to 1,
            ApsStrings.bg_combined_delta_moderate to 1,
            ApsStrings.bg_combined_delta_weak to 1,
            ApsStrings.bg_delta to 2,
            ApsStrings.bg_drop_high_critical to 1,
            ApsStrings.bg_drop_high_warning to 1,
            ApsStrings.bg_near_target to 2,
            ApsStrings.bg_near_target_but_rising to 4,
            ApsStrings.bg_note_less_aggressive to 0,
            ApsStrings.bg_note_low_treatment to 0,
            ApsStrings.bg_note_more_aggressive to 0,
            ApsStrings.bg_note_normal to 0,
            ApsStrings.bg_note_too_aggressive to 0,
            ApsStrings.bg_rapid_rise to 1,
            ApsStrings.bg_stable_high_delta_low to 0,
            ApsStrings.bg_trend_analysis to 0,
            ApsStrings.cache_hit to 1,
            ApsStrings.cache_hit_invalid to 0,
            ApsStrings.calc_dynamic_peaktime to 0,
            ApsStrings.calculated_trend to 1,
            ApsStrings.condition_acceleratingdown to 0,
            ApsStrings.condition_belowminthreshold to 0,
            ApsStrings.condition_belowtarget_dropping to 0,
            ApsStrings.condition_belowtarget_stable_nocob to 0,
            ApsStrings.condition_bg90 to 0,
            ApsStrings.condition_droppingfast to 0,
            ApsStrings.condition_droppingfastathigh to 0,
            ApsStrings.condition_droppingveryfast to 0,
            ApsStrings.condition_fasting to 0,
            ApsStrings.condition_honeysmb to 0,
            ApsStrings.condition_hypoguard to 0,
            ApsStrings.condition_negdelta to 0,
            ApsStrings.condition_newcalibration to 0,
            ApsStrings.condition_nosmb to 0,
            ApsStrings.condition_prediction to 0,
            ApsStrings.console_adjust_basal to 2,
            ApsStrings.console_basal_unchanged to 1,
            ApsStrings.console_carb_impact to 3,
            ApsStrings.console_dia_adjusted to 1,
            ApsStrings.console_limiting_carb_impact to 3,
            ApsStrings.console_max_bg_adjusted to 2,
            ApsStrings.console_max_bg_unchanged to 1,
            ApsStrings.console_min_bg_adjusted to 2,
            ApsStrings.console_min_bg_unchanged to 1,
            ApsStrings.console_profile_sens to 3,
            ApsStrings.console_target_bg_adjusted to 2,
            ApsStrings.console_target_bg_changed to 2,
            ApsStrings.console_target_bg_unchanged to 1,
            ApsStrings.console_temp_target_set to 0,
            ApsStrings.dia_base_info to 2,
            ApsStrings.dia_calculation_details to 0,
            ApsStrings.fcl_prebolus to 1,
            ApsStrings.file_too_short to 0,
            ApsStrings.final_dia_constrained to 1,
            ApsStrings.finalization_smb to 2,
            ApsStrings.first_bg_value to 1,
            ApsStrings.folder_documents to 0,
            InterfacesStrings.format_insulin_units to 1,
            ApsStrings.heart_rate to 1,
            ApsStrings.hypo_risk_notification_text to 0,
            ApsStrings.insulin_effect to 1,
            ApsStrings.iob_high_reduction to 1,
            ApsStrings.last_200_deleted to 1,
            ApsStrings.last_bg_value to 1,
            ApsStrings.lgs_triggered to 2,
            ApsStrings.lgs_triggered_min_pred to 2,
            ApsStrings.lgs_triggered_predicted to 3,
            ApsStrings.limits_smb to 2,
            ApsStrings.log_error_closing_interpreter to 1,
            ApsStrings.log_failed_init_uam to 1,
            ApsStrings.log_interpreter_closed to 0,
            ApsStrings.log_interpreter_initialized to 2,
            ApsStrings.log_model_file_not_found to 1,
            ApsStrings.log_smb_cache_cleared to 0,
            ApsStrings.log_tflite_failed to 1,
            ApsStrings.manual_basal_override to 3,
            ApsStrings.manual_meal_prebolus to 1,
            ApsStrings.meal_mode_first_30 to 2,
            ApsStrings.model_load_failed to 1,
            ApsStrings.model_loaded to 2,
            ApsStrings.model_missing to 1,
            ApsStrings.morning_adjustment to 0,
            ApsStrings.night_adjustment to 0,
            ApsStrings.no_bg_history to 0,
            ApsStrings.no_conditions_met_2 to 0,
            ApsStrings.number_of_values to 1,
            ApsStrings.oaps_aimi_ngr_basal_applied to 2,
            ApsStrings.oaps_aimi_ngr_headroom to 2,
            ApsStrings.oaps_aimi_ngr_smb_applied to 3,
            ApsStrings.original_file_missing to 0,
            ApsStrings.peak_time to 3,
            ApsStrings.profile_peak_time to 1,
            ApsStrings.pump_age_adjustment to 2,
            ApsStrings.reason_activity_cap to 1,
            ApsStrings.reason_activity_ratio to 2,
            ApsStrings.reason_additional_carbs to 2,
            ApsStrings.reason_ai_file to 2,
            ApsStrings.reason_autodrive_v3_authoritative_blender_skipped to 0,
            ApsStrings.reason_bg_data_old to 3,
            ApsStrings.reason_bg_dropping to 1,
            ApsStrings.reason_bg_dropping_floor to 2,
            ApsStrings.reason_bio_sync_flow to 3,
            ApsStrings.reason_bio_sync_stress to 2,
            ApsStrings.reason_boost_hyper to 2,
            ApsStrings.reason_boost_hyper_2 to 2,
            ApsStrings.reason_cgm_calibrating to 0,
            ApsStrings.reason_cgm_flat to 0,
            ApsStrings.reason_data_removed to 1,
            ApsStrings.reason_deletion_time_restricted to 0,
            ApsStrings.reason_eventual_bg to 2,
            ApsStrings.reason_hyper_correction to 1,
            ApsStrings.reason_hypo_guard to 5,
            ApsStrings.reason_insulin_required to 1,
            ApsStrings.reason_iob_adjustment_inverted to 1,
            ApsStrings.reason_iob_max to 2,
            ApsStrings.reason_max_iob to 1,
            ApsStrings.reason_max_smb to 1,
            ApsStrings.reason_maxsmb to 1,
            ApsStrings.reason_meal_aggression_boost to 3,
            ApsStrings.reason_meal_high_iob_relaxed to 3,
            ApsStrings.reason_microbolus to 1,
            ApsStrings.reason_ml_training to 0,
            ApsStrings.reason_mpc_pi to 4,
            ApsStrings.reason_prebolus_bfast1 to 1,
            ApsStrings.reason_prebolus_bfast2 to 1,
            ApsStrings.reason_prebolus_dinner1 to 1,
            ApsStrings.reason_prebolus_dinner2 to 1,
            ApsStrings.reason_prebolus_highcarb to 1,
            ApsStrings.reason_prebolus_lunch1 to 1,
            ApsStrings.reason_prebolus_lunch2 to 1,
            ApsStrings.reason_prebolus_snack to 1,
            ApsStrings.reason_safety_sport_meal_reduction to 2,
            ApsStrings.reason_sensor_lag to 0,
            ApsStrings.reason_sensor_lag_lower to 0,
            ApsStrings.reason_set_temp_basal to 1,
            ApsStrings.reason_wait_microbolus to 2,
            ApsStrings.safety_condition to 2,
            ApsStrings.safety_sport_smb_zero to 0,
            ApsStrings.sanitize_info to 1,
            ApsStrings.sensitivity_ratio_temp_target to 2,
            ApsStrings.smb_disabled to 0,
            ApsStrings.smb_disabled_high_target to 1,
            ApsStrings.smb_disabled_no_pref_or_condition to 0,
            ApsStrings.smb_enabled_after_carb_entry to 0,
            ApsStrings.smb_enabled_always to 0,
            ApsStrings.smb_enabled_for_cob to 1,
            ApsStrings.smb_enabled_for_temp_target to 1,
            ApsStrings.smb_enabled_meal_mode to 3,
            ApsStrings.smb_final to 1,
            ApsStrings.steps to 1,
            ApsStrings.tdd_per_hour_high to 1,
            ApsStrings.temp_basal_pose to 2,
            ApsStrings.tflite_failed to 1,
            ApsStrings.tir_high to 1,
            ApsStrings.uam_executed to 1,
            ApsStrings.uam_invalid to 1,
            ApsStrings.uam_model_status to 3,
            ApsStrings.uam_unavailable to 0,
            ApsStrings.zero_basal_forced to 1,
        )
    }
}
