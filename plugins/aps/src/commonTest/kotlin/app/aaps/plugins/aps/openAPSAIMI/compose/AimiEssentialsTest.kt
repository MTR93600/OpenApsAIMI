package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.UnitDoubleKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.ui.CoreUiStrings
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthActivation
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthState
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiStringKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The Essentials page (step S1) and the setting row it is made of. No dosing is involved. */
class AimiEssentialsTest {

    // ------------------------------------------------------------------ the page

    @Test
    fun `the page lists the patient inputs and the dose limits in this order`() {
        assertEquals(
            listOf(
                DoubleKey.OApsAIMIweight.key,
                DoubleKey.OApsAIMITDD7.key,
                DoubleKey.OApsAIMICHO.key,
                IntKey.OApsAIMINightGrowthAgeYears.key,
                BooleanKey.OApsAIMIhoneymoon.key,
                BooleanKey.OApsAIMIpregnancy.key,
                AimiStringKey.PregnancyDueDateString.key,
                DoubleKey.ApsMaxBasal.key,
                DoubleKey.meal_modes_MaxBasal.key,
                DoubleKey.autodriveMaxBasal.key,
                DoubleKey.ApsMaxCurrentBasalMultiplier.key,
                DoubleKey.ApsSmbMaxIob.key,
                DoubleKey.OApsAIMIMaxSMB.key,
                DoubleKey.OApsAIMIHighBGMaxSMB.key,
                DoubleKey.OApsAIMIMaxMultiplier.key,
                IntKey.OApsAIMIManualBoostPercent.key,
                UnitDoubleKey.ApsLgsThreshold.key,
                BooleanKey.ApsUseSmb.key,
            ),
            AimiEssentials.rows.map { it.key.key },
        )
    }

    @Test
    fun `the age row is labelled as the patient age`() {
        val age = AimiEssentials.rows.single { it.key == IntKey.OApsAIMINightGrowthAgeYears }
        assertEquals(ApsStrings.aimi_essentials_patient_age_title, age.title)
        assertEquals(AimiEssentialsGroup.PATIENT, age.group)
    }

    @Test
    fun `every AIMI key of the page is declared in an essentials section and nothing else is`() {
        val aimiKeysOnPage = AimiEssentials.rows.map { it.key }.filter { AimiSettingsManifest.entryFor(it) != null }.map { it.key }.toSet()
        val declared = AimiSettingsScreens.sectionsOf(AimiSettingsContentId.PreferencesEssentials)
            .flatMap { section -> section.keys.map { it.key } }.toSet()
        assertEquals(aimiKeysOnPage, declared)
        assertTrue(AimiSettingsContentId.PreferencesEssentials in AimiSettingsScreens.routedContents)
    }

    /**
     * Checks only that every number key declares finite bounds. The row keeps the value inside them
     * because `AimiSettingRow` draws every key with the shared core/ui Adaptive*PreferenceItem controls.
     */
    @Test
    fun `every number key on the page declares finite bounds`() {
        AimiEssentials.rows.forEach { row ->
            when (val key = row.key) {
                is DoublePreferenceKey -> assertTrue(key.min > -Double.MAX_VALUE && key.max < Double.MAX_VALUE && key.min < key.max, key.key)
                is IntPreferenceKey    -> assertTrue(key.min < key.max, key.key)
            }
        }
    }

    @Test
    fun `every row warns because every row changes insulin delivery`() {
        AimiEssentials.rows.forEach { row ->
            val lines = aimiSettingSheetLines(row.key, row.unit, row.warnings)
            assertIs<AimiSettingSheetLine.Warning>(lines.last(), row.key.key)
        }
    }

    // ------------------------------------------------------------------ the "?" sheet

    @Test
    fun `the sheet starts with the default and ends with the warnings`() {
        val lines = aimiSettingSheetLines(
            key = DoubleKey.OApsAIMIMaxSMB,
            unit = null,
            warnings = listOf(ApsStrings.aimi_setting_warning_dose_limit),
        )
        val first = assertIs<AimiSettingSheetLine.Default>(lines.first())
        assertEquals(AimiSettingValue.Number(1.0, 2, CoreUiStrings.units_insulin), first.value)
        assertEquals(AimiSettingSheetLine.Warning(ApsStrings.aimi_setting_warning_dose_limit), lines.last())
        // TPO may rewrite max SMB, so the sheet says so before the warnings.
        assertTrue(AimiSettingSheetLine.Note(ApsStrings.aimi_setting_note_tpo) in lines)
    }

