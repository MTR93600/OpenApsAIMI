package app.aaps.plugins.aps.openAPSAIMI.ports

import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState

/*
 * Small ports onto the Android helpers that the AIMI plugin starts, stops or asks.
 *
 * Each interface is as small as the plugin's own use of the class behind it. The class stays in
 * `androidMain` and is bound to its port there. iOS has no implementations yet.
 */

/** Starts and stops the physiological data work (health data sync). */
interface AimiPhysioLifecycle {

    /** Starts the periodic physiological work. Safe to call again. */
    fun start()

    /** Stops the periodic physiological work. */
    fun stop()
}

/** Starts and stops the step count work. */
interface AimiStepsLifecycle {

    /** Starts step count collection. */
    fun start()

    /** Stops step count collection. */
    fun stop()
}

/** Plans and cancels the background training of the AIMI machine learning models. */
interface AimiTrainingScheduler {

    /** Plans the training work. */
    fun schedule()

    /** Cancels the planned training work. */
    fun cancel()
}

/** Where AIMI keeps its files, from best to worst. */
enum class AimiStorageStatus {
    /** The public `Documents/AAPS` folder works. */
    DOCUMENTS_AAPS,

    /** Fallback: the app-owned external folder. */
    APP_SCOPED_EXTERNAL,

    /** Last resort: internal storage only. */
    INTERNAL_ONLY,

    /** Storage failed. This should never happen. */
    ERROR,
}

/** Tells which storage location AIMI uses now, for health logs. */
interface AimiStorageStatusSource {

    /** Returns the status, the folder path (if any) and the last error text (if any). */
    fun getStorageStatus(): Triple<AimiStorageStatus, String?, String?>
}

/** Tells the user when the auditor has a new insight. */
interface AimiAuditorNotifier {

    /**
     * Shows a notification if [uiState] is worth one. It never announces the same verdict twice.
     *
     * @return true if a notification was posted for this verdict.
     */
    fun showInsightAvailable(uiState: AuditorUIState): Boolean

    /** Removes the notification posted by [showInsightAvailable], if it is still shown. */
    fun cancelNotification()
}
