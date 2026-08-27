package app.aaps.plugins.aps.openAPSAIMI

/**
 * Represents cache-read quality for non-blocking asynchronous data providers.
 *
 * Use this instead of silently returning empty values, so callers can decide
 * whether to keep stale data, fallback to neutral behavior, or skip a feature.
 */
sealed class AsyncDataState<out T> {
    data class Fresh<T>(val value: T, val ageMs: Long = 0L) : AsyncDataState<T>()
    data class Stale<T>(val value: T, val ageMs: Long) : AsyncDataState<T>()
    data class Missing(val reason: String) : AsyncDataState<Nothing>()
}

inline fun <T> AsyncDataState<T>.valueOrNull(): T? = when (this) {
    is AsyncDataState.Fresh -> value
    is AsyncDataState.Stale -> value
    is AsyncDataState.Missing -> null
}