    @Test
    fun `a setting that changes no delivery has no warning`() {
        val lines = aimiSettingSheetLines(key = BooleanKey.OApsAIMIhoneymoon, unit = null, warnings = emptyList())
        assertTrue(lines.none { it is AimiSettingSheetLine.Warning })
        assertTrue(lines.none { it is AimiSettingSheetLine.Note })
    }

    @Test
    fun `defaults carry the key unit or the row unit`() {
        assertEquals(AimiSettingValue.Number(50.0, 0, CoreUiStrings.units_kg), aimiDefaultValue(DoubleKey.OApsAIMIweight, CoreUiStrings.units_kg))
        assertEquals(AimiSettingValue.Number(1.0, 2, CoreUiStrings.units_insulin_rate), aimiDefaultValue(DoubleKey.ApsMaxBasal, null))
        assertEquals(AimiSettingValue.Number(14.0, 0, CoreUiStrings.units_years), aimiDefaultValue(IntKey.OApsAIMINightGrowthAgeYears, CoreUiStrings.units_years))
        assertEquals(AimiSettingValue.Glucose(65.0), aimiDefaultValue(UnitDoubleKey.ApsLgsThreshold, null))
        assertEquals(AimiSettingValue.Switch(true), aimiDefaultValue(BooleanKey.ApsUseSmb, null))
        assertEquals(AimiSettingValue.Text(""), aimiDefaultValue(AimiStringKey.PregnancyDueDateString, null))
        assertEquals(AimiSettingValue.Number(1.6, 2, ApsStrings.aimi_unit_times_profile_basal), aimiDefaultValue(DoubleKey.OApsAIMIMaxMultiplier, ApsStrings.aimi_unit_times_profile_basal))
    }

    // ------------------------------------------------------------------ value in use now

    @Test
    fun `the max multiplier row says it is a factor on the profile basal`() {
        val row = AimiEssentials.rows.single { it.key == DoubleKey.OApsAIMIMaxMultiplier }
        assertEquals(ApsStrings.aimi_unit_times_profile_basal, row.unit)
    }

    @Test
    fun `the current basal multiplier row is a dose limit with its own hint and unit`() {
        // Owner decision (S1 review): the real cap of a temp basal against the profile basal is on the page.
        val row = AimiEssentials.rows.single { it.key == DoubleKey.ApsMaxCurrentBasalMultiplier }
        assertEquals(AimiEssentialsGroup.DOSE_LIMITS, row.group)
        assertEquals(ApsStrings.aimi_essentials_hint_current_basal_multiplier, row.hint)
        assertEquals(ApsStrings.aimi_unit_times_profile_basal, row.unit)
        assertEquals(listOf(ApsStrings.aimi_setting_warning_dose_limit), row.warnings)
    }

    @Test
    fun `the current basal multiplier row shows in use or not in use from the flag`() {
        // Only the display mapping; the rule that sets the flag is tested below.
        val key = DoubleKey.ApsMaxCurrentBasalMultiplier
        assertEquals(AimiInUse(4.0, null, null), aimiInUse(key, 4.0, AimiEssentialsLive(currentBasalMultiplierApplies = true)))
        assertEquals(AimiInUse(4.0, null, null, AimiInUseKind.WHEN_ACTIVE), aimiInUse(key, 4.0, AimiEssentialsLive(currentBasalMultiplierApplies = false)))
        assertEquals(AimiInUse(4.0, null, null), aimiInUse(key, 4.0, AimiEssentialsLive()))
    }

    @Test
    fun `the basal constraint and the page use the multipliers only in the normal mode without T3C brittle mode`() {
        // `applyBasalConstraints` and the Essentials flag both call this rule.
        assertFalse(isSpecialBasalMode(AimiBasalLimitMode.NORMAL, t3cBrittleMode = false))
        assertTrue(isSpecialBasalMode(AimiBasalLimitMode.NORMAL, t3cBrittleMode = true))
        assertTrue(isSpecialBasalMode(AimiBasalLimitMode.MEAL, t3cBrittleMode = false))
        assertTrue(isSpecialBasalMode(AimiBasalLimitMode.AUTODRIVE, t3cBrittleMode = false))
        assertTrue(isSpecialBasalMode(AimiBasalLimitMode.MEAL, t3cBrittleMode = true))
    }

