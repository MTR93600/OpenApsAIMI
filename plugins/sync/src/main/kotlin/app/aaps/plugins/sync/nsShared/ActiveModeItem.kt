package app.aaps.plugins.sync.nsShared

/**
 * Represents an active mode with remaining time.
 * Used for displaying "What's running now" to parents.
 */
data class ActiveModeItem(
    val mode: ModePreset,
    val startedAt: Long,
    val durationMs: Long
) {
    val remainingMs: Long
        get() = (startedAt + durationMs) - System.currentTimeMillis()
    
    val remainingMinutes: Int
        get() = (remainingMs / 60000).toInt().coerceAtLeast(0)
    
    val isExpired: Boolean
        get() = remainingMs <= 0
    
    val progressPercent: Int
        get() {
            if (durationMs == 0L) return 100
            val elapsed = System.currentTimeMillis() - startedAt
            return ((elapsed.toFloat() / durationMs) * 100).toInt().coerceIn(0, 100)
        }
}
