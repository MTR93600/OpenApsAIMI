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

    /**
     * What is stored, and whether it could be read. [DashPodStateManager] reads with this.
     *
     * A store that can fail to read (the iOS Keychain before the first unlock after a reboot, or
     * bytes that do not decode) must give [DashPodStateRead.Unreadable] then, never
     * [DashPodStateRead.Empty]: "no pod" and "a pod I cannot read now" lead to very different
     * actions (see [DashPodStateManager.isStateKnown]). The default is for a store that cannot fail
     * to read, as the memory stores of the tests.
     */
    fun read(): DashPodStateRead = load()?.let { DashPodStateRead.Loaded(it) } ?: DashPodStateRead.Empty

    /** Persists [state], replacing any previously stored state. */
    fun save(state: DashPodState)

    /** Deletes any stored state. */
    fun clear()
}

/** The result of [DashPodStateStore.read]. */
sealed interface DashPodStateRead {

    /** A stored state, read and decoded. */
    data class Loaded(val state: DashPodState) : DashPodStateRead

    /** Nothing is stored: no pod is paired. */
    data object Empty : DashPodStateRead

    /**
     * Something may be stored, but it could not be read or decoded now. The stored bytes must stay
     * as they are: they may hold the key of a pod that runs on the body.
     */
    data class Unreadable(val reason: String) : DashPodStateRead
}

/**
 * A write of the pod state while the stored state is not known ([DashPodStateManager.isStateKnown]
 * is false). The write is refused, so the stored item (maybe with the key of a running pod) is never
 * replaced by a state that was not read from it.
 */
class DashPodStateUnknownException(reason: String) :
    IllegalStateException("The stored pod state cannot be read ($reason). Nothing is written and nothing is sent to the pod.")