    @Test
    fun `without live data the stored value is in use`() {
        assertEquals(AimiInUse(3.0, null, null), aimiInUse(DoubleKey.ApsMaxBasal, 3.0, AimiEssentialsLive()))
        assertEquals(AimiInUse(1.6, null, null), aimiInUse(DoubleKey.OApsAIMIMaxMultiplier, 1.6, AimiEssentialsLive()))
    }

    @Test
    fun `max SMB and max SMB at high glucose and max IOB claim no value in use`() {
        // The loop picks the SMB limit from both SMB keys on every decision and lowers it (exercise,
        // physio, trajectory), and moves max IOB up or down: the stored value is not "in use now".
        for (key in listOf(DoubleKey.OApsAIMIMaxSMB, DoubleKey.OApsAIMIHighBGMaxSMB, DoubleKey.ApsSmbMaxIob)) {
            assertEquals(AimiInUse(0.8, null, null, AimiInUseKind.PER_DECISION), aimiInUse(key, 0.8, AimiEssentialsLive()), key.key)
            assertTrue(aimiPerDecisionNote(key) != null, key.key)
        }
        // Max SMB at high glucose below max SMB: still no single value claimed.
        assertEquals(AimiInUseKind.PER_DECISION, aimiInUse(DoubleKey.OApsAIMIHighBGMaxSMB, 0.5, AimiEssentialsLive()).kind)
        assertEquals(null, aimiPerDecisionNote(DoubleKey.ApsMaxBasal))
    }

    @Test
    fun `the sheet of a per decision limit says the loop changes it`() {
        val lines = aimiSettingSheetLines(DoubleKey.ApsSmbMaxIob, null, listOf(ApsStrings.aimi_setting_warning_dose_limit))
        assertTrue(AimiSettingSheetLine.Note(ApsStrings.aimi_setting_per_decision_iob) in lines)
    }

    @Test
    fun `max IOB ignores the OpenAPS limit the loop does not gate on`() {
        // The live data has no max IOB at all: a simple-mode or hard-limit value can never show.
        assertEquals(AimiInUse(3.0, null, null, AimiInUseKind.PER_DECISION), aimiInUse(DoubleKey.ApsSmbMaxIob, 3.0, AimiEssentialsLive(basalMode = AimiBasalLimitMode.NORMAL)))
    }

    @Test
    fun `a value TPO wrote gets the TPO badge and the person's own value`() {
        val live = AimiEssentialsLive(
            tpoOverlay = mapOf(DoubleKey.OApsAIMIMaxSMB.key to 0.6),
            tpoBaseline = mapOf(DoubleKey.OApsAIMIMaxSMB.key to 1.0),
        )
        assertEquals(AimiInUse(0.6, AimiInUseBadge.TPO, 1.0, AimiInUseKind.PER_DECISION), aimiInUse(DoubleKey.OApsAIMIMaxSMB, 0.6, live))
    }

    @Test
    fun `a value the person changed during a TPO session has no TPO badge`() {
        val live = AimiEssentialsLive(
            tpoOverlay = mapOf(DoubleKey.OApsAIMIMaxSMB.key to 0.6),
            tpoBaseline = mapOf(DoubleKey.OApsAIMIMaxSMB.key to 1.0),
        )
        assertEquals(AimiInUse(1.2, null, null, AimiInUseKind.PER_DECISION), aimiInUse(DoubleKey.OApsAIMIMaxSMB, 1.2, live))
    }

    @Test
    fun `in normal mode the max basal row gets the constraint result and the others are not in use`() {
        val live = AimiEssentialsLive(basalMode = AimiBasalLimitMode.NORMAL, maxBasalInUse = 2.0, profileMaxDailyBasal = 1.2)
        assertEquals(AimiInUse(2.0, AimiInUseBadge.LIMITED, null), aimiInUse(DoubleKey.ApsMaxBasal, 3.0, live))
        assertEquals(AimiInUse(3.0, null, null), aimiInUse(DoubleKey.ApsMaxBasal, 3.0, live.copy(maxBasalInUse = 3.0)))
        assertEquals(AimiInUse(4.0, null, null, AimiInUseKind.WHEN_ACTIVE), aimiInUse(DoubleKey.meal_modes_MaxBasal, 4.0, live))
        assertEquals(AimiInUse(5.0, null, null, AimiInUseKind.WHEN_ACTIVE), aimiInUse(DoubleKey.autodriveMaxBasal, 5.0, live))
    }

