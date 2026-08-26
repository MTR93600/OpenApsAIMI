package app.aaps.plugins.dexcomoneplus.session

import app.aaps.plugins.dexcomoneplus.OnePlusLog
import app.aaps.plugins.dexcomoneplus.OnePlusLogMarkers

/**
 * Gives the physical sensor an owner.
 *
 * The dual-slot design separates everything that can be separated — store namespace, executor,
 * driver instance — and the one thing it cannot separate is the transmitter itself. Two slots on one
 * MAC means two `BluetoothGatt` clients over a single ACL link, both subscribed to the same Auth and
 * ExtraData characteristics, so each receives the other's notifications and the sensor sees one
 * interleaved stream of two KEKS handshakes. The field log of 2026-08-25 shows both sides dying
 * inside libkeks 1.6 s after the second connect, on a handshake that had just reported
 * `authenticated=1`. It is not a race that sometimes bites; it is certain.
 *
 * Why a process-wide claim and not only a check at the two UI entry points: the entry points are not
 * the only way in. `resumeStoredSession` runs for both slots on every plugin start, and for an
 * install whose two stores already hold the same MAC — the state a phone is left in by the very bug
 * above — that reproduced the collision on every launch with no UI involved. A claim taken where the
 * connection is actually made covers those paths, and the ones not written yet.
 *
 * A slot may re-claim what it already holds: reconnects and repairs inside one slot are normal, and
 * claiming a different MAC releases the slot's previous claim.
 */
object OnePlusMacArbiter {

    private val lock = Any()

    /** Normalised MAC → the slot that owns it. At most one owner per transmitter. */
    private val owners = mutableMapOf<String, String>()

    private fun normalize(mac: String): String = mac.trim().uppercase()

    /**
     * Take ownership of [mac] for [slot].
     *
     * @return true when the slot may connect. false means the other slot holds this transmitter and
     *   the caller must not open a session on it.
     */
    fun claim(mac: String, slot: String): Boolean {
        val key = normalize(mac).takeIf { it.isNotEmpty() } ?: return false
        synchronized(lock) {
            val owner = owners[key]
            if (owner != null && owner != slot) {
                OnePlusLog.w(
                    "${OnePlusLogMarkers.SESSION}: [$slot] MAC claim refused — ***${key.takeLast(5)} " +
                        "is held by [$owner]; two sessions on one sensor corrupt the KEKS handshake",
                )
                return false
            }
            // A slot owns one transmitter at a time: moving to another releases the old one.
            owners.entries.removeAll { it.value == slot && it.key != key }
            owners[key] = slot
            return true
        }
    }

    /** Give up whatever [slot] holds. Safe when it holds nothing. */
    fun release(slot: String) {
        synchronized(lock) {
            owners.entries.removeAll { it.value == slot }
        }
    }

    /** Which slot owns [mac], or null when nobody does. */
    fun ownerOf(mac: String): String? {
        val key = normalize(mac).takeIf { it.isNotEmpty() } ?: return null
        return synchronized(lock) { owners[key] }
    }

    /** Test hook: forget every claim. */
    fun reset() {
        synchronized(lock) { owners.clear() }
    }
}
