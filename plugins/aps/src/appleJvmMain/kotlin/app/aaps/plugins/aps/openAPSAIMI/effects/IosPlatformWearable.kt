package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.plugins.aps.openAPSAIMI.physio.HealthContextSnapshot

/**
 * JVM returns the empty scaffold. iOS reads HealthKit and, on failure, the same empty snapshot.
 * The tick stays off: [app.aaps.plugins.aimiengine.AimiCommonEngineSwitch] defaults to false.
 */
internal expect fun iosPlatformWearable(log: MutableList<String>): HealthContextSnapshot
