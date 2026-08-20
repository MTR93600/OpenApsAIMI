package app.aaps.plugins.libre3.parse

/**
 * Joins the two pieces of a glucose message before anything tries to read it.
 *
 * Ported from LibreCRKit `DataPlane/DataPlaneDecoder.swift` (`DataPlaneNotificationAssembler`) at
 * pin `a86b92f`.
 *
 * A glucose message does not arrive in one piece. The sensor sends 15 bytes, then 20 bytes. The
 * tag that protects the message is only complete once both are put together, so checking the
 * first piece on its own would always fail.
 *
 * Only the glucose channel is split this way. Every other channel arrives whole.
 *
 * ⚠️ ASYNC IMPACT: the Bluetooth thread calls [feed]. The held piece is guarded, and [reset] must
 * be called whenever a link drops, so a piece from an old session is never joined to a new one.
 */
class Libre3GlucoseFrameAssembler {

    private val lock = Any()
    private var heldPrefix: ByteArray? = null

    /**
     * @param isGlucoseChannel true when the bytes came from the glucose channel.
     * @return the whole message, or null when this was the first piece and the second is awaited.
     */
    fun feed(fragment: ByteArray, isGlucoseChannel: Boolean): ByteArray? {
        if (!isGlucoseChannel) return fragment
        synchronized(lock) {
            heldPrefix?.let { prefix ->
                heldPrefix = null
                return prefix + fragment
            }
            if (fragment.size == PREFIX_SIZE) {
                heldPrefix = fragment
                return null
            }
            return fragment
        }
    }

    /** Throws away a piece that is waiting. Called whenever a link drops. */
    fun reset() {
        synchronized(lock) { heldPrefix = null }
    }

    /** True while a first piece is being held. Used by the log and by tests. */
    val isWaitingForSecondPiece: Boolean get() = synchronized(lock) { heldPrefix != null }

    companion object {

        /** The size that marks a first piece. */
        const val PREFIX_SIZE = 15
    }
}
