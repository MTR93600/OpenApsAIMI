package app.aaps.pump.omnipod.dashctl.session

/**
 * Persistence seam for [DashPodState].
 *
 * The Android original used Gson + SharedPreferences. Storage format and location
 * are a platform concern on purpose: the platform implementation owns the
 * serialization (JSON, protobuf, ...) and must protect the stored LTK at rest,
 * because the LTK is long-lived key material.
 */
interface DashPodStateStore {

    /** Returns the stored state, or null when nothing was stored yet. */
    fun load(): DashPodState?

    /** Persists [state], replacing any previously stored state. */
    fun save(state: DashPodState)

    /** Deletes any stored state. */
    fun clear()
}