    @Test
    fun `in meal mode the meal max basal is in use and the normal one is not flagged as limited`() {
        // The constraint result is the meal-mode limit (2.5). It is lower than the normal max basal (3.0),
        // which must not get the LIMITED badge: it is simply not in use now.
        val live = AimiEssentialsLive(basalMode = AimiBasalLimitMode.MEAL, maxBasalInUse = 2.5, profileMaxDailyBasal = 1.2)
        assertEquals(AimiInUse(2.5, null, null), aimiInUse(DoubleKey.meal_modes_MaxBasal, 2.5, live))
        assertEquals(AimiInUse(3.0, null, null, AimiInUseKind.WHEN_ACTIVE), aimiInUse(DoubleKey.ApsMaxBasal, 3.0, live))
        assertEquals(AimiInUse(5.0, null, null, AimiInUseKind.WHEN_ACTIVE), aimiInUse(DoubleKey.autodriveMaxBasal, 5.0, live))
    }

    @Test
    fun `with Autodrive engaged the Autodrive max basal is in use`() {
        val live = AimiEssentialsLive(basalMode = AimiBasalLimitMode.AUTODRIVE, maxBasalInUse = 5.0, profileMaxDailyBasal = 1.2)
        assertEquals(AimiInUse(5.0, null, null), aimiInUse(DoubleKey.autodriveMaxBasal, 5.0, live))
        assertEquals(AimiInUse(3.0, null, null, AimiInUseKind.WHEN_ACTIVE), aimiInUse(DoubleKey.ApsMaxBasal, 3.0, live))
    }

    @Test
    fun `a max basal below the profile max daily basal is shown raised in every mode`() {
        val live = AimiEssentialsLive(basalMode = AimiBasalLimitMode.MEAL, maxBasalInUse = 2.0, profileMaxDailyBasal = 2.0)
        // Active row: the loop raised the stored 1.0 to the profile max daily basal 2.0.
        assertEquals(AimiInUse(2.0, AimiInUseBadge.RAISED, null), aimiInUse(DoubleKey.meal_modes_MaxBasal, 1.0, live))
        // Rows of modes that are not on: raised the same way when their mode comes on.
        assertEquals(AimiInUse(2.0, AimiInUseBadge.RAISED, null, AimiInUseKind.WHEN_ACTIVE), aimiInUse(DoubleKey.ApsMaxBasal, 0.5, live))
        assertEquals(AimiInUse(2.0, AimiInUseBadge.RAISED, null, AimiInUseKind.WHEN_ACTIVE), aimiInUse(DoubleKey.autodriveMaxBasal, 1.5, live))
    }

    @Test
    fun `without a known basal mode every max basal row shows its stored value`() {
        val live = AimiEssentialsLive(basalMode = null, maxBasalInUse = 9.0, profileMaxDailyBasal = 2.0)
        assertEquals(AimiInUse(1.0, null, null), aimiInUse(DoubleKey.ApsMaxBasal, 1.0, live))
        assertEquals(AimiInUse(1.0, null, null), aimiInUse(DoubleKey.meal_modes_MaxBasal, 1.0, live))
    }

    @Test
    fun `SMB is shown off when a safety rule keeps it off`() {
        assertEquals(AimiInUse(false, AimiInUseBadge.LIMITED, null), aimiInUse(BooleanKey.ApsUseSmb, true, AimiEssentialsLive(smbAllowed = false)))
        assertEquals(AimiInUse(true, null, null), aimiInUse(BooleanKey.ApsUseSmb, true, AimiEssentialsLive(smbAllowed = true)))
        assertEquals(AimiInUse(false, null, null), aimiInUse(BooleanKey.ApsUseSmb, false, AimiEssentialsLive(smbAllowed = false)))
    }

    // ------------------------------------------------------------------ night growth state

    // Owner decision of step D1 (2026-11-15): an age never stored means an adult, so NGR is off. A
    // stored age or a stored switch keeps the behaviour of before.

    @Test
    fun `no age stored and no switch stored turns night growth off`() {
        assertEquals(NightGrowthState.OFF_NO_AGE, NightGrowthActivation.resolve(enabledPref = null, storedAgeYears = null, t3cBrittleMode = false))
        assertFalse(NightGrowthActivation.isEnabledBySwitchOrAge(enabledPref = null, storedAgeYears = null))
    }

