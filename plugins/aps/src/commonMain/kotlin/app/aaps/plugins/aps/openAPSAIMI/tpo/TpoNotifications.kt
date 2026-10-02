package app.aaps.plugins.aps.openAPSAIMI.tpo

/**
 * Tells the user that a TPO protection session has started or ended.
 *
 * `TpoOrchestrator` calls this from inside a loop tick. Telling the user is pure platform work:
 * Android builds a `NotificationChannel`, a `NotificationCompat` notification and a `PendingIntent`
 * that opens the AIMI screen. Only that hand-off is behind this interface; the rule that decides
 * *when* to tell the user stays in the orchestrator.
 *
 * The Android side is `TpoNotificationManager`, bound with `@ContributesBinding(AppScope::class)`.
 * There is no iOS implementation yet, so a TPO session on iOS would start and end without telling
 * the user. That is a real gap, not something to paper over with a no-op binding.
 *
 * The orchestrator itself is still in `androidMain`: it builds a `TpoLlmValidator`, which needs
 * `org.json` and `AiCoachingService`. This interface removes the notification part of that block,
 * so the orchestrator can move once the LLM validator does.
 */
interface TpoNotifications {

    /** A new session has just been applied. */
    fun showSessionStarted(session: TpoSessionDocument)

    /** The active session has just gone away, for [reason]. */
    fun showSessionEnded(reason: TpoEndReason)
}

/** Why an active TPO session stopped. */
enum class TpoEndReason {
    EXPIRED,
    MANUAL_REVERT,
    SUPERSEDED,
}
