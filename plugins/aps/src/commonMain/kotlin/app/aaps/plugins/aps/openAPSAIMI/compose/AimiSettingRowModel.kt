package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.UnitType
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.UnitDoublePreferenceKey
import app.aaps.core.ui.compose.preference.sliderDecimalsAndStep
import app.aaps.core.ui.compose.unitLabel
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthState
import app.aaps.plugins.aps.openAPSAIMI.tpo.TpoPreferenceKeys
import kotlin.math.abs
import kotlin.math.max

/*
 * The plain model behind the AIMI setting row (`AimiSettingRow`). No Compose here, so the rules can
 * be tested in commonTest and run on iOS too.
 */

/** A setting value, ready to be shown with its unit. */
internal sealed interface AimiSettingValue {

    /** A number with the decimals to show and its unit label (null when it has none). */
    data class Number(val value: Double, val decimals: Int, val unit: TextRef?) : AimiSettingValue

    /** A glucose value in mg/dL. The screen shows it in the units of the person. */
    data class Glucose(val mgdl: Double) : AimiSettingValue

    /** An on / off switch. */
    data class Switch(val on: Boolean) : AimiSettingValue

    /** A text value. Empty means "not set". */
    data class Text(val text: String) : AimiSettingValue
}

/**
 * Decimals used to show a number of [key]. Insulin amounts and rates get two decimals, so a value
 * such as 0.25 U is not shown as 0.3 U. Other numbers use the same rule as the shared slider.
 */
private fun displayDecimals(key: DoublePreferenceKey): Int =
    if (key.unitType == UnitType.INSULIN || key.unitType == UnitType.INSULIN_RATE) 2
    else sliderDecimalsAndStep(key.unitType, key.min, key.max).first

/**
 * The value [raw] of [key] as a displayable value. The unit comes from the key; [unit] is used only
 * when the key itself has no unit (body weight in kg, for example).
 */
internal fun aimiValueOf(key: PreferenceKey, raw: Any, unit: TextRef?): AimiSettingValue =
    when (key) {
        is UnitDoublePreferenceKey -> AimiSettingValue.Glucose((raw as Number).toDouble())
        is DoublePreferenceKey     -> AimiSettingValue.Number((raw as Number).toDouble(), displayDecimals(key), key.unitType.unitLabel() ?: unit)
        is IntPreferenceKey        -> AimiSettingValue.Number((raw as Number).toDouble(), 0, key.unitType.unitLabel() ?: unit)
        is BooleanPreferenceKey    -> AimiSettingValue.Switch(raw as Boolean)
        else                       -> AimiSettingValue.Text(raw.toString())
    }

/** The shipped default of [key], or null for a key with no stored value (a link row). */
internal fun aimiDefaultValue(key: PreferenceKey, unit: TextRef?): AimiSettingValue? =
    aimiDefaultRaw(key)?.let { aimiValueOf(key, it, unit) }

private fun aimiDefaultRaw(key: PreferenceKey): Any? =
    when (key) {
        is UnitDoublePreferenceKey -> key.defaultValue
        is DoublePreferenceKey     -> key.defaultValue
        is IntPreferenceKey        -> key.defaultValue
        is BooleanPreferenceKey    -> key.defaultValue
        is StringPreferenceKey     -> key.defaultValue
        else                       -> null
    }

/** One line of the "?" sheet of a setting row. */
internal sealed interface AimiSettingSheetLine {

    /** Always first: "Default: X". */
    data class Default(val value: AimiSettingValue) : AimiSettingSheetLine

    /** What the setting does, from the key's own summary. */
    data class Detail(val text: TextRef) : AimiSettingSheetLine

    /** A fact the person should know, for example that AIMI may change the value by itself. */
    data class Note(val text: TextRef) : AimiSettingSheetLine

    /** Always last: what can go wrong for insulin delivery. */
    data class Warning(val text: TextRef) : AimiSettingSheetLine
}

