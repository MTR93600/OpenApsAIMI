package app.aaps.ui.compose.calibrationDialog

import app.aaps.core.interfaces.calibration.CalibrationStatus
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.ui.UiStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Ref `CalibrationDialogViewModel.notYetEffectiveMessage` L254–257 @ `6598201d`.
 * Only NeedMoreEntries and UnsafeFit produce a sentence. The other five statuses stay quiet.
 */
class CalibrationSavedEntryNoticeTest {

    private val rh = RecordingResolver()

    @Test
    fun only_need_more_entries_and_unsafe_fit_are_told_to_the_user() {
        val needMore = notYetEffectiveMessage(CalibrationStatus.NeedMoreEntries(1), rh)
        assertEquals("need-more", needMore)
        assertEquals(UiStrings.cal_saved_need_more_entries, rh.lastRef)
        assertEquals(listOf(1), rh.lastArgs)

        val unsafe = notYetEffectiveMessage(CalibrationStatus.UnsafeFit, rh)
        assertEquals("unsafe", unsafe)
        assertEquals(UiStrings.cal_saved_unsafe_fit, rh.lastRef)
        assertNull(rh.lastArgs)

        val quiet = listOf(
            CalibrationStatus.NoSession,
            CalibrationStatus.WarmUp(1_700_000_000_000L),
            CalibrationStatus.AppliedOffsetOnly,
            CalibrationStatus.AppliedSlopeClamped,
            CalibrationStatus.Applied
        )
        quiet.forEach { status ->
            rh.lastRef = null
            assertNull(notYetEffectiveMessage(status, rh))
            assertNull(rh.lastRef)
        }
    }

    private class RecordingResolver : TextResolver {
        var lastRef: TextRef? = null
        var lastArgs: List<Any?>? = null

        override fun gs(ref: TextRef): String {
            lastRef = ref
            lastArgs = null
            return if (ref == UiStrings.cal_saved_unsafe_fit) "unsafe" else "other"
        }

        override fun gs(ref: TextRef, vararg args: Any?): String {
            lastRef = ref
            lastArgs = args.toList()
            return "need-more"
        }

        override fun gsNotLocalised(ref: TextRef): String = gs(ref)

        override fun shortTextMode(): Boolean = false
    }
}
