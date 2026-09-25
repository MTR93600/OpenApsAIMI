package app.aaps.plugins.calibration

import app.aaps.core.interfaces.calibration.CalibrationStatus
import app.aaps.core.interfaces.resources.TextResolver

/**
 * Ref `CalibrationDialogViewModel.notYetEffectiveMessage` L254–257 @ `6598201d`.
 *
 * NoSession and WarmUp are unreachable after an accepted entry: `addEntry` already required a
 * running session past warm-up. The sentences are [CalibrationStrings], generated from this
 * module's `strings.xml` (the ref text is `ui/src/main/res/values/strings.xml` L339–340).
 * Nothing here is a literal.
 */
fun notYetEffectiveMessage(status: CalibrationStatus, rh: TextResolver): String? = when (status) {
    is CalibrationStatus.NeedMoreEntries -> rh.gs(CalibrationStrings.cal_saved_need_more_entries, status.entryCount)
    CalibrationStatus.UnsafeFit          -> rh.gs(CalibrationStrings.cal_saved_unsafe_fit)
    else                                  -> null
}