/**
 * The lines of the "?" sheet of [key], in order: the default, the key's summary, a note when the loop
 * sets its own limit for each decision from the value, a note when TPO may rewrite the value, then
 * [warnings]. Pass the warnings only when the key can change insulin delivery; a setting without
 * warnings ends with its last note.
 */
internal fun aimiSettingSheetLines(key: PreferenceKey, unit: TextRef?, warnings: List<TextRef>): List<AimiSettingSheetLine> =
    buildList {
        aimiDefaultValue(key, unit)?.let { add(AimiSettingSheetLine.Default(it)) }
        key.summary?.let { add(AimiSettingSheetLine.Detail(it)) }
        aimiPerDecisionNote(key)?.let { add(AimiSettingSheetLine.Note(it)) }
        if (TpoPreferenceKeys.isWhitelisted(key.key)) add(AimiSettingSheetLine.Note(ApsStrings.aimi_setting_note_tpo))
        warnings.forEach { add(AimiSettingSheetLine.Warning(it)) }
    }

/**
 * For a limit the loop changes on every decision, the text that says so; null for any other key.
 *
 * - Max SMB and max SMB at high glucose: each decision picks its own SMB limit from both values (by
 *   glucose and how fast it rises), and then can lower it, for example during exercise.
 * - Max IOB: the loop starts each decision from the stored value and can move it up (night growth
 *   support) or down (trajectory) for that decision.
 *
 * So no single value is "in use now" for these keys, and the page does not claim one.
 */
internal fun aimiPerDecisionNote(key: PreferenceKey): TextRef? =
    when (key) {
        DoubleKey.OApsAIMIMaxSMB, DoubleKey.OApsAIMIHighBGMaxSMB -> ApsStrings.aimi_setting_per_decision_smb
        DoubleKey.ApsSmbMaxIob                                    -> ApsStrings.aimi_setting_per_decision_iob
        else                                                      -> null
    }

/**
 * Which max basal key the loop uses now. It is the same choice `OpenAPSAIMIPlugin.applyBasalConstraints`
 * makes, and the plugin takes it from the same function.
 */
internal enum class AimiBasalLimitMode {

    /** No meal mode and no Autodrive: [DoubleKey.ApsMaxBasal]. T3C brittle mode also uses it. */
    NORMAL,

    /** A meal mode, or a meal from the context: [DoubleKey.meal_modes_MaxBasal]. */
    MEAL,

    /** Autodrive is engaged (day time, no meal or sport mode): [DoubleKey.autodriveMaxBasal]. */
    AUTODRIVE,
}

/**
 * True in a meal mode, with Autodrive engaged, or in T3C brittle mode. Then the AIMI basal constraint
 * (`OpenAPSAIMIPlugin.applyBasalConstraints`) caps with the max basal of [mode] only, not with the
 * current or daily basal multipliers. The Essentials page uses the same rule to say whether
 * [DoubleKey.ApsMaxCurrentBasalMultiplier] is in use now, so the two cannot disagree.
 */
internal fun isSpecialBasalMode(mode: AimiBasalLimitMode, t3cBrittleMode: Boolean): Boolean =
    mode != AimiBasalLimitMode.NORMAL || t3cBrittleMode

/**
 * What the loop uses right now, read once when the Essentials page opens.
 *
 * Every field is optional: null means "not known", and the page then shows the stored value.
 *
 * @param basalMode which max basal key the loop uses now; null when AIMI sets no max basal of its own
 *   (AIMI not enabled, or no glucose data)
 * @param maxBasalInUse the max basal after every constraint, as the loop asks for it. It belongs to the
 *   key of [basalMode] only, so the result depends on the mode
 * @param profileMaxDailyBasal the highest basal rate of the profile: the loop never uses a lower max
 *   basal key value than this, in every mode
 * @param currentBasalMultiplierApplies true when the loop caps a temporary basal with
 *   [DoubleKey.ApsMaxCurrentBasalMultiplier] now (normal basal mode, no T3C brittle mode); false when
 *   it does not (meal mode, Autodrive engaged, T3C brittle mode); null when not known. See
 *   [isSpecialBasalMode]. When true, the page shows the stored factor: that is the outer cap. Inside
 *   the decision the loop also multiplies it by the physio basal factor, so with a factor below 1 the
 *   real cap of that decision is lower than the value shown (the hint says the loop can lower it)
 * @param smbAllowed false when a constraint keeps SMB off (open loop, objectives, the SMB switch)
 * @param targetLowMgdl low end of the profile target now
 * @param targetHighMgdl high end of the profile target now
 * @param tpoOverlay the values an active TPO session wrote, by storage key
 * @param tpoBaseline the person's own values before that TPO session, by storage key
 */
