package app.aaps.plugins.aimicontracts

/**
 * One measured value plus whether it may be used.
 *
 * Missing, denied, unsupported and the number 0 are four different facts. The engine must not
 * treat a missing HRV or a denied HealthKit grant as 0.
 *
 * @param T the payload when a number or object was actually captured.
 */
sealed interface TimedValue<out T> {

    /** The captured payload, or null when nothing was measured. Never a silent 0. */
    val valueOrNull: T?

    data class Fresh<T>(
        val value: T,
        val capturedAtEpochMs: Long,
        val ageMs: Long,
    ) : TimedValue<T> {
        override val valueOrNull: T = value
    }

    data class Stale<T>(
        val value: T,
        val capturedAtEpochMs: Long,
        val ageMs: Long,
    ) : TimedValue<T> {
        override val valueOrNull: T = value
    }

    data class Missing(val reason: String) : TimedValue<Nothing> {
        override val valueOrNull: Nothing? = null
    }

    data class Denied(val capability: String) : TimedValue<Nothing> {
        override val valueOrNull: Nothing? = null
    }

    data class Unsupported(val capability: String) : TimedValue<Nothing> {
        override val valueOrNull: Nothing? = null
    }
}
