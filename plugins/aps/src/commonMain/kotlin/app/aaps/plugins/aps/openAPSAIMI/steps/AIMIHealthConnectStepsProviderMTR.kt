package app.aaps.plugins.aps.openAPSAIMI.steps

/**
 * 🏥 AIMI Health Connect Steps Provider - MTR
 *
 * Provides step counts from Health Connect as a fallback source (priority 3,
 * after Wear OS and the phone sensor).
 *
 * Health Connect is an Android-only API: the Android actual implements the real
 * client, other platforms report unavailable so [AIMICompositeStepsProviderMTR]
 * falls through to the next source.
 */
expect class AIMIHealthConnectStepsProviderMTR : AIMIStepsProviderMTR
