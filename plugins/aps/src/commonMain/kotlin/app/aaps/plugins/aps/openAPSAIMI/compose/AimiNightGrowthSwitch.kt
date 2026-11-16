package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.BooleanPreferenceKey
import app.aaps.core.keys.interfaces.PreferenceItem
import app.aaps.core.keys.interfaces.PreferenceKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.VisibilityContext
import app.aaps.core.ui.compose.LocalPreferences
import app.aaps.core.ui.compose.preference.PreferenceTitleWithSyncBadge
import app.aaps.core.ui.compose.preference.SwitchPreference
import app.aaps.core.ui.compose.preference.calculatePreferenceVisibility
import app.aaps.core.ui.compose.preference.withRow
import app.aaps.core.ui.compose.stringResource
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthActivation
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthState
import kotlinx.coroutines.delay

/** How often the open switch row reads the night growth state again. */
private const val NIGHT_GROWTH_SWITCH_REFRESH_MS = 1_000L

/**
 * The night growth support (NGR) switch row of the night growth page (step A1, owner decision of
 * 2026-11-15, display only).
 *
 * The row shows the state the loop uses ([NightGrowthActivation.read]): on when the switch is stored
 * on, when the switch was never set and the stored age is under 18, or when T3C brittle mode is on.
 * Before step A1 it showed the stored switch, or its key default (off) when nothing was stored, so a
 * child whose age was set but whose switch was never touched saw "off" while the loop ran NGR.
 *
 * A tap stores the opposite of what the row shows on screen, as an explicit value. A stored switch
 * wins over the age, as before. Only T3C brittle mode can keep NGR on after a tap that stored off, and the line
 * under the row says so. The reset of the settings audit still removes the stored value, so the age
 * decides again.
 *
 * This changes no dosing: the loop reads the same keys in the same way. Only what the row shows and
 * what a tap stores when nothing was stored are new (before, a tap from the shown "off" stored on).
 */
internal object AimiNightGrowthSwitch {

    /** True for the night growth switch key, also when it is already wrapped by [row]. */
    fun isSwitchRow(item: PreferenceItem): Boolean =
        item is PreferenceKey && item.key == BooleanKey.OApsAIMINightGrowthEnabled.key

    /**
     * The switch key as the page lists it: the same key, drawn by [AimiNightGrowthSwitchRow]. The shared
     * renderer honours it (`BooleanKeyWithRow`), so the row is the same on the AIMI page and on the page
     * the app search opens. Search, the manifest and the tree tests still see the plain key string.
     */
    fun row(key: BooleanPreferenceKey): BooleanPreferenceKey =
        key.withRow { visibilityContext ->
            AimiNightGrowthSwitchRow(key = key, preferences = LocalPreferences.current, visibilityContext = visibilityContext)
        }

    /** What the row shows: whether the loop runs NGR now. */
    fun shown(preferences: Preferences): Boolean = NightGrowthActivation.read(preferences).enabled

    /**
     * A tap on the row that showed [shownOnScreen]: stores the opposite, as an explicit switch value.
     *
     * It uses the state on screen, not a fresh read: the screen reads the state again only once a
     * second, and the age row is on the same page. A fresh read just after the age changed could store
     * the value the person saw, and that value would then also win over the age.
     */
    fun tap(preferences: Preferences, shownOnScreen: Boolean) {
        preferences.put(BooleanKey.OApsAIMINightGrowthEnabled, !shownOnScreen)
    }

    /** The one line under the row that says why it shows what it shows. */
    fun reason(state: NightGrowthState): TextRef = when (state) {
        NightGrowthState.ON_BY_SWITCH, NightGrowthState.OFF_BY_SWITCH -> ApsStrings.aimi_ngr_switch_reason_hand
        NightGrowthState.ON_BY_AGE, NightGrowthState.OFF_BY_AGE       -> ApsStrings.aimi_ngr_switch_reason_age
        NightGrowthState.OFF_NO_AGE                                   -> ApsStrings.aimi_ngr_switch_reason_no_age
        NightGrowthState.ON_BY_T3C                                    -> ApsStrings.aimi_ngr_switch_reason_t3c
    }
}

/**
 * Draws the NGR switch row with the state of [AimiNightGrowthSwitch]. It is hidden or disabled in
 * the same cases as the shared switch row (`calculatePreferenceVisibility`).
 *
 * The state is read again every second while the row is shown, and right after a tap. Observing the
 * switch is not enough: storing the age, or storing the value that is already shown, changes the
 * state without a new value of the switch (see `rememberNightGrowthState`).
 */
@Composable
internal fun AimiNightGrowthSwitchRow(
    key: BooleanPreferenceKey,
    preferences: Preferences,
    visibilityContext: VisibilityContext?,
) {
    val visibility = calculatePreferenceVisibility(
        preferenceKey = key,
        engineeringModeOnly = key.engineeringModeOnly,
        visibilityContext = visibilityContext,
    )
    if (!visibility.visible) return

    var state by remember(preferences) { mutableStateOf(NightGrowthActivation.read(preferences)) }
    LaunchedEffect(preferences) {
        while (true) {
            state = NightGrowthActivation.read(preferences)
            delay(NIGHT_GROWTH_SWITCH_REFRESH_MS)
        }
    }
    AimiNightGrowthSwitchContent(
        key = key,
        state = state,
        enabled = visibility.enabled,
        onTap = { shownOnScreen ->
            AimiNightGrowthSwitch.tap(preferences, shownOnScreen)
            state = NightGrowthActivation.read(preferences)
        },
    )
}

/**
 * The row itself, without reading the store: the switch shows [state], the line says why. A tap
 * calls [onTap] with the state that was on screen (`state.enabled`).
 */
@Composable
internal fun AimiNightGrowthSwitchContent(
    key: BooleanPreferenceKey,
    state: NightGrowthState,
    enabled: Boolean,
    onTap: (shownOnScreen: Boolean) -> Unit,
) {
    SwitchPreference(
        value = state.enabled,
        onValueChange = { onTap(state.enabled) },
        title = { PreferenceTitleWithSyncBadge(key.title, key) },
        summary = { Text(stringResource(AimiNightGrowthSwitch.reason(state))) },
        enabled = enabled,
    )
}
