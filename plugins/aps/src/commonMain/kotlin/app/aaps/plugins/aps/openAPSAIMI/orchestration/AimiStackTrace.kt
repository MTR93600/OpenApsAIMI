package app.aaps.plugins.aps.openAPSAIMI.orchestration

/**
 * Platform stack-trace lines for [Throwable], newest frame first.
 * JVM/Android return real frames; other platforms return an empty list.
 */
internal expect fun aimiThrowableFrames(error: Throwable): List<String>
