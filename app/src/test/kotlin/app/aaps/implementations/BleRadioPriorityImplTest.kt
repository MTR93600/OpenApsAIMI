package app.aaps.implementations

import app.aaps.core.interfaces.ble.BleRadioPriority
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.shared.tests.AAPSLoggerTest
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleRadioPriorityImplTest {

    private val aapsLogger: AAPSLogger = AAPSLoggerTest()

    @Test
    fun `a free radio is given to the first caller`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sut = BleRadioPriorityImpl(aapsLogger, scope)

        assertThat(sut.owner.value).isNull()
        assertThat(sut.acquire(WIZARD, BleRadioPriority.MIN_HOLD_MS)).isTrue()
        assertThat(sut.owner.value).isEqualTo(WIZARD)
    }

    @Test
    fun `a second owner is refused and the first keeps the radio`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sut = BleRadioPriorityImpl(aapsLogger, scope)
        sut.acquire(WIZARD, BleRadioPriority.MIN_HOLD_MS)

        assertThat(sut.acquire(OTHER, BleRadioPriority.MIN_HOLD_MS)).isFalse()
        assertThat(sut.owner.value).isEqualTo(WIZARD)
    }

    @Test
    fun `release by somebody who does not hold it changes nothing`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sut = BleRadioPriorityImpl(aapsLogger, scope)
        sut.acquire(WIZARD, BleRadioPriority.MIN_HOLD_MS)

        sut.release(OTHER)

        assertThat(sut.owner.value).isEqualTo(WIZARD)
    }

    @Test
    fun `release gives the radio back`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sut = BleRadioPriorityImpl(aapsLogger, scope)
        sut.acquire(WIZARD, BleRadioPriority.MIN_HOLD_MS)

        sut.release(WIZARD)

        assertThat(sut.owner.value).isNull()
    }

    @Test
    fun `a lease nobody releases ends by itself`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sut = BleRadioPriorityImpl(aapsLogger, scope)
        sut.acquire(WIZARD, BleRadioPriority.MIN_HOLD_MS)

        advanceTimeBy(BleRadioPriority.MIN_HOLD_MS - 1)
        assertThat(sut.owner.value).isEqualTo(WIZARD)

        advanceUntilIdle()
        assertThat(sut.owner.value).isNull()
    }

    @Test
    fun `a hold longer than the cap is cut down to the cap`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sut = BleRadioPriorityImpl(aapsLogger, scope)
        sut.acquire(WIZARD, BleRadioPriority.MAX_HOLD_MS * 10)

        advanceTimeBy(BleRadioPriority.MAX_HOLD_MS + 1)
        advanceUntilIdle()

        assertThat(sut.owner.value).isNull()
    }

    @Test
    fun `asking again renews the hold instead of being refused`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sut = BleRadioPriorityImpl(aapsLogger, scope)
        sut.acquire(WIZARD, BleRadioPriority.MIN_HOLD_MS)

        advanceTimeBy(BleRadioPriority.MIN_HOLD_MS - 1)
        assertThat(sut.acquire(WIZARD, BleRadioPriority.MIN_HOLD_MS)).isTrue()

        // Past the first hold, but the renewed one is still running.
        advanceTimeBy(2)
        assertThat(sut.owner.value).isEqualTo(WIZARD)
    }

    @Test
    fun `an expiry left over from an old lease does not cut a newer one short`() = runTest {
        val scope = TestScope(StandardTestDispatcher(testScheduler))
        val sut = BleRadioPriorityImpl(aapsLogger, scope)
        sut.acquire(WIZARD, BleRadioPriority.MIN_HOLD_MS)
        sut.release(WIZARD)
        sut.acquire(OTHER, BleRadioPriority.MAX_HOLD_MS)

        advanceTimeBy(BleRadioPriority.MIN_HOLD_MS + 1)

        assertThat(sut.owner.value).isEqualTo(OTHER)
    }

    companion object {

        private const val WIZARD = "MedtrumPatchWizard"
        private const val OTHER = "SomeOtherOwner"
    }
}