internal data class AimiEssentialsLive(
    val basalMode: AimiBasalLimitMode? = null,
    val maxBasalInUse: Double? = null,
    val profileMaxDailyBasal: Double? = null,
    val currentBasalMultiplierApplies: Boolean? = null,
    val smbAllowed: Boolean? = null,
    val targetLowMgdl: Double? = null,
    val targetHighMgdl: Double? = null,
    val tpoOverlay: Map<String, Any> = emptyMap(),
    val tpoBaseline: Map<String, Any> = emptyMap(),
)

/** Why the value in use is not the person's own stored value. */
internal enum class AimiInUseBadge {

    /** An active TPO session wrote this value for a while. The person's value comes back after. */
    TPO,

    /** A safety limit of the loop gives a lower value (or keeps SMB off). */
    LIMITED,

    /** The loop raises a max basal that is below the highest basal rate of the profile. */
    RAISED,
}

/** What the value line of a row says. */
internal enum class AimiInUseKind {

    /** The loop uses [AimiInUse.value] now. */
    NOW,

    /** The key belongs to a basal mode that is not on now; [AimiInUse.value] is used when it is. */
    WHEN_ACTIVE,

    /** The loop sets its own limit for each decision from this key; see [aimiPerDecisionNote]. */
    PER_DECISION,
}

/**
 * The value in use, and why it differs from the stored value.
 *
 * @param value the value the loop uses (raw: a Double or a Boolean). For [AimiInUseKind.PER_DECISION]
 *   it is the stored value, and the row does not show it as "in use"
 * @param badge why it differs, or null when the stored value is used as it is
 * @param userValue the person's own value when TPO replaced it, else null
 * @param kind what the value line says
 */
internal data class AimiInUse(val value: Any, val badge: AimiInUseBadge?, val userValue: Any?, val kind: AimiInUseKind = AimiInUseKind.NOW)

/**
 * The value in use for [key], whose stored value is [stored]. The result depends on [live].
 *
 * Only facts the loop really uses are shown:
 * - the values an active TPO session wrote;
 * - the three max basal keys: only the key of the active basal mode is in use now, with the result of
 *   the same constraint call the loop makes. Each of them is raised to the highest basal rate of the
 *   profile when it is lower, also when its mode is not on now;
 * - the current basal multiplier: in use only when the loop caps a temporary basal with it now;
 * - the SMB switch after the same constraint call the loop makes;
 * - max SMB, max SMB at high glucose and max IOB: the loop changes them on every decision, so no value
 *   is claimed as in use ([AimiInUseKind.PER_DECISION]).
 *
 * Any other key is used as it is stored. Without live data, the stored value is shown.
 */
