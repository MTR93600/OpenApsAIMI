package app.aaps.plugins.aps.openAPSAIMI.tpo

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.plugins.aps.ApsStrings

/**
 * Builds the words of the two TPO notifications.
 *
 * This is shared code on purpose. The wording used to live in the Android notification class, so an
 * iOS implementation would have had to write the same sentences a second time and they would have
 * drifted. Here both phones say the same thing by construction, and a `commonTest` can check it -
 * that test also runs on the iOS simulator.
 *
 * Every line comes from [TextResolver], so nothing here is English written into code and the user's
 * AAPS language override is honoured on both platforms.
 */
internal object TpoNotificationText {

    /**
     * What the user reads when a session has just been applied, or null when there is nothing to
     * say (the session has already gone away between the decision and this call).
     *
     * [nowMs] is passed in rather than read here, so the remaining minutes are the ones the caller
     * decided on and a test can fix them.
     */
    fun sessionStarted(rh: TextResolver, session: TpoSessionDocument, nowMs: Long): TpoNotification? {
        val ui = TpoUiSupport.buildActiveSessionUi(session, nowMs) ?: return null
        val text = rh.gs(
            ApsStrings.aimi_tpo_notification_started_text,
            rh.gs(ui.packTitle),
            ui.remainingMinutes,
            ui.changedKeyCount,
        )
        // Whole translated lines joined by newlines. Each line is its own resource, so a translator
        // controls the words and the order inside a line; only the line break is added here.
        val longText = buildString {
            append(text)
            append('\n')
            append(rh.gs(ApsStrings.aimi_tpo_notification_started_tier, ui.tierLabel))
            if (ui.deltaPreviewLines.isNotEmpty()) {
                append('\n')
                ui.deltaPreviewLines.forEach { line ->
                    append(line)
                    append('\n')
                }
            }
            if (ui.extraChangeCount > 0) {
                append(rh.gs(ApsStrings.aimi_tpo_extra_changes, ui.extraChangeCount))
            }
        }.trim()
        return TpoNotification(
            kind = TpoNotificationKind.SESSION_STARTED,
            title = rh.gs(ApsStrings.aimi_tpo_notification_started_title),
            text = text,
            longText = longText,
        )
    }

    /** What the user reads when the session has gone away, for [reason]. */
    fun sessionEnded(rh: TextResolver, reason: TpoEndReason): TpoNotification {
        val text = when (reason) {
            TpoEndReason.EXPIRED       -> rh.gs(ApsStrings.aimi_tpo_notification_ended_expired)
            TpoEndReason.MANUAL_REVERT -> rh.gs(ApsStrings.aimi_tpo_notification_ended_manual)
            TpoEndReason.SUPERSEDED    -> rh.gs(ApsStrings.aimi_tpo_notification_ended_superseded)
        }
        return TpoNotification(
            kind = TpoNotificationKind.SESSION_ENDED,
            title = rh.gs(ApsStrings.aimi_tpo_notification_ended_title),
            text = text,
            // One sentence, so there is nothing longer to expand into.
            longText = text,
        )
    }
}
