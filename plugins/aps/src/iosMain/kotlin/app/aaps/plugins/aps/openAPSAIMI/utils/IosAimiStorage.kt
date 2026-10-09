package app.aaps.plugins.aps.openAPSAIMI.utils

/**
 * iOS [AimiStorage].
 *
 * Backed by [DirectoryAimiStorage] over the app's Documents/AAPS directory (see [iosTickAimiRoot]).
 * The relative file names are the shared ones (`tpo/tpo_session.json`, `basal_adaptive_weights.json`,
 * ...); only the root directory is platform-specific. Same write guarantees as Android: a failed
 * write answers `false` and never throws, so AIMI logging can never take down a dosing tick.
 */
fun iosAimiStorage(): AimiStorage = DirectoryAimiStorage(iosTickAimiRoot(), aimiLocalFiles())