internal fun aimiInUse(key: PreferenceKey, stored: Any, live: AimiEssentialsLive): AimiInUse {
    val tpoValue = live.tpoOverlay[key.key]
    val byTpo = tpoValue != null && sameValue(tpoValue, stored)
    if (aimiPerDecisionNote(key) != null) {
        return if (byTpo) AimiInUse(stored, AimiInUseBadge.TPO, live.tpoBaseline[key.key], AimiInUseKind.PER_DECISION)
        else AimiInUse(stored, null, null, AimiInUseKind.PER_DECISION)
    }
    if (byTpo) return AimiInUse(stored, AimiInUseBadge.TPO, live.tpoBaseline[key.key])
    return when (key) {
        DoubleKey.ApsMaxBasal                  -> maxBasalInUse(AimiBasalLimitMode.NORMAL, stored, live)
        DoubleKey.meal_modes_MaxBasal          -> maxBasalInUse(AimiBasalLimitMode.MEAL, stored, live)
        DoubleKey.autodriveMaxBasal            -> maxBasalInUse(AimiBasalLimitMode.AUTODRIVE, stored, live)
        DoubleKey.ApsMaxCurrentBasalMultiplier -> live.currentBasalMultiplierApplies?.let { applies ->
            AimiInUse(stored, null, null, if (applies) AimiInUseKind.NOW else AimiInUseKind.WHEN_ACTIVE)
        }

        BooleanKey.ApsUseSmb                   -> live.smbAllowed?.let { allowed ->
            val on = stored as Boolean
            if (on && !allowed) AimiInUse(false, AimiInUseBadge.LIMITED, null) else AimiInUse(on, null, null)
        }

        else                                   -> null
    } ?: AimiInUse(stored, null, null)
}

/**
 * A max basal key of [rowMode]. The loop raises the key value to the profile max daily basal when it
 * is lower, in every mode. Only the key of the active mode gets the constraint result, which may also
 * lower it (basal multipliers, pump, hard limits). Null when the mode is not known.
 */
private fun maxBasalInUse(rowMode: AimiBasalLimitMode, stored: Any, live: AimiEssentialsLive): AimiInUse? {
    val activeMode = live.basalMode ?: return null
    val storedValue = (stored as Number).toDouble()
    val raised = live.profileMaxDailyBasal?.let { max(storedValue, it) } ?: storedValue
    if (rowMode != activeMode) {
        return AimiInUse(raised, if (raised > storedValue + VALUE_EPSILON) AimiInUseBadge.RAISED else null, null, AimiInUseKind.WHEN_ACTIVE)
    }
    val inUse = live.maxBasalInUse ?: raised
    val badge = when {
        inUse > storedValue + VALUE_EPSILON -> AimiInUseBadge.RAISED
        inUse < storedValue - VALUE_EPSILON -> AimiInUseBadge.LIMITED
        else                                -> null
    }
    return AimiInUse(inUse, badge, null)
}

/**
 * The night growth line of the age row. Since step D1 an age never stored turns NGR off (AIMI takes
 * the person for an adult), and the line says that the age is not set.
 */
internal fun aimiNightGrowthText(state: NightGrowthState): TextRef =
    when (state) {
        NightGrowthState.ON_BY_SWITCH  -> ApsStrings.aimi_essentials_ngr_on_by_switch
        NightGrowthState.OFF_BY_SWITCH -> ApsStrings.aimi_essentials_ngr_off_by_switch
        NightGrowthState.ON_BY_AGE     -> ApsStrings.aimi_essentials_ngr_on_by_age
        NightGrowthState.OFF_BY_AGE    -> ApsStrings.aimi_essentials_ngr_off_by_age
        NightGrowthState.OFF_NO_AGE    -> ApsStrings.aimi_essentials_ngr_off_no_age
        NightGrowthState.ON_BY_T3C     -> ApsStrings.aimi_essentials_ngr_on_by_t3c
    }

/**
 * The hint under the profile targets on the Essentials page. It says "tap" only when the platform
 * can open the profile screen ([canOpenProfile]); otherwise the targets are shown read only.
 */
internal fun aimiTargetsHint(canOpenProfile: Boolean): TextRef =
    if (canOpenProfile) ApsStrings.aimi_essentials_targets_hint_open else ApsStrings.aimi_essentials_targets_hint

private fun sameValue(a: Any, b: Any): Boolean =
    if (a is Number && b is Number) abs(a.toDouble() - b.toDouble()) < VALUE_EPSILON else a == b

private const val VALUE_EPSILON = 1e-9
