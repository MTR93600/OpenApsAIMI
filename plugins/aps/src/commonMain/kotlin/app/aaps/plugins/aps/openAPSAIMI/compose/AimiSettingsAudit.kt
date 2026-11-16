package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.DoublePreferenceKey
import app.aaps.core.keys.interfaces.IntPreferenceKey
import app.aaps.core.keys.interfaces.NonPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.StringPreferenceKey
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthActivation
import kotlin.math.abs

/**
 * Who may have written the stored value an audit row reports, in the order the screen uses to
 * decide what to say.
 *
 * The real defect that started this whole manifest was a stale `0.4` sitting where the code
 * expected `0.03`. A screen that just printed "you changed this" for every row would have been
 * wrong on day one: the transient preference overlay rewrites some of these keys from inside a
 * loop tick, with no person involved at all. [AimiSettingsAudit] reads [AimiSettingEntry.writers]
 * and picks ONE of these, with [LOOP] winning over everything else, so a value the loop moved is
 * never shown as something the person did.
 */
/**
 * Who changed a setting, in display order. The localized label comes from
 * `AimiSettingsAuditChangedBy.label(strings)`, so a test can pass its own text.
 */
internal enum class AimiSettingsAuditChangedBy {
    LOOP,
    SLIDER,
    USER,
}

/** A value as the audit screen shows it: a plain text, or a string such as yes / no. */
internal data class AimiValueDescriptor(
    val valueText: String? = null,
    val valueResId: TextRef? = null,
)

/**
 * One AIMI setting whose stored value is not what the app ships.
 *
 * ## The secret rule
 * When [secret] is true, [storedValue] and [defaultValue] are ALWAYS `null`. The row then carries
 * only [isSet]: whether the person has put something in, never what it is. This is the one rule
 * `AimiSettingsAuditTest` exists to hold the line on: an API key or a phone number must never
 * reach a screenshot or a support export through this screen. See `AimiSettingEntry.secret`.
 */
internal data class AimiSettingsAuditRow(
    /** The stored preference key, for logging or a stable list key. Never shown as-is if [titleResId] exists. */
    val keyString: String,
    /**
     * The key's own title, when the key is a [PreferenceKey], and `null` otherwise.
     *
     * On Android this used to be a `@StringRes Int` where `0` meant "no title" — 182 AIMI keys
     * never set it, including `aimi_isf_fusion_max_change_per_tick`, the stale value this whole
     * screen was built to surface. In commonMain [PreferenceKey.title] is a [TextRef], so there
     * is no `0` sentinel to translate; a non-preference key simply has no title and the screen
     * falls back to the storage key, which is also the name the person would quote in a support
     * report.
     */
    val title: TextRef?,
    val level: AimiSettingsLevel,
    val family: AimiBehaviorFamilyId,
    val secret: Boolean,
    val changedBy: AimiSettingsAuditChangedBy,
    /** `null` when [secret] is true. See the class KDoc. */
    val storedValue: AimiValueDescriptor?,
    /** `null` when [secret] is true. See the class KDoc. */
    val defaultValue: AimiValueDescriptor?,
    /** True when a value is stored. For a [secret] row this is the only fact the screen may show. */
    val isSet: Boolean,
    /**
     * True when a value is stored but the app does not read it, so the loop runs on the default.
     *
     * This happens in simple mode for a key marked `defaultedBySM`: `Preferences.get` returns the
     * shipped default and ignores what is stored. Without this the screen would say "current value
     * 8" about a setting the loop reads as 3 — on the one screen whose whole promise is that it
     * tells the truth about what is running.
     */
    val storedValueIgnored: Boolean,
    /**
     * Writes the key's own shipped default back to [Preferences]. For the night growth age and switch
     * it removes the stored value instead, because for them "never stored" is the default behaviour.
     */
    val resetToDefault: (Preferences) -> Unit,
) {
    init {
        // The rule lives in the type, not in the caller. Nulling the two values where the row is
        // built is correct today, but a second construction site tomorrow would only have a comment
        // to stop it, and what leaks here is an API key or a personal phone number. Let the type
        // refuse instead.
        require(!secret || (storedValue == null && defaultValue == null)) {
            "a secret setting must carry no value: $keyString"
        }
    }
}