    @Test
    fun `a stored age of 12 turns night growth on`() {
        assertEquals(NightGrowthState.ON_BY_AGE, NightGrowthActivation.resolve(enabledPref = null, storedAgeYears = 12, t3cBrittleMode = false))
    }

    @Test
    fun `a stored age of 30 turns night growth off`() {
        assertEquals(NightGrowthState.OFF_BY_AGE, NightGrowthActivation.resolve(enabledPref = null, storedAgeYears = 30, t3cBrittleMode = false))
    }

    @Test
    fun `a stored switch wins over the age also when no age is stored`() {
        for (age in listOf(null, 12, 30)) {
            assertEquals(NightGrowthState.ON_BY_SWITCH, NightGrowthActivation.resolve(enabledPref = true, storedAgeYears = age, t3cBrittleMode = false), "age=$age")
            assertEquals(NightGrowthState.OFF_BY_SWITCH, NightGrowthActivation.resolve(enabledPref = false, storedAgeYears = age, t3cBrittleMode = false), "age=$age")
        }
    }

    @Test
    fun `T3C brittle mode still turns night growth on`() {
        assertEquals(NightGrowthState.ON_BY_T3C, NightGrowthActivation.resolve(enabledPref = false, storedAgeYears = 10, t3cBrittleMode = true))
        assertEquals(NightGrowthState.ON_BY_T3C, NightGrowthActivation.resolve(enabledPref = null, storedAgeYears = 30, t3cBrittleMode = true))
        assertEquals(NightGrowthState.ON_BY_T3C, NightGrowthActivation.resolve(enabledPref = null, storedAgeYears = null, t3cBrittleMode = true))
    }

    @Test
    fun `the state is on exactly when the loop formula says on`() {
        for (pref in listOf(null, true, false)) for (age in listOf(null, 0, 9, 17, 18, 25)) for (t3c in listOf(true, false)) {
            val bySwitchOrAge = pref ?: (age != null && age < 18)
            assertEquals(bySwitchOrAge || t3c, NightGrowthActivation.resolve(pref, age, t3c).enabled, "pref=$pref age=$age t3c=$t3c")
            assertEquals(bySwitchOrAge, NightGrowthActivation.isEnabledBySwitchOrAge(pref, age))
        }
    }

    @Test
    fun `an age never stored says night growth support is off because the age is not set`() {
        assertEquals(ApsStrings.aimi_essentials_ngr_off_no_age, aimiNightGrowthText(NightGrowthState.OFF_NO_AGE))
        assertEquals(ApsStrings.aimi_essentials_ngr_on_by_age, aimiNightGrowthText(NightGrowthState.ON_BY_AGE))
        assertEquals(ApsStrings.aimi_essentials_ngr_off_by_age, aimiNightGrowthText(NightGrowthState.OFF_BY_AGE))
        assertEquals(ApsStrings.aimi_essentials_ngr_on_by_switch, aimiNightGrowthText(NightGrowthState.ON_BY_SWITCH))
        assertEquals(ApsStrings.aimi_essentials_ngr_off_by_switch, aimiNightGrowthText(NightGrowthState.OFF_BY_SWITCH))
        assertEquals(ApsStrings.aimi_essentials_ngr_on_by_t3c, aimiNightGrowthText(NightGrowthState.ON_BY_T3C))
    }

    @Test
    fun `the night slope threshold takes the adult age when no age is stored`() {
        assertEquals(NightGrowthActivation.AGE_LIMIT_YEARS, NightGrowthActivation.nightSlopeAgeYears(storedAgeYears = null))
        assertEquals(9, NightGrowthActivation.nightSlopeAgeYears(storedAgeYears = 9))
    }

    @Test
    fun `the night growth config keeps the key default age when no age is stored`() {
        // Only a stored switch or T3C can turn NGR on without a stored age. Both must work as before,
        // and the NGR monitor stops at age 18, so the config keeps the key default (14) for them.
        assertEquals(IntKey.OApsAIMINightGrowthAgeYears.defaultValue, NightGrowthActivation.configAgeYears(storedAgeYears = null))
        assertEquals(30, NightGrowthActivation.configAgeYears(storedAgeYears = 30))
    }
}
