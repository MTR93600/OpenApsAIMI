package app.aaps.plugins.main.general.dashboard.glass

/**
 * UI state for the ported Glass Loop Dashboard screen. Every glucose/insulin/ISF value is already
 * converted to the user's display unit and formatted as a String by the ViewModel — this screen never
 * sees a raw mg/dL Double, so it cannot reintroduce a unit-conversion bug.
 */
data class GlassLoopDashboardState(
    val isLoading: Boolean = false,
    val lastRunTime: String = "",
    val requestedSmbText: String = "--",
    val glucoseText: String = "--",
    val delta5mText: String = "--",
    val delta5mIsPositive: Boolean = true,
    val shortAvgDeltaText: String = "--",
    val shortAvgDeltaIsPositive: Boolean = true,
    val longAvgDeltaText: String = "--",
    val longAvgDeltaIsPositive: Boolean = true,
    val iobText: String = "--",
    val targetBgText: String = "--",
    val maxIobProfileText: String = "--",
    val maxSmbProfileText: String = "--",
    val isfText: String = "--",
    val stableMinutesText: String = "--",
    val tdd7DaysPerHourText: String = "--",
    val tirLow1hText: String = "--",
    val tirLow24hText: String = "--",
    val steps5mText: String = "--",
    val hourOfDay: Int = 0,
    val isWeekend: Boolean = false,
    val buildVersionText: String = "",
    val hasTrajectory: Boolean = false,
    val trajectoryHybridText: String = "--",
    val trajectoryIobText: String = "--",
    val trajectoryCobText: String = "--",
    val hasPhysio: Boolean = false,
    val physioModeText: String = "--",
    val physioIntentText: String = "--",
    val physioThermalText: String = "--",
    val physioUpdatedText: String = "",
    val hasMlTraining: Boolean = false,
    val mlLastTrainedText: String = "--",
    val mlSampleCountText: String = "--",
    val mlCircuitOpen: Boolean = false,
    val basalModelFileText: String = "--",
    val smbModelFileText: String = "--",
)