/** The whole audit: every setting that differs from its shipped default, plus how many were checked. */
internal data class AimiSettingsAuditReport(
    val rows: List<AimiSettingsAuditRow>,
    /** Every setting looked at to build [rows], including the ones that matched their default. */
    val scopedCount: Int,
) {
    val changedCount: Int get() = rows.size
}

/**
 * Builds the settings audit screen's content: for every AIMI setting, compares the value stored in
 * [Preferences] with the value the key itself declares as its shipped default, and keeps only the
 * ones that differ.
 *
 * This is a pure function of [AimiSettingsManifest.entries] and the stored `preferences`: no
 * Compose, no Android view code. The screen only renders what [build] returns, which is what lets
 * `AimiSettingsAuditTest` cover it without a device.
 *
 * Every entry is in scope — including a key no settings screen lets the person reach today
 * ([AimiSettingsManifest.REACHABILITY_DEBT]). Bookkeeping flags such as "the PK/PD wizard has run"
 * and every internal state key ([AimiSettingsManifest.INTERNAL_STATE]: the learned PK/PD state, the
 * loop and meal advisor state) have no entry, so they are
 * never shown here. The reset button here is the one
 * way to correct such a key from inside the app, which is exactly why it belongs on this screen.
 */
internal object AimiSettingsAudit {

    /** Below this, two doubles are the same value, not a changed setting. */
    private const val DOUBLE_SAME_VALUE_EPSILON = 0.0001

    fun build(preferences: Preferences, strings: AimiSettingsAuditStrings): AimiSettingsAuditReport {
        val scoped = AimiSettingsManifest.entries
        val rows = scoped.mapNotNull { entry -> rowFor(preferences, entry, strings) }
        return AimiSettingsAuditReport(rows = rows, scopedCount = scoped.size)
    }

    private fun rowFor(
        preferences: Preferences,
        entry: AimiSettingEntry,
        strings: AimiSettingsAuditStrings,
    ): AimiSettingsAuditRow? {
        val diff = readDiff(preferences, entry.key, strings) ?: return null
        return AimiSettingsAuditRow(
            keyString = entry.key.key,
            title = (entry.key as? PreferenceKey)?.title,
            level = entry.level,
            family = entry.family,
            secret = entry.secret,
            changedBy = changedByOf(entry.writers),
            storedValue = if (entry.secret) null else diff.stored,
            defaultValue = if (entry.secret) null else diff.default,
            isSet = diff.isSet,
            storedValueIgnored = diff.ignored,
            resetToDefault = diff.reset,
        )
    }

    private fun changedByOf(writers: Set<AimiSettingsWriter>): AimiSettingsAuditChangedBy = when {
        AimiSettingsWriter.LOOP in writers   -> AimiSettingsAuditChangedBy.LOOP
        AimiSettingsWriter.SLIDER in writers -> AimiSettingsAuditChangedBy.SLIDER
        else                                 -> AimiSettingsAuditChangedBy.USER
    }

    /**
     * One key's comparison result. Never built for a key whose stored value matches its default, except
     * the keys of [NightGrowthActivation.STORED_OR_NOT_KEYS] (see `storedOrNotDiff`).
     */
    private class Diff(
        val stored: AimiValueDescriptor,
        val default: AimiValueDescriptor,
        val isSet: Boolean,
        /** The stored value is not the one the app reads back. See [AimiSettingsAuditRow.storedValueIgnored]. */
        val ignored: Boolean,
        val reset: (Preferences) -> Unit,
    )

