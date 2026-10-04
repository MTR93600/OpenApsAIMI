package app.aaps.plugins.aps.openAPSAIMI.effects

/**
 * One optional read inside [buildRbtExtendedSignals].
 *
 * [Ready] is a completed read, including a legitimate null (auditor cache miss).
 * [Failed] is an [Exception] from the read. The dose path still uses [valueOrNull],
 * which is null, the same fallback the clinical reference returns from its silent catch.
 */
internal sealed class OptionalSignal<out T> {
    data class Ready<T>(val value: T) : OptionalSignal<T>()
    data class Failed(
        val source: String,
        val errorType: String,
        val message: String?,
    ) : OptionalSignal<Nothing>()
}

internal fun <T> OptionalSignal<T>.valueOrNull(): T? = when (this) {
    is OptionalSignal.Ready -> value
    is OptionalSignal.Failed -> null
}

/**
 * Reads [block]. An [Exception] becomes [OptionalSignal.Failed], a console line, and a null
 * fallback via [valueOrNull]. An [Error] propagates: the clinical reference only catches [Exception].
 */
internal fun <T> readRbtOptional(
    source: String,
    consoleLog: MutableList<String>,
    failureLine: (errorType: String, message: String?) -> String = { errorType, message ->
        "RBT $source failed ($errorType): ${message.orEmpty()} — value null"
    },
    block: () -> T,
): OptionalSignal<T> {
    return try {
        OptionalSignal.Ready(block())
    } catch (e: Exception) {
        val errorType = e::class.simpleName ?: "Exception"
        val message = e.message
        consoleLog.add(failureLine(errorType, message))
        OptionalSignal.Failed(source, errorType, message)
    }
}
