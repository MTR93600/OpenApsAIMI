package app.aaps.core.objects.extensions

import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.utils.HardLimits

/**
 * True when this insulin configuration looks like an inhaled insulin (Afrezza).
 *
 * `ICfg` stores no inhaled flag, and `InsulinType.fromPeak` only matches the exact factory peak,
 * so an edited Afrezza peak loses its identity. This is the fork's heuristic replacement: the
 * peak must sit in the inhaled peak band AND the DIA must be too short to be a valid injected
 * insulin while still being a valid inhaled DIA.
 *
 * The DIA half of the test is what makes this unambiguous. The inhaled peak band (20-45 min) does
 * overlap the injected band (35-120 min), so peak alone would also match Lyumjev (peak 45 min).
 * The injected DIA floor is 4.0 h across every age type, and the inhaled DIA band is 1.5-4.0 h, so
 * `dia < 4.0` can never hold for an insulin that passed injected-insulin validation.
 *
 * There is deliberately no "exact factory peak" shortcut here. `InsulinType.fromPeak` matches the
 * peak alone, and `OREF_FREE_PEAK` lets a user set any peak inside `HardLimits.LIMIT_PEAK`
 * (35..120 min), which contains Afrezza's 40 min. A shortcut would therefore call a normal
 * injected insulin with a 40 min peak and a 6 h DIA "inhaled". Peak and DIA must always agree.
 *
 * Same shape as the checks already used by `ProfileSealed.validateSemantic`,
 * `InsulinManagementViewModel.resolveEditorTemplate` and `DataHandlerMobile.findAfrezzaIcfg`.
 * This helper is for display and button gating only — it is NOT used to validate hard limits.
 */
fun ICfg.looksInhaled(): Boolean {
    val injectedDiaFloor = HardLimits.LIMIT_DIA.values.minOf { it.start }
    return peak in HardLimits.LIMIT_PEAK_INHALED &&
        dia < injectedDiaFloor &&
        dia in HardLimits.LIMIT_DIA_INHALED.getValue(HardLimits.AgeType.ADULT)
}
