package app.aaps.plugins.source.dexcomoneplus.identity

/**
 * Juggluco-aligned ADV name / candidate matching for ONE+ / G7 family.
 *
 * Juggluco `isG7`: local name starts with `DX` + (`CM`|`02`|`01`).
 * Once a device name is known from a successful session, prefer exact match.
 *
 * Do **not** treat marketing names like `Dexcom65` / `DexcomONE` as ONE+ candidates —
 * field logs showed pre-connect latching on a G6-style `Dexcom65` while the real
 * transmitter was `DX02aS`.
 */
object OnePlusAdvCandidate {

    fun isG7FamilyName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val n = name.trim()
        if (n.length < 4) return false
        if (!n.startsWith("DX", ignoreCase = true)) return false
        val rest = n.substring(2, 4)
        return rest.equals("CM", ignoreCase = true) ||
            rest.equals("02", ignoreCase = true) ||
            rest.equals("01", ignoreCase = true)
    }

    /**
     * Open-scan name filter: G7/ONE+ family only (`DX02` / `DX01` / `DXCM`).
     * Intentionally excludes `Dex*` marketing names (G6 / phone companions).
     */
    fun nameMatchesSoft(name: String?): Boolean = isG7FamilyName(name)

    /**
     * Whether this ADV should be offered / preferred for [session].
     * - Exact stored ADV name wins (reconnect sticky).
     * - Stored MAC wins even if name is missing/odd.
     * - Else G7-family name only.
     */
    fun isCandidate(
        name: String?,
        address: String?,
        session: OnePlusStoredSession?,
    ): Boolean {
        val storedName = session?.lastDeviceName
        if (!storedName.isNullOrBlank() && name != null && name.equals(storedName, ignoreCase = true)) {
            return true
        }
        val storedMac = session?.lastMac
        if (!storedMac.isNullOrBlank() && address != null &&
            address.equals(storedMac, ignoreCase = true)
        ) {
            return true
        }
        if (!isG7FamilyName(name)) return false
        val serial = session?.identity?.serial
        if (!serial.isNullOrBlank() && !name.isNullOrBlank()) {
            // Prefer ADV that embeds serial characters when present; do not hard-reject
            // when serial encoding in ADV is opaque (G7 often uses short DX02* names).
            if (name.contains(serial, ignoreCase = true)) return true
        }
        return true
    }

    fun rankScore(
        name: String?,
        address: String?,
        rssi: Int,
        session: OnePlusStoredSession?,
    ): Int {
        var score = rssi
        if (!session?.lastDeviceName.isNullOrBlank() &&
            name.equals(session?.lastDeviceName, ignoreCase = true)
        ) {
            score += 10_000
        }
        if (!session?.lastMac.isNullOrBlank() &&
            address.equals(session?.lastMac, ignoreCase = true)
        ) {
            score += 5_000
        }
        val serial = session?.identity?.serial
        if (!serial.isNullOrBlank() && name?.contains(serial, ignoreCase = true) == true) {
            score += 1_000
        }
        if (isG7FamilyName(name)) score += 100
        return score
    }
}
