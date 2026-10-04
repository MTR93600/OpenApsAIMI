package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.keys.AimiLongKey
import kotlin.math.max

/** Glycémie (mg/dL) under which a declared meal still refuses its prebolus. */
internal const val SEVERE_HYPO_MEAL_OVERRIDE_MGDL: Double = 54.0

/**
 * Static prebolus latch. The tick instance can be recreated between cycles, so this
 * lives beside the decision, not on the Android instance. Preference reads and writes
 * happen in the getters and setters, at the call.
 */
internal object LegacyPrebolusMemory {
    var lastSmbTimestampMem: Long = 0L
    val firedAt: HashMap<String, Long> = HashMap()
    val missAlertedAt: HashMap<String, Long> = HashMap()
    var lastLegacyPrebolusTimestampMem: Long = 0L
    var lastCarryRetryFireMillis: Long = 0L
    var pendingUnitMem: Float = 0.0f
    var pendingExpiryMem: Long = 0L

    /** Delay after a fire before a missing delivery can be concluded. */
    const val CONFIRM_DELAY_MS = 1_500_000L

    /** How long a requested prebolus stays "in flight". */
    const val DELIVERY_TTL_MS = 30 * 60 * 1000L

    /** Carry-forward retry cooldown. Shorter than a normal loop tick. */
    const val CARRY_RETRY_COOLDOWN_MS = 90 * 1000L

    fun reset() {
        firedAt.clear()
        missAlertedAt.clear()
        lastSmbTimestampMem = 0L
        lastLegacyPrebolusTimestampMem = 0L
        lastCarryRetryFireMillis = 0L
        pendingUnitMem = 0f
        pendingExpiryMem = 0L
    }

    fun lastSmbMillis(preferences: Preferences): Long =
        max(lastSmbTimestampMem, preferences.get(AimiLongKey.LastPrebolusTime))

    fun setLastSmbMillis(preferences: Preferences, value: Long) {
        lastSmbTimestampMem = value
        preferences.put(AimiLongKey.LastPrebolusTime, value)
    }

    fun lastLegacyPrebolusMillis(preferences: Preferences, nowMs: Long): Long {
        val stored = preferences.get(AimiLongKey.LastLegacyPrebolusTime)
        val validStored = if (stored > 0L && (nowMs - stored) < 24 * 3_600_000L) stored else 0L
        return max(lastLegacyPrebolusTimestampMem, validStored)
    }

    fun setLastLegacyPrebolusMillis(preferences: Preferences, value: Long) {
        lastLegacyPrebolusTimestampMem = value
        preferences.put(AimiLongKey.LastLegacyPrebolusTime, value)
    }

    /** 0 when nothing is in flight. A positive memory wins; otherwise the milli-unit preference is read. */
    fun pendingUnit(preferences: Preferences): Float {
        if (pendingUnitMem > 0.0f) return pendingUnitMem
        return preferences.get(AimiLongKey.PendingLegacyPrebolusUnitMilli) / 1000.0f
    }

    fun setPendingUnit(preferences: Preferences, value: Float) {
        pendingUnitMem = value
        preferences.put(AimiLongKey.PendingLegacyPrebolusUnitMilli, (value * 1000).toLong())
    }

    fun pendingExpiry(preferences: Preferences): Long {
        if (pendingExpiryMem > 0L) return pendingExpiryMem
        return preferences.get(AimiLongKey.PendingLegacyPrebolusExpiry)
    }

    fun setPendingExpiry(preferences: Preferences, value: Long) {
        pendingExpiryMem = value
        preferences.put(AimiLongKey.PendingLegacyPrebolusExpiry, value)
    }
}

internal fun legacyPrebolusLatchBlocks(firedAtMs: Long?, nowMs: Long, runtimeMin: Long): Boolean {
    if (firedAtMs == null) return false
    val activationWindowMs = runtimeMin.coerceAtLeast(0) * 60_000L + 90_000L
    return (nowMs - firedAtMs) < activationWindowMs
}

internal fun legacyPrebolusMissedDelivery(
    firedAtMs: Long?,
    lastSmbConfirmedMs: Long?,
    nowMs: Long,
    runtimeMin: Long,
): Boolean {
    if (firedAtMs == null) return false
    if (!legacyPrebolusLatchBlocks(firedAtMs, nowMs, runtimeMin)) return false
    if (nowMs - firedAtMs < LegacyPrebolusMemory.CONFIRM_DELAY_MS) return false
    return lastSmbConfirmedMs == null || lastSmbConfirmedMs < firedAtMs
}
