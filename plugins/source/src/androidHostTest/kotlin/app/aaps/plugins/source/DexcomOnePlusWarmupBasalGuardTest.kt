package app.aaps.plugins.source

import app.aaps.core.data.model.GV
import app.aaps.core.data.model.RM
import app.aaps.core.data.model.SourceSensor
import app.aaps.core.data.model.TB
import app.aaps.core.data.model.TrendArrow
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.profile.EffectiveProfile
import app.aaps.core.interfaces.queue.CommandQueue
import app.aaps.core.interfaces.source.BgSource
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import javax.inject.Provider

/**
 * Decision-matrix tests for the SAFETY basal guard (see [DexcomOnePlusWarmupBasalGuard]).
 * Covers the hard guards + option (b) + the recent-glucose guard. Does not exercise the pump
 * command (fire-and-forget) — only the `shouldCancelResidualTemp` decision.
 */
class DexcomOnePlusWarmupBasalGuardTest : TestBaseWithProfile() {

    private val loop: Loop = mock()
    private val commandQueue: CommandQueue = mock()
    private val persistenceLayer: PersistenceLayer = mock()
    private val owner: BgSource = mock()
    private val profile: EffectiveProfile = mock()

    private lateinit var guard: DexcomOnePlusWarmupBasalGuard

    private val fixedNow = 1_000_000_000L
    private val highTemp = TB(timestamp = fixedNow - 60_000L, type = TB.Type.NORMAL, isAbsolute = true, rate = 2.0, duration = 30 * 60_000L)
    private val lowTemp = TB(timestamp = fixedNow - 60_000L, type = TB.Type.NORMAL, isAbsolute = true, rate = 0.3, duration = 30 * 60_000L)

    @BeforeEach
    fun prepareGuard() {
        guard = DexcomOnePlusWarmupBasalGuard(aapsLogger, commandQueue, Provider { loop }, activePlugin, profileFunction, persistenceLayer)
        runTest {
            // Happy path: active source, closed loop, no fresh glucose, HIGH residual temp, profile 1.0 U/h.
            whenever(activePlugin.activeBgSource).thenReturn(owner)
            whenever(loop.isEnabled()).thenReturn(true)
            whenever(loop.runningMode()).thenReturn(RM.Mode.CLOSED_LOOP)
            whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(null)
            whenever(persistenceLayer.getTemporaryBasalActiveAt(fixedNow)).thenReturn(highTemp)
            whenever(profile.getBasal()).thenReturn(1.0)
            whenever(profileFunction.getProfile()).thenReturn(profile)
        }
    }

    @Test
    fun `high temp, no fresh glucose, closed loop, active source → cancel`() = runTest {
        assertThat(guard.shouldCancelResidualTemp(owner, fixedNow)).isTrue()
    }

    @Test
    fun `not the active BG source → skip`() = runTest {
        whenever(activePlugin.activeBgSource).thenReturn(mock<BgSource>())
        assertThat(guard.shouldCancelResidualTemp(owner, fixedNow)).isFalse()
    }

    @Test
    fun `loop disabled → skip`() = runTest {
        whenever(loop.isEnabled()).thenReturn(false)
        assertThat(guard.shouldCancelResidualTemp(owner, fixedNow)).isFalse()
    }

    @Test
    fun `open loop → skip (do not auto-enact)`() = runTest {
        whenever(loop.runningMode()).thenReturn(RM.Mode.OPEN_LOOP)
        assertThat(guard.shouldCancelResidualTemp(owner, fixedNow)).isFalse()
    }

    @Test
    fun `suspended by user → skip (never lift a protective zero-temp)`() = runTest {
        whenever(loop.runningMode()).thenReturn(RM.Mode.SUSPENDED_BY_USER)
        assertThat(guard.shouldCancelResidualTemp(owner, fixedNow)).isFalse()
    }

    @Test
    fun `recent glucose present → skip (loop owns dosing)`() = runTest {
        val recent = GV(
            timestamp = fixedNow - 2 * 60_000L,
            raw = null,
            value = 120.0,
            trendArrow = TrendArrow.FLAT,
            noise = null,
            sourceSensor = SourceSensor.UNKNOWN,
        )
        whenever(persistenceLayer.getLastGlucoseValue()).thenReturn(recent)
        assertThat(guard.shouldCancelResidualTemp(owner, fixedNow)).isFalse()
    }

    @Test
    fun `no active temp → skip (already on profile)`() = runTest {
        whenever(persistenceLayer.getTemporaryBasalActiveAt(fixedNow)).thenReturn(null)
        assertThat(guard.shouldCancelResidualTemp(owner, fixedNow)).isFalse()
    }

    @Test
    fun `temp at or below profile → skip (option b, never raise delivery)`() = runTest {
        whenever(persistenceLayer.getTemporaryBasalActiveAt(fixedNow)).thenReturn(lowTemp)
        assertThat(guard.shouldCancelResidualTemp(owner, fixedNow)).isFalse()
    }
}
