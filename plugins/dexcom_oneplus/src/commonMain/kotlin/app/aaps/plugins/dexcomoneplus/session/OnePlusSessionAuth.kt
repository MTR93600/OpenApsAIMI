package app.aaps.plugins.dexcomoneplus.session

/**
 * Session authentication / J-PAKE key exchange (A6.4).
 *
 * Production path: [OnePlusSessionAuthKeks] via `:plugins:libkeks` (GPL-3 xDrip pin). Both are
 * shared code: the library was ported to Kotlin Multiplatform, so the handshake compiles and runs
 * on Apple too, and `OnePlusCgmDriverReal` around it is shared as well. What differs is OS
 * bonding: CoreBluetooth has no API to start one. iOS pairs by itself on the next encrypted
 * operation and shows its own dialog, and the iOS GATT client gives that operation a pairing
 * window so the person can accept it (see `OnePlusGattClientIos.createBond`). The unbonded four
 * digit pairing has not been tried on an iPhone with a real sensor yet.
 * **No proprietary Dexcom secrets or hard-coded session keys belong in this module.**
 */
interface OnePlusSessionAuth {
    /**
     * Runs auth after GATT connect. Returns success/failure without throwing for expected auth fails.
     *
     * @param savedSharedKey optional 16-byte key from a prior session (libkeks channel 2 /
     * Juggluco reconnect short path). When present and Android-bonded, Round1–3 are skipped.
     */
    fun authenticate(pairingCode: String, savedSharedKey: ByteArray? = null): AuthResult
}

data class AuthResult(
    val ok: Boolean,
    val message: String? = null,
    /** 16-byte KEKS shared key when [ok] — persist for reconnect short-auth. */
    val sharedKey: ByteArray? = null,
    /**
     * Juggluco-style: wipe persisted shared key before the next attempt
     * (short-auth rejected / bond failure / missing certs).
     */
    val invalidateSharedKey: Boolean = false,
    /**
     * The sensor itself said no: its AuthStatus was not "authenticated", or its challenge did not
     * match ours (wrong code or stale key). Counted by [OnePlusAuthRefusalPolicy], because refusals
     * in a row lock a sensor. A lost link, a timeout or a bond problem is not a refusal.
     */
    val refusedBySensor: Boolean = false,
    /** The refused attempt used a saved shared key (short auth), not the full pairing with the code. */
    val refusedSavedKey: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AuthResult) return false
        return ok == other.ok &&
            message == other.message &&
            sharedKey.contentEquals(other.sharedKey) &&
            invalidateSharedKey == other.invalidateSharedKey &&
            refusedBySensor == other.refusedBySensor &&
            refusedSavedKey == other.refusedSavedKey
    }

    override fun hashCode(): Int {
        var result = ok.hashCode()
        result = 31 * result + (message?.hashCode() ?: 0)
        result = 31 * result + (sharedKey?.contentHashCode() ?: 0)
        result = 31 * result + invalidateSharedKey.hashCode()
        result = 31 * result + refusedBySensor.hashCode()
        result = 31 * result + refusedSavedKey.hashCode()
        return result
    }
}

/**
 * Always fails — used in unit tests without Bluetooth / libkeks.
 */
class OnePlusSessionAuthUnimplemented : OnePlusSessionAuth {
    override fun authenticate(pairingCode: String, savedSharedKey: ByteArray?): AuthResult {
        val redactedLen = pairingCode.length
        return AuthResult(
            ok = false,
            message = "ONEPLUS_AUTH_UNIMPLEMENTED: J-PAKE not wired (codeLen=$redactedLen)",
        )
    }
}
