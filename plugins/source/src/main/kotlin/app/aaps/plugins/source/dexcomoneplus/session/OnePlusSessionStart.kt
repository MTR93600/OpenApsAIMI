package app.aaps.plugins.source.dexcomoneplus.session

/**
 * Native session start from pairing code (Q11 A / A6.5).
 * Pure validation helpers live here; BLE side effects stay in [OnePlusBleSession].
 *
 * Short-code rules align with xDrip `TxIdHelper.isValidShortPairingCode` at A1 pin
 * (GPL-3.0) — see [OnePlusInvalidShortPairingCodes].
 */
object OnePlusSessionStart {

    /** Dexcom ONE+ / G7 applicator codes are 4 decimal digits in xDrip Direct UX. */
    const val EXPECTED_CODE_LENGTH: Int = 4

    fun normalizePairingCode(raw: String): String = raw.trim()

    fun isValidPairingCode(raw: String): Boolean {
        val code = normalizePairingCode(raw)
        if (code.length != EXPECTED_CODE_LENGTH) return false
        if (!code.all { it.isDigit() }) return false
        return code !in OnePlusInvalidShortPairingCodes.codes
    }

    fun validationError(raw: String): String? {
        if (isValidPairingCode(raw)) return null
        val code = normalizePairingCode(raw)
        if (code.length != EXPECTED_CODE_LENGTH || !code.all { it.isDigit() }) {
            return "ONEPLUS_PAIR_CODE_INVALID: expect $EXPECTED_CODE_LENGTH digits"
        }
        return "ONEPLUS_PAIR_CODE_INVALID: reserved / invalid short code"
    }
}
