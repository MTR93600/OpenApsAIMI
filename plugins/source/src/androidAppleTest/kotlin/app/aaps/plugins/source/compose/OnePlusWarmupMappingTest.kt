package app.aaps.plugins.source.compose

import app.aaps.plugins.dexcomoneplus.OnePlusWarmupState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ONE+ warm-up mapping is shared now, so the warm-up screen on iPhone reads the driver the same
 * way as on Android. Runs on the JVM host and on the iOS simulator.
 *
 * No test name here may contain a comma: Kotlin/Native rejects it.
 */
class OnePlusWarmupMappingTest {

    @Test
    fun everyDriverPhaseMapsToTheSharedPhaseOfTheSameName() {
        OnePlusWarmupState.Phase.entries.forEach { phase ->
            assertEquals(phase.name, phase.toCgmWarmupPhase().name)
        }
    }

    @Test
    fun theCountdownFactsAreCarriedOverUnchanged() {
        val info = OnePlusWarmupState(
            phase = OnePlusWarmupState.Phase.WARMING,
            remainingMs = 120_000L,
            endsAtEpochMs = 5_000_000L,
        ).toCgmWarmupInfo()

        assertEquals(CgmWarmupInfo(CgmWarmupPhase.WARMING, 120_000L, 5_000_000L), info)
    }
}
