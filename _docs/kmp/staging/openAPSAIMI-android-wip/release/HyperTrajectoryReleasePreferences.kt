package app.aaps.plugins.aps.openAPSAIMI.release

import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey

/**
 * Resolved HTR preferences for one tick (TDD-scaled defaults + optional overrides).
 */
data class HyperTrajectoryReleasePreferences(
    val masterEnabled: Boolean,
    val aggressive: Boolean,
    val establishedDevOverrideMgdl: Double,
    val deepDevOverrideMgdl: Double,
) {
    fun establishedDevOrAuto(tdd24hU: Double, highBgBandMgdl: Double): Double {
        if (establishedDevOverrideMgdl > 1.0) {
            return establishedDevOverrideMgdl
        }
        return HyperSeverityClassifier.establishedDevMgdl(tdd24hU, highBgBandMgdl)
    }

    fun deepDevOrAuto(tdd24hU: Double, highBgBandMgdl: Double): Double {
        if (deepDevOverrideMgdl > 1.0) {
            return deepDevOverrideMgdl
        }
        return HyperSeverityClassifier.deepDevMgdl(tdd24hU, highBgBandMgdl)
    }

    companion object {
        fun from(preferences: Preferences): HyperTrajectoryReleasePreferences =
            HyperTrajectoryReleasePreferences(
                masterEnabled = preferences.get(BooleanKey.OApsAIMIautoDriveActive) &&
                    preferences.get(BooleanKey.OApsAIMIHyperTrajectoryRelease),
                aggressive = preferences.get(BooleanKey.OApsAIMIHyperTrajectoryReleaseAggressive),
                establishedDevOverrideMgdl = preferences.get(DoubleKey.OApsAIMIHyperEstablishedDevMgdl),
                deepDevOverrideMgdl = preferences.get(DoubleKey.OApsAIMIHyperDeepDevMgdl),
            )
    }
}