    private fun readDiff(
        preferences: Preferences,
        key: NonPreferenceKey,
        strings: AimiSettingsAuditStrings,
    ): Diff? = when (key) {
        is BooleanPreferenceKey -> {
            val stored = preferences.getIfExists(key)
            if (key in NightGrowthActivation.STORED_OR_NOT_KEYS) {
                storedOrNotDiff(
                    stored = stored,
                    storedDescriptor = booleanDescriptor(stored ?: false, strings),
                    key = key,
                    ignored = preferences.get(key) != stored,
                    strings = strings,
                )
            } else if (stored == null || stored == key.defaultValue) null
            else Diff(
                stored = booleanDescriptor(stored, strings),
                default = booleanDescriptor(key.defaultValue, strings),
                isSet = true,
                ignored = preferences.get(key) != stored,
                reset = { prefs -> prefs.put(key, key.defaultValue) },
            )
        }

        is DoublePreferenceKey  -> {
            val stored = preferences.getIfExists(key)
            if (stored == null || abs(stored - key.defaultValue) < DOUBLE_SAME_VALUE_EPSILON) null
            else Diff(
                stored = AimiValueDescriptor(valueText = doubleText(stored, key.defaultValue)),
                default = AimiValueDescriptor(valueText = doubleText(key.defaultValue, stored)),
                isSet = true,
                ignored = abs(preferences.get(key) - stored) >= DOUBLE_SAME_VALUE_EPSILON,
                reset = { prefs -> prefs.put(key, key.defaultValue) },
            )
        }

        is IntPreferenceKey     -> {
            val stored = preferences.getIfExists(key)
            if (key in NightGrowthActivation.STORED_OR_NOT_KEYS) {
                storedOrNotDiff(
                    stored = stored,
                    storedDescriptor = AimiValueDescriptor(valueText = stored.toString()),
                    key = key,
                    ignored = preferences.get(key) != stored,
                    strings = strings,
                )
            } else if (stored == null || stored == key.defaultValue) null
            else Diff(
                stored = AimiValueDescriptor(valueText = stored.toString()),
                default = AimiValueDescriptor(valueText = key.defaultValue.toString()),
                isSet = true,
                ignored = preferences.get(key) != stored,
                reset = { prefs -> prefs.put(key, key.defaultValue) },
            )
        }

        is StringPreferenceKey  -> {
            val stored = preferences.getIfExists(key)
            if (stored == null || stored == key.defaultValue) null
            else Diff(
                stored = AimiValueDescriptor(valueText = stored),
                default = AimiValueDescriptor(valueText = key.defaultValue),
                isSet = stored.isNotBlank(),
                ignored = preferences.get(key) != stored,
                reset = { prefs -> prefs.put(key, key.defaultValue) },
            )
        }

        else                    -> null
    }

    /**
     * The row of a key where "never stored" is its own state ([NightGrowthActivation.STORED_OR_NOT_KEYS]).
     * A stored value equal to the key default still changes what the loop does (a stored age 14 turns
     * night growth support on, nothing stored means an adult and off). So the row shows for any stored
     * value, the default column says "not set", and the reset removes the stored value instead of
     * writing the key default.
     */
    private fun storedOrNotDiff(
        stored: Any?,
        storedDescriptor: AimiValueDescriptor,
        key: NonPreferenceKey,
        ignored: Boolean,
        strings: AimiSettingsAuditStrings,
    ): Diff? =
        if (stored == null) null
        else Diff(
            stored = storedDescriptor,
            default = AimiValueDescriptor(valueResId = strings.notSet),
            isSet = true,
            ignored = ignored,
            reset = { prefs -> prefs.remove(key) },
        )

    /**
     * Two values differ by more than [DOUBLE_SAME_VALUE_EPSILON] but the shared formatter rounds to
     * two decimals, so a row could show the same text in both columns and look like a bug in the
     * audit. When that happens, both columns fall back to full precision.
     */
    private fun doubleText(value: Double, other: Double): String {
        val formatted = formatAimiNumber(value, unit = null)
        return if (formatted == formatAimiNumber(other, unit = null)) value.toString() else formatted
    }

    private fun booleanDescriptor(value: Boolean, strings: AimiSettingsAuditStrings): AimiValueDescriptor =
        AimiValueDescriptor(valueResId = if (value) strings.yes else strings.no)
}
