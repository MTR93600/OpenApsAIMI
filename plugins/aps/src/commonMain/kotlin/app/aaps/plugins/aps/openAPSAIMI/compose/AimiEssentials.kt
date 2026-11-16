package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.preference.PreferenceSubScreenDef
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey

/** The two groups of the Essentials page. */
internal enum class AimiEssentialsGroup {

    /** Facts about the person: body, usual insulin and carbs, age, honeymoon, pregnancy. */
    PATIENT,

    /** The limits on insulin delivery. */
    DOSE_LIMITS,
}

/**
 * One row of the Essentials page.
 *
 * @param key the setting; its storage name is the one every other screen uses, so the value is shared
 * @param group where the row is shown
 * @param hint one line under the label
 * @param warnings the last lines of the "?" sheet: every row here changes insulin delivery
 * @param unit unit label for a key that carries none of its own (kg, U/day, g, years)
 * @param title label to show instead of the key's own title, or null
 */
internal data class AimiEssentialsRow(
    val key: PreferenceKey,
    val group: AimiEssentialsGroup,
    val hint: TextRef,
    val warnings: List<TextRef>,
    val unit: TextRef? = null,
    val title: TextRef? = null,
)

/**
 * The Essentials page: the person's own data and the dose limits, at the top of the AIMI settings.
 *
 * It holds no new setting. Every row edits a key that keeps its storage name and keeps its old place
 * in the settings too, so a stored value survives and nothing a person knew moves away. The page adds
 * what the old screens did not have: a hint, the value in use now, a badge when TPO or a safety limit
 * changed it, and a "?" sheet with the default and the warnings.
 *
 * The AIMI keys of [rows] are declared in the `Essentials*` sections of [AimiSettingsScreens], and
 * `AimiEssentialsTest` checks that both lists stay the same.
 */
internal object AimiEssentials {

    /** Key of the page. The link row that opens it uses `AimiEssentialsLink`. */
    const val SCREEN_KEY: String = "aimi_compose_essentials"

    private val patientWarning = listOf(ApsStrings.aimi_setting_warning_patient_input)
    private val limitWarning = listOf(ApsStrings.aimi_setting_warning_dose_limit)

    val rows: List<AimiEssentialsRow> = listOf(
        AimiEssentialsRow(DoubleKey.OApsAIMIweight, AimiEssentialsGroup.PATIENT, ApsStrings.aimi_essentials_hint_weight, patientWarning, unit = CoreUiStrings.units_kg),
        AimiEssentialsRow(DoubleKey.OApsAIMITDD7, AimiEssentialsGroup.PATIENT, ApsStrings.aimi_essentials_hint_tdd7, patientWarning, unit = ApsStrings.aimi_unit_insulin_per_day),
        AimiEssentialsRow(DoubleKey.OApsAIMICHO, AimiEssentialsGroup.PATIENT, ApsStrings.aimi_essentials_hint_cho, patientWarning, unit = CoreUiStrings.units_grams),
        AimiEssentialsRow(
            IntKey.OApsAIMINightGrowthAgeYears, AimiEssentialsGroup.PATIENT, ApsStrings.aimi_essentials_hint_age, patientWarning,
            unit = CoreUiStrings.units_years, title = ApsStrings.aimi_essentials_patient_age_title,
        ),
        AimiEssentialsRow(BooleanKey.OApsAIMIhoneymoon, AimiEssentialsGroup.PATIENT, ApsStrings.aimi_essentials_hint_honeymoon, patientWarning),
        AimiEssentialsRow(BooleanKey.OApsAIMIpregnancy, AimiEssentialsGroup.PATIENT, ApsStrings.aimi_essentials_hint_pregnancy, patientWarning),
        // Shown only while pregnancy is on; it keeps its place on the patient context screen.
        AimiEssentialsRow(AimiStringKey.PregnancyDueDateString, AimiEssentialsGroup.PATIENT, ApsStrings.aimi_essentials_hint_due_date, patientWarning),
        AimiEssentialsRow(DoubleKey.ApsMaxBasal, AimiEssentialsGroup.DOSE_LIMITS, ApsStrings.aimi_essentials_hint_max_basal, limitWarning),
        AimiEssentialsRow(DoubleKey.meal_modes_MaxBasal, AimiEssentialsGroup.DOSE_LIMITS, ApsStrings.aimi_essentials_hint_meal_max_basal, limitWarning, unit = CoreUiStrings.units_insulin_rate),
        AimiEssentialsRow(DoubleKey.autodriveMaxBasal, AimiEssentialsGroup.DOSE_LIMITS, ApsStrings.aimi_essentials_hint_autodrive_max_basal, limitWarning, unit = CoreUiStrings.units_insulin_rate),
        // The real cap of a temporary basal against the profile basal (owner decision, S1 review). It
        // keeps its old place in the OpenAPS advanced settings too.
        AimiEssentialsRow(
            DoubleKey.ApsMaxCurrentBasalMultiplier, AimiEssentialsGroup.DOSE_LIMITS, ApsStrings.aimi_essentials_hint_current_basal_multiplier, limitWarning,
            unit = ApsStrings.aimi_unit_times_profile_basal,
        ),
        AimiEssentialsRow(DoubleKey.ApsSmbMaxIob, AimiEssentialsGroup.DOSE_LIMITS, ApsStrings.aimi_essentials_hint_max_iob, limitWarning),
        AimiEssentialsRow(DoubleKey.OApsAIMIMaxSMB, AimiEssentialsGroup.DOSE_LIMITS, ApsStrings.aimi_essentials_hint_max_smb, limitWarning),
        AimiEssentialsRow(DoubleKey.OApsAIMIHighBGMaxSMB, AimiEssentialsGroup.DOSE_LIMITS, ApsStrings.aimi_essentials_hint_high_bg_max_smb, limitWarning),
        // Only the upper limit of the adaptive basal learner (heuristic channel), not a cap on every basal.
        AimiEssentialsRow(
            DoubleKey.OApsAIMIMaxMultiplier, AimiEssentialsGroup.DOSE_LIMITS, ApsStrings.aimi_essentials_hint_max_multiplier, limitWarning,
            unit = ApsStrings.aimi_unit_times_profile_basal,
        ),
        AimiEssentialsRow(UnitDoubleKey.ApsLgsThreshold, AimiEssentialsGroup.DOSE_LIMITS, ApsStrings.aimi_essentials_hint_lgs, listOf(ApsStrings.aimi_setting_warning_lgs)),
        AimiEssentialsRow(BooleanKey.ApsUseSmb, AimiEssentialsGroup.DOSE_LIMITS, ApsStrings.aimi_essentials_hint_use_smb, listOf(ApsStrings.aimi_setting_warning_smb)),
    )

    /** The page as a sub screen: its keys, for the tree walk and for the link row. */
    fun screenDef(): PreferenceSubScreenDef =
        PreferenceSubScreenDef(
            key = SCREEN_KEY,
            title = ApsStrings.aimi_essentials_title,
            summary = ApsStrings.aimi_essentials_summary,
            items = rows.map { it.key },
        )
}
