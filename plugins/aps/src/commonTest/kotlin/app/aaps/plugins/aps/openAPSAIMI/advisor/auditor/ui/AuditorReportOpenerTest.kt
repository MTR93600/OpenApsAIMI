package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui

import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.resources.formatTemplate
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.Event
import app.aaps.core.interfaces.rx.events.EventShowDialog
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.core.keys.interfaces.TextRef.Companion.withArgs
import app.aaps.plugins.aps.ApsStringsValues
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorStatusTracker
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.AuditorVerdictCache
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import app.aaps.plugins.aps.openAPSAIMI.ports.AimiAuditorNotifier
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlin.reflect.KClass
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shared "open the auditor report" call, used by the Glass Tools tile on both phones and by the
 * Android Overview chip.
 *
 * It lives in `commonTest`, so it also runs under `iosSimulatorArm64Test`. The resolver reads the
 * generated English table, so the title and the text are the real words a user sees.
 */
class AuditorReportOpenerTest {

    private val holder = AuditorStatusStateHolder()
    private val notifier = RecordingNotifier()
    private val bus = RecordingBus()
    private val sut = AuditorReportOpener(holder, notifier, bus, EnglishApsText)

    @BeforeTest
    fun setUp() {
        AuditorVerdictCache.clear()
        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFFLINE_NO_NETWORK)
    }

    @AfterTest
    fun tearDown() {
        AuditorVerdictCache.clear()
    }

    @Test
    fun openingTheReportSendsOneOkDialogWithTheTitleAndTheFormatterText() {
        sut.openReport()

        assertEquals(1, bus.sent.size)
        val dialog = bus.sent.single() as EventShowDialog.Ok
        assertEquals("Auditor report", dialog.title)
        assertEquals(AuditorReportFormatter.buildFullReportMessageWithFallback(EnglishApsText).first, dialog.message)
    }

    @Test
    fun withoutAVerdictTheDialogSaysThatNoReportIsAvailable() {
        sut.openReport()

        val dialog = bus.sent.single() as EventShowDialog.Ok
        assertTrue(dialog.message.contains("No recent auditor report is available."), dialog.message)
    }

    @Test
    fun openingTheReportRemovesTheNotificationOnce() {
        sut.openReport()

        assertEquals(1, notifier.cancelled)
    }

    @Test
    fun openingTheReportMarksTheStateAsReadAgain() {
        // markAsRead publishes the state again from the tracker. A holder that was never updated is
        // still idle, so a new ERROR state proves markAsRead ran.
        assertEquals(AuditorUIState.StateType.IDLE, holder.uiState.value.type)

        sut.openReport()

        assertEquals(AuditorUIState.StateType.ERROR, holder.uiState.value.type)
        assertFalse(holder.uiState.value.shouldNotify)
    }

    private class RecordingNotifier : AimiAuditorNotifier {

        var cancelled = 0

        override fun showInsightAvailable(uiState: AuditorUIState): Boolean = false

        override fun cancelNotification() {
            cancelled++
        }
    }

    private class RecordingBus : RxBus {

        val sent = mutableListOf<Event>()

        override fun send(event: Event) {
            sent += event
        }

        override fun <T : Event> toFlow(eventType: KClass<T>): Flow<T> = emptyFlow()
    }

    private object EnglishApsText : TextResolver {

        override fun gs(ref: TextRef): String = when (ref) {
            is TextRef.Literal    -> ref.text
            is TextRef.Named      -> {
                val template = ApsStringsValues.textOf(ref.name, null) ?: ref.name
                if (ref.args.isEmpty()) template else formatTemplate(template, ref.args)
            }

            is TextRef.AndroidRes -> "res:${ref.id}"
        }

        override fun gs(ref: TextRef, vararg args: Any?): String = gs(ref.withArgs(*args))

        override fun gsNotLocalised(ref: TextRef): String = gs(ref)
    }
}
