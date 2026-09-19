package app.aaps.plugins.aps.openAPSAIMI.learning

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiPath
import app.aaps.plugins.aps.openAPSAIMI.utils.AimiStorage
import app.aaps.shared.tests.TestBase
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever

/**
 * Pins the "refresh in flight" guard contract for [UnifiedReactivityLearner].
 *
 * The KMP conversion moved `bg24hRefreshInFlight` / `bg2hRefreshInFlight` / `exerciseRefreshInFlight`
 * from `java.util.concurrent.atomic.AtomicBoolean` (`compareAndSet`) to an `AapsLock` plus a
 * `@Volatile` flag, tested and set inside one `withLock`. There was no test coverage for this class
 * before this conversion. This is the one field in the conversion where a wrong choice - reading the
 * flag outside the lock, or forgetting to set it before launching the background refresh - would
 * silently allow two refreshes to run at once instead of failing loudly. This test proves that a
 * second call made while a refresh is still in flight does not start a second one.
 */
class UnifiedReactivityLearnerGuardTest : TestBase() {

    @Mock lateinit var persistenceLayer: PersistenceLayer
    @Mock lateinit var dateUtil: DateUtil
    @Mock lateinit var preferences: Preferences
    @Mock lateinit var storage: AimiStorage

    private fun buildLearner(): UnifiedReactivityLearner {
        whenever(storage.file(any())).thenReturn(AimiPath("test-unified-reactivity"))
        whenever(dateUtil.now()).thenReturn(1_700_000_000_000L)
        return UnifiedReactivityLearner(
            persistenceLayer = persistenceLayer,
            dateUtil = dateUtil,
            preferences = preferences,
            log = aapsLogger,
            storage = storage,
        )
    }

    @Test
    fun `a second analyzeLast24h call while a refresh is in flight does not start a second refresh`() {
        val callCount = AtomicInteger(0)
        val enteredLatch = CountDownLatch(1)
        val releaseLatch = CountDownLatch(1)

        runBlocking {
            whenever(persistenceLayer.getTherapyEventDataFromTime(any(), any())).thenReturn(emptyList())
            whenever(persistenceLayer.getBgReadingsDataFromTime(any(), any())).thenAnswer {
                callCount.incrementAndGet()
                enteredLatch.countDown()
                // Hold the "in flight" window open long enough for the second call below to run its
                // test-and-set while the first refresh is still active - the exact race the guard exists
                // to prevent.
                releaseLatch.await(5, TimeUnit.SECONDS)
                emptyList<GV>()
            }
        }

        val learner = buildLearner()

        // Starts the background refresh. The guard is set (inside the lock) before the coroutine is
        // launched, so by the time this call returns, bg24hRefreshInFlight is already true even though
        // the coroutine itself is still parked inside the mocked call above.
        learner.analyzeLast24h()
        assertThat(enteredLatch.await(2, TimeUnit.SECONDS)).isTrue()

        // A second call while the first refresh is still in flight must be a no-op for the refresh -
        // not a second background query.
        learner.analyzeLast24h()

        releaseLatch.countDown()
        // Give the background coroutine a moment to finish and reset the flag.
        Thread.sleep(200)

        assertThat(callCount.get()).isEqualTo(1)
    }
}
