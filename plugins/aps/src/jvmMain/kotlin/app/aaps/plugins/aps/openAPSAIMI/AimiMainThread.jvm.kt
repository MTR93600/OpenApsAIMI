package app.aaps.plugins.aps.openAPSAIMI

/**
 * Plain JVM half of [aimiIsMainThread].
 *
 * A headless JVM draws no user interface, so no thread needs this protection and the honest answer
 * is always `false`. Callers then do their blocking read, which is what they want here.
 */
actual fun aimiIsMainThread(): Boolean = false
