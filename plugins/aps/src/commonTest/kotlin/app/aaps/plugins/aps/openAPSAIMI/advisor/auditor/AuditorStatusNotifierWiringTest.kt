package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.model.AuditorUIState
import app.aaps.plugins.aps.openAPSAIMI.advisor.auditor.ui.AuditorStatusStateHolder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The wiring test for [AuditorStatusNotifier].
 *
 * The writers take the interface, so they can live in shared code. What that must not change is the
 * chip: a `notifyUpdate()` still has to reach the real [AuditorStatusStateHolder], and that holder
 * still has to hand the new state to whoever reads it - which is what `AuditorStatusBadgeSource`
 * does.
 *
 * So this test uses the real holder, not a fake, and reads the flow the Overview chip reads. It
 * lives in `commonTest` so it runs on iOS as well: the holder is shared code now, and the whole
 * point of moving it was that both phones behave the same.
 */
class AuditorStatusNotifierWiringTest {

    private val holder = AuditorStatusStateHolder()

    @BeforeTest
    fun setUp() {
        AuditorVerdictCache.clear()
    }

    @AfterTest
    fun tearDown() {
        AuditorVerdictCache.clear()
    }

    @Test
    fun `the notifier the writers hold is the same state the chip reads`() {
        val notifier: AuditorStatusNotifier = holder
        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFFLINE_NO_NETWORK)

        notifier.notifyUpdate()

        assertEquals(AuditorUIState.StateType.ERROR, holder.uiState.value.type)
        assertEquals("No network connection", holder.uiState.value.statusMessage)
    }

    /**
     * `LiveData.postValue` used to drop a value when a second one was posted before the main looper
     * ran. A `StateFlow` only drops a value equal to the one before it, and every state carries its
     * own `timestampMs`, so two different statuses always both arrive.
     */
    @Test
    fun `two statuses in a row both reach the reader`() = runTest {
        val seen = mutableListOf<String>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            holder.uiState.collect { seen += it.statusMessage }
        }

        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFFLINE_NO_NETWORK)
        holder.notifyUpdate()
        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.ERROR_TIMEOUT)
        holder.notifyUpdate()

        job.cancel()

        assertEquals(listOf("Auditor idle", "No network connection", "Request timeout"), seen)
    }

    /**
     * A reader that starts late still gets the current state. The old `LiveData` holder had a
     * `forceUpdate()` for exactly this, called when a fragment attached its observer. The flow does
     * it by itself, which is why that method is gone.
     */
    @Test
    fun `a reader that starts late still gets the current state`() = runTest {
        AuditorStatusTracker.updateStatus(AuditorStatusTracker.Status.OFFLINE_NO_APIKEY)
        holder.notifyUpdate()

        assertEquals("No API key configured", holder.uiState.first().statusMessage)
    }
}
