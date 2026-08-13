package app.aaps.plugins.source.dexcomoneplus

/**
 * Callback surface for [OnePlusCgmDriver].
 */
interface OnePlusGlucoseWatcher {
    fun onWarmup(state: OnePlusWarmupState)
    fun onGlucose(sample: OnePlusGlucoseSample)
    fun onSession(up: Boolean, reason: String?)
    fun onError(message: String, fatal: Boolean)
}
