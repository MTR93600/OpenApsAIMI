package app.aaps.plugins.dexcomoneplus

/**
 * Callback surface for [OnePlusCgmDriver] (mirror EversenseWatcher pattern).
 */
interface OnePlusGlucoseWatcher {
    fun onWarmup(state: OnePlusWarmupState)
    fun onGlucose(sample: OnePlusGlucoseSample)
    fun onSession(up: Boolean, reason: String?)
    fun onError(message: String, fatal: Boolean)
}
