package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.ui.compose.navigation.setNavigationRoute
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAuditorNotifier
import app.aaps.plugins.aps.openAPSAIMI.tpo.OPENAPS_AIMI_PLUGIN_ROUTE
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import platform.Foundation.NSError
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationInterruptionLevel.UNNotificationInterruptionLevelActive
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNUserNotificationCenter
import platform.darwin.DISPATCH_TIME_NOW
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait
import platform.darwin.dispatch_time

/**
 * The rule for when the auditor may tell the user, without any Apple call in it, so a test can
 * run it.
 *
 * Same rule as Android's `AuditorNotificationManager`:
 * 1. The state must ask for a notification and must be active (it has insights).
 * 2. There must be a verdict to show.
 * 3. The same verdict is never announced twice. A verdict is told apart by its cache time stamp.
 * 4. The verdict is only marked as announced after the notification was really posted. If the post
 *    fails, the next call may try again.
 *
 * @param post posts the notification and answers whether it was really handed to the system.
 */
internal class AuditorAnnouncer(private val post: (AuditorUIState) -> Boolean) {

    private val lastAnnouncedVerdictMs = AtomicReference(0L)

    /**
     * @param verdictTimestampMs the time stamp of the verdict in the cache, or null if none.
     * @return true if a notification was posted for this verdict.
     */
    fun announce(uiState: AuditorUIState, verdictTimestampMs: Long?): Boolean {
        if (!uiState.shouldNotify || !uiState.isActive()) return false
        if (verdictTimestampMs == null) return false
        if (verdictTimestampMs == lastAnnouncedVerdictMs.value) return false

        val posted = post(uiState)
        if (posted) lastAnnouncedVerdictMs.value = verdictTimestampMs
        return posted
    }
}

/**
 * The iOS side of [AimiAuditorNotifier]: puts a notification in Notification Center when the
 * auditor has a new verdict.
 *
 * The shape follows `IosTpoNotifications`: permission is asked once and lazily, the notification
 * goes through `UNUserNotificationCenter.addNotificationRequest`, and a tap opens the AIMI
 * settings screen through `setNavigationRoute`. The words come from the shared
 * `AuditorReportFormatter`, so both phones say the same thing.
 *
 * ## The return value
 *
 * `showInsightAvailable` answers `true` only when the system took the request without an error
 * and the user has allowed notifications. To know that, the call waits (at most a few seconds) for
 * the two Apple callbacks. It is called from a background collector, never from the main thread.
 * Two cases answer `false` and are not marked as announced:
 * - Permission is denied, or the user has not answered the first prompt yet. Because the prompt is
 *   shown on the first call, the very first verdict may not be announced. The next verdict will.
 * - The system reports an error.
 *
 * ## What is different from Android
 *
 * Android also has an in-app notification with a "view" button that opens a report dialog. iOS
 * has only the system notification. A tap opens the AIMI settings screen, not the report, because
 * no report screen is wired for iOS yet. The full report text is in the notification body, so the
 * content is still there.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class IosAuditorNotifier @Inject constructor(
    private val aapsLogger: AAPSLogger,
    private val rh: TextResolver,
) : AimiAuditorNotifier {

    /** Resolved on first use. See `IosTpoNotifications.center` for why it is lazy. */
    private val center by lazy { UNUserNotificationCenter.currentNotificationCenter() }

    /** Asked once, on first use. */
    private val authorizationRequested = AtomicInt(0)

    private val announcer = AuditorAnnouncer(::post)

    override fun showInsightAvailable(uiState: AuditorUIState): Boolean =
        announcer.announce(uiState, AuditorVerdictCache.resolveForDisplay()?.cached?.timestamp)

    private fun post(uiState: AuditorUIState): Boolean {
        requestAuthorizationOnce()
        if (!isAuthorized()) {
            aapsLogger.warn(LTag.NOTIFICATION, "AIMI Auditor notification skipped, notifications are not allowed (yet)")
            return false
        }
        val title = rh.gs(
            if (uiState.type == AuditorUIState.StateType.WARNING) ApsStrings.aimi_auditor_notification_title_warning
            else ApsStrings.aimi_auditor_notification_title_ready,
        )
        // The body starts with the short summary, so the first banner line says the same as Android's.
        val body = AuditorReportFormatter.buildNotificationBigText(rh, uiState)
        val content = UNMutableNotificationContent().apply {
            setTitle(title)
            setBody(body)
            setInterruptionLevel(UNNotificationInterruptionLevelActive)
            setNavigationRoute(OPENAPS_AIMI_PLUGIN_ROUTE)
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier = IDENTIFIER,
            content = content,
            trigger = null,
        )
        val failure = AtomicReference<NSError?>(null)
        val done = dispatch_semaphore_create(0)
        center.addNotificationRequest(request) { error ->
            failure.value = error
            dispatch_semaphore_signal(done)
        }
        if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, WAIT_NS)) != 0L) {
            aapsLogger.warn(LTag.NOTIFICATION, "AIMI Auditor notification: the system did not answer in time")
            return false
        }
        val error = failure.value
        if (error != null) {
            aapsLogger.error(LTag.NOTIFICATION, "Cannot post AIMI Auditor notification: $error")
            return false
        }
        return true
    }

    private fun requestAuthorizationOnce() {
        if (!authorizationRequested.compareAndSet(0, 1)) return
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { granted, error ->
            if (error != null) aapsLogger.error(LTag.NOTIFICATION, "AIMI Auditor notification permission failed: $error")
            else aapsLogger.debug(LTag.NOTIFICATION, "AIMI Auditor notification permission granted=$granted")
        }
    }

    /** True when the user has allowed alerts. `false` also covers "not asked yet" and "no answer in time". */
    private fun isAuthorized(): Boolean {
        val status = AtomicReference<Long?>(null)
        val done = dispatch_semaphore_create(0)
        center.getNotificationSettingsWithCompletionHandler { settings ->
            status.value = settings?.authorizationStatus
            dispatch_semaphore_signal(done)
        }
        if (dispatch_semaphore_wait(done, dispatch_time(DISPATCH_TIME_NOW, WAIT_NS)) != 0L) return false
        return when (status.value) {
            UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> true
            else -> false
        }
    }

    internal companion object {

        /**
         * One identifier, so a new verdict replaces the old notification. The prefix is not `aaps-`,
         * for the reason written on `IosTpoNotifications.identifierOf`.
         */
        const val IDENTIFIER = "aimi-auditor-insight"

        /** How long to wait for one Apple callback: 5 seconds. */
        private const val WAIT_NS = 5_000_000_000L
    }
}
