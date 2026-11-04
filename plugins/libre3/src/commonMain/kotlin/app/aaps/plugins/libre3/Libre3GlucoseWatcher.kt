package app.aaps.plugins.libre3

import app.aaps.plugins.libre3.parse.Libre3PatchStatus

/**
 * Callback surface of [Libre3CgmDriver]. The plugin in `:plugins:source` implements it.
 *
 * ⚠️ ASYNC IMPACT: the real driver calls these methods from its own BLE executor thread.
 * Do not block in [onGlucose]; hand the sample to a coroutine scope instead.
 */
interface Libre3GlucoseWatcher {

    fun onWarmup(state: Libre3WarmupState)
    fun onGlucose(sample: Libre3GlucoseSample)
    fun onSession(up: Boolean, reason: String?)
    fun onError(message: String, fatal: Boolean)

    /**
     * One health message of the sensor: its own fault code and state. Only the sensor alarms read it;
     * it never changes a glucose value. Empty by default, so a watcher that does not need it (the
     * pre-soak slot) has nothing to write.
     */
    fun onPatchStatus(status: Libre3PatchStatus) {}
}
