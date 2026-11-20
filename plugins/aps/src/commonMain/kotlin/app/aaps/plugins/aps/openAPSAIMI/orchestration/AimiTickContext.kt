package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.AutosensResult
import app.aaps.core.interfaces.aps.CurrentTemp
import app.aaps.core.interfaces.aps.GlucoseStatusAIMI
import app.aaps.core.interfaces.aps.IobTotal
import app.aaps.core.interfaces.aps.MealData
import app.aaps.core.interfaces.aps.OapsProfileAimi
import app.aaps.core.interfaces.ui.UiInteraction

/**
 * Entry bundle for one [app.aaps.plugins.aps.openAPSAIMI.DetermineBasalaimiSMB2.determine_basal] invocation (Phase 2).
 *
 * **Aliasing:** properties reference the same objects as the original method parameters (not deep copies).
 * In-place updates to [profile] or [mealData] are visible through this context.
 *
 * **flatBGsDetected:** the loop body may **shadow** this with a local `val flatBGsDetected` after delta override;
 * downstream code must use that local value, not [flatBGsDetected] from context, once the shadow exists.
 */
data class AimiTickContext(
    val glucoseStatus: GlucoseStatusAIMI,
    val currentTemp: CurrentTemp,
    val iobDataArray: Array<IobTotal>,
    /** Insulin-activity array rebuilt on the LEARNED PK/PD DIA/peak, used only to shape the prediction
     *  curves (predictCurves). Null when the learner gives no valid DIA/peak → the prediction falls back
     *  to [iobDataArray] (static profile kinetics). Always on since step P3. */
    val pkpdIobDataArray: Array<IobTotal>? = null,
    /** TAP-D + TAP-G effective kinetics from [InsulinKineticsAuthority] (plugin invoke). */
    val effectiveDiaHours: Double? = null,
    val effectivePeakMinutes: Double? = null,
    /**
     * The whole kinetics view that dosed, resolved once by the plugin. Null for callers that pass
     * only the numbers above (replay engine, simulator).
     */
    val kineticsView: InsulinKineticsView? = null,
    val profile: OapsProfileAimi,
    val autosensData: AutosensResult,
    val mealData: MealData,
    val microBolusAllowed: Boolean,
    val currentTime: Long,
    val flatBGsDetected: Boolean,
    val dynIsfMode: Boolean,
    val uiInteraction: UiInteraction,
    val extraDebug: String,
    /**
     * `profile.current_basal` when the tick started. The tick changes that field in place
     * (gestational autopilot, thyroid, early PK/PD), so a safe hold must not read it after an error
     * as "the profile basal". Null for callers that build a context without it.
     */
    val basalAtTickStart: Double? = null,
)
