package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

/**
 * Pins the tick id sequence of [AimiLoopTelemetry] and what the ring and the readers see around one
 * tick. The id is the key the blackbox rows are joined on, so a gap or a repeat would silently break
 * every correlation.
 *
 * This lives in `androidHostTest` and not in `commonTest` because it needs a `Preferences`, which only
 * Mockito can stand in for and which is JVM only.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AimiLoopTelemetryTickTest {

    @Mock private lateinit var preferences: Preferences

    @BeforeEach
    fun setUp() {
        whenever(preferences.get(BooleanKey.OApsAIMILoopExclusiveInvocationEnabled)).thenReturn(false)
    }

    @Test
    fun ticksGetConsecutiveIds() {
        val first = runTick()
        val second = runTick()
        val third = runTick()
        assertThat(second).isEqualTo(first + 1)
        assertThat(third).isEqualTo(second + 1)
    }

    @Test
    fun theExclusiveGateDoesNotChangeTheIdSequence() {
        whenever(preferences.get(BooleanKey.OApsAIMILoopExclusiveInvocationEnabled)).thenReturn(true)
        val first = runTick()
        val second = runTick()
        assertThat(second).isEqualTo(first + 1)
    }

    @Test
    fun theActiveTickIsVisibleWhileItRunsAndClearedAfter() {
        var seenId = -1L
        var seenInProgress = false
        var seenAge = -1L
        val started = 1_000L
        runTick(startedWallMs = started) {
            seenId = AimiLoopTelemetry.activeTickId
            seenInProgress = AimiLoopTelemetry.isTickInProgress()
            seenAge = AimiLoopTelemetry.activeTickAgeMs()
        }
        assertThat(seenInProgress).isTrue()
        assertThat(seenId).isGreaterThan(0L)
        assertThat(seenAge).isGreaterThan(0L)
        assertThat(AimiLoopTelemetry.activeTickId).isEqualTo(0L)
        assertThat(AimiLoopTelemetry.isTickInProgress()).isFalse()
        assertThat(AimiLoopTelemetry.activeTickAgeMs()).isEqualTo(0L)
    }

    @Test
    fun aNestedTickTakesTheNextIdAndGivesTheOuterOneBack() {
        var outerId = 0L
        var innerId = 0L
        var outerIdAfterInner = 0L
        runTick {
            outerId = AimiLoopTelemetry.activeTickId
            innerId = runTick()
            outerIdAfterInner = AimiLoopTelemetry.activeTickId
        }
        assertThat(innerId).isEqualTo(outerId + 1)
        assertThat(outerIdAfterInner).isEqualTo(outerId)
        assertThat(AimiLoopTelemetry.activeTickId).isEqualTo(0L)
    }

    @Test
    fun aNormalTickWritesStartAndEndForTheSameId() {
        val id = runTick(startedWallMs = 500L)
        val tail = AimiLoopTelemetry.ringSnapshotTail(8)
        val start = tail.single { it.contains("tick_start id=$id ") }
        val end = tail.single { it.contains("tick_end id=$id ") }
        assertThat(start).contains("wall_ms=500")
        assertThat(tail.indexOf(start)).isLessThan(tail.indexOf(end))
        assertThat(tail.none { it.contains("tick_abort id=$id ") }).isTrue()
    }

    @Test
    fun aFailingTickRecoversAndWritesAbortForTheSameId() {
        val boom = IllegalStateException("boom")
        var abortId = 0L
        var recovered: Throwable? = null
        val result = AimiLoopTelemetry.traceDetermineBasalTick(
            preferences = preferences,
            wallClockMs = 700L,
            onLockTimeout = { rt() },
            recoverFromError = { recovered = it; rt() },
            onTickAbort = { tickId, _, _, _ -> abortId = tickId },
            block = { throw boom }
        )
        assertThat(result).isNotNull()
        assertThat(recovered).isSameInstanceAs(boom)
        assertThat(abortId).isGreaterThan(0L)
        val abort = AimiLoopTelemetry.ringSnapshotTail(8).single { it.contains("tick_abort id=$abortId ") }
        assertThat(abort).contains("error=IllegalStateException")
        assertThat(AimiLoopTelemetry.activeTickId).isEqualTo(0L)
    }

    @Test
    fun theExclusiveGateLetsANestedTickThroughRatherThanSkippingIt() {
        whenever(preferences.get(BooleanKey.OApsAIMILoopExclusiveInvocationEnabled)).thenReturn(true)
        var skipped = false
        var innerId = 0L
        var outerId = 0L
        AimiLoopTelemetry.traceDetermineBasalTick(
            preferences = preferences,
            wallClockMs = 800L,
            onLockTimeout = { rt() },
            recoverFromError = { rt() },
            onTickEnd = { tickId, _, _ -> outerId = tickId },
            block = {
                // The gate is reentrant, exactly like the ReentrantLock it replaces, so the same
                // thread is let through instead of waiting for itself.
                AimiLoopTelemetry.traceDetermineBasalTick(
                    preferences = preferences,
                    wallClockMs = 900L,
                    onLockTimeout = { skipped = true; rt() },
                    recoverFromError = { rt() },
                    onTickEnd = { tickId, _, _ -> innerId = tickId },
                    block = { rt() }
                )
            }
        )
        assertThat(skipped).isFalse()
        assertThat(innerId).isEqualTo(outerId + 1)
        // Both levels were released, so the next tick is not blocked by the one just finished.
        assertThat(runTick()).isEqualTo(innerId + 1)
    }

    private fun rt() = RT(runningDynamicIsf = false)

    /** Runs one tick and returns the id it was given. */
    private fun runTick(startedWallMs: Long = 100L, inside: () -> Unit = {}): Long {
        var id = 0L
        AimiLoopTelemetry.traceDetermineBasalTick(
            preferences = preferences,
            wallClockMs = startedWallMs,
            onLockTimeout = { rt() },
            recoverFromError = { rt() },
            onTickEnd = { tickId, _, _ -> id = tickId },
            block = { inside(); rt() }
        )
        return id
    }
}
