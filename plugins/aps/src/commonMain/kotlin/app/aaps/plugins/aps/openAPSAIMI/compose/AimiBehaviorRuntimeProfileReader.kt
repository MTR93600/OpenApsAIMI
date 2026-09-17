package app.aaps.plugins.aps.openAPSAIMI.compose

import app.aaps.core.keys.interfaces.Preferences

internal fun readAimiBehaviorRuntimeProfile(preferences: Preferences): AimiBehaviorRuntimeProfile {
    val draft = readAimiControlCenterDraft(preferences)
    return AimiBehaviorRuntimeProfile(
        protectionLevel = draft.protectionLevel,
        mealCaptureLevel = draft.mealCaptureLevel,
        stabilityLevel = draft.stabilityLevel,
        physioLevel = draft.physioLevel,
        autonomyMode = draft.autonomyMode,
    )
}
