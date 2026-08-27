package app.aaps.plugins.aps.openAPSAIMI

import kotlin.time.Clock

/** Wall clock for AIMI commonMain. Do not use java.lang.System here. */
fun aimiWallClockMs(): Long = Clock.System.now().toEpochMilliseconds()
