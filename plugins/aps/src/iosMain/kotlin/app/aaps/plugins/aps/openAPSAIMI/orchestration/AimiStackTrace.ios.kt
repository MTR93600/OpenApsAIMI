package app.aaps.plugins.aps.openAPSAIMI.orchestration

/**
 * iOS/Kotlin-Native: no stack-trace API. Recovery logs the error name and
 * message only; frame-level location is unavailable.
 */
internal actual fun aimiThrowableFrames(error: Throwable): List<String> = emptyList()
