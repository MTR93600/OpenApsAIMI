package app.aaps.plugins.dexcomoneplus

import app.aaps.plugins.dexcomoneplus.session.OnePlusBondRepairState

/**
 * Callback surface for `OnePlusCgmDriver`.
 */
interface OnePlusGlucoseWatcher {
    fun onWarmup(state: OnePlusWarmupState)
    fun onGlucose(sample: OnePlusGlucoseSample)
    fun onSession(up: Boolean, reason: String?)
    fun onError(message: String, fatal: Boolean)

    /** The state of a stale bond and of its repair changed, see `OnePlusBondRepair`. */
    fun onBondRepair(state: OnePlusBondRepairState) {}
}
