package app.aaps.plugins.libre3.reconnect

/** What the driver should do after a session attempt failed. */
enum class Libre3RecoveryAction {

    /** Wait and try the same short reconnect again. */
    RETRY_CACHED_RECONNECT,

    /**
     * Stop trying and ask the user to hold the phone on the sensor again.
     *
     * This is the only way out when the short reconnect keeps failing. Trying a first pairing
     * instead would be refused by a running sensor anyway.
     */
    ASK_FOR_NFC_SCAN,
}

/**
 * How often and how fast the driver tries again, and when it gives up and asks for a new scan.
 *
 * Pure, so it is unit tested without a radio.
 */
object Libre3ReconnectPolicy {

    /** Wait before the first retry. Short, so the next short radio window of the sensor is caught. */
    const val FIRST_RETRY_MS = 3_000L

    /** No wait is ever longer than this. */
    const val MAX_DELAY_MS = 30_000L

    /** After this many failed attempts the user is asked to scan the sensor again. */
    const val MAX_ATTEMPTS = 6

    /**
     * @param attempt how many attempts already failed, starting at 1 for the first failure.
     */
    fun nextDelayMs(attempt: Int): Long {
        if (attempt <= 1) return FIRST_RETRY_MS
        return (FIRST_RETRY_MS * attempt).coerceAtMost(MAX_DELAY_MS)
    }

    /**
     * @param handshakeReached true when the sensor answered the first pairing message. A sensor
     *   that answers and then refuses is telling us that the stored key is no longer good, so
     *   there is no point in trying the same key many more times.
     */
    fun actionAfterFailure(attempt: Int, handshakeReached: Boolean): Libre3RecoveryAction {
        if (handshakeReached) return Libre3RecoveryAction.ASK_FOR_NFC_SCAN
        if (attempt >= MAX_ATTEMPTS) return Libre3RecoveryAction.ASK_FOR_NFC_SCAN
        return Libre3RecoveryAction.RETRY_CACHED_RECONNECT
    }
}
