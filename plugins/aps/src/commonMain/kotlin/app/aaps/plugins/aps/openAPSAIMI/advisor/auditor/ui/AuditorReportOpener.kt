package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import app.aaps.core.interfaces.aps.AimiAuditorReport
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.plugins.aps.ApsStrings
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAuditorNotifier
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * The one implementation of [AimiAuditorReport], shared by Android and iOS.
 *
 * It does three things, in this order:
 * 1. marks the verdict as read, so the Overview chip stops showing it as new;
 * 2. removes the "new insight" notification through [AimiAuditorNotifier] (the only part that is
 *    different on each phone);
 * 3. sends an [EventShowDialog.Ok] with the full report. Both hosts show that event as a dialog.
 *    When there is no fresh verdict, the text falls back to the last one, or says there is none.
 *
 * Desktop excludes this class from its graph: it has no AIMI plugin and no notifier.
 */
@ContributesBinding(AppScope::class)
@SingleIn(AppScope::class)
class AuditorReportOpener @Inject constructor(
    private val auditorStatusStateHolder: AuditorStatusStateHolder,
    private val auditorNotifier: AimiAuditorNotifier,
    private val rxBus: RxBus,
    private val rh: TextResolver,
) : AimiAuditorReport {

    override fun openReport() {
        auditorStatusStateHolder.markAsRead()
        auditorNotifier.cancelNotification()
        val (message, _) = AuditorReportFormatter.buildFullReportMessageWithFallback(rh)
        rxBus.send(
            EventShowDialog.Ok(
                title = rh.gs(ApsStrings.aimi_auditor_report_dialog_title),
                message = message,
            )
        )
    }
}
