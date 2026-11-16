package app.aaps.plugins.aps.openAPSAIMI.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthActivation
import app.aaps.plugins.aps.openAPSAIMI.NightGrowthState
import app.aaps.plugins.aps.openAPSAIMI.context.ui.AimiPreferenceInfoScreen
import kotlinx.coroutines.delay

/** How often an open page reads the night growth state again. */
private const val NIGHT_GROWTH_STATE_REFRESH_MS = 1_000L

/**
 * Night growth support (NGR) on or off now, and why, kept up to date while the page is open.
 *
 * The state depends on whether the age and the switch are stored at all, not only on their values
 * (step D1). Observing the values is not enough: `Preferences.observe` gives a `StateFlow`, which
 * drops a value equal to the last one. With no age stored the page already shows 14 (the key
 * default), so storing 14 sends nothing, but the loop now reads a stored age under 18 and turns NGR
 * on. There is no flow that tells when a key becomes stored, so the page reads
 * [NightGrowthActivation.read] again every second. It is three reads of values kept in memory.
 */
@Composable
internal fun rememberNightGrowthState(preferences: Preferences): State<NightGrowthState> =
    produceState(initialValue = NightGrowthActivation.read(preferences), preferences) {
        while (true) {
            value = NightGrowthActivation.read(preferences)
            delay(NIGHT_GROWTH_STATE_REFRESH_MS)
        }
    }

/**
 * The page behind the "Night growth support now" row of the night growth settings. It says in full
 * what the loop does and why. The switch row of that page shows the same state since step A1
 * ([AimiNightGrowthSwitch]); the age row still shows the key default (14) when no age is stored,
 * while the loop then takes the person for an adult and keeps NGR off.
 */
@Composable
internal fun AimiNightGrowthStateScreen(preferences: Preferences, onBack: () -> Unit) {
    val state by rememberNightGrowthState(preferences)
    AimiPreferenceInfoScreen(
        title = ApsStrings.aimi_ngr_state_title,
        message = aimiNightGrowthText(state),
        onBack = onBack,
    )
}
