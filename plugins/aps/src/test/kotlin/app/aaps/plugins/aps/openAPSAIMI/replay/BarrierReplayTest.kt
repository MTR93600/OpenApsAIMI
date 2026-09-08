package app.aaps.plugins.aps.openAPSAIMI.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Self-check of the barrier replay.
 *
 * The first test that matters is [replayReproducesProductionSafeU]. Every other figure this harness
 * can produce — what a lower coefficient would permit, how many suspensions it would lift — is only
 * worth reading if the replay lands on the recorded `safe_u` first.
 *
 * See `docs/adr/0001-replay-harness.md`.
 */
class BarrierReplayTest {

    private fun barrierTicks(): List<ReplayTick> = ReplayCorpus.load(ReplayCorpus.BARRIER_TICKS)

    @Test
    fun tickWithoutBarrierBlockLoadsWithNullsAndDoesNotThrow() {
        val tick = ReplayTick.fromJson("""{"t":1,"bg":120.0,"iob":1.5}""")

        assertFalse(tick.hasBarrierBlock)
        assertNull(tick.cbfActiveGamma)
        assertNull(tick.cbfSafeU)
        assertNull(tick.cbfSiMetabolic)
        assertNull(tick.cbfLfhMgdlPerMin)
        assertNull(tick.cbfPermittedU)
        assertNull(tick.cbfBgMgdl)
        assertNull(tick.cbfIobU)
        assertNull(tick.cbfEstimatedRaMgdlPerMin)
        assertNull(tick.maxIobU)
        // The point of the nulls: an absent barrier must not read as a barrier that permitted zero.
        assertEquals(120.0, tick.bgMgdl)
    }

    @Test
    fun dayFixturesPredateTheBarrierFieldsAndStayLoadable() {
        val ticks = ReplayCorpus.load(ReplayCorpus.DAY_IN_RANGE)

        assertTrue(ticks.isNotEmpty())
        assertTrue(ticks.none { it.hasBarrierBlock }, "day fixtures were captured before the barrier block existed")
        assertTrue(BarrierReplay.replayable(ticks).isEmpty(), "a day fixture has nothing to replay, and that is not a failure")
    }

    @Test
    fun barrierFixtureCarriesBothSuspendedAndPassingTicks() {
        val ticks = barrierTicks()

        assertTrue(ticks.size >= 40, "expected a few dozen barrier ticks, got ${ticks.size}")
        assertEquals(ticks.size, BarrierReplay.replayable(ticks).size, "every tick of this fixture must be replayable")
        assertTrue(ticks.any { it.cbfFullySuspended == true }, "fixture must contain full suspensions")
        assertTrue(ticks.any { it.cbfFullySuspended == false }, "fixture must contain ticks the barrier let through")
        assertTrue(ticks.any { it.cbfSafeU == null }, "fixture must contain ticks the barrier never had to touch")
        assertTrue(
            ticks.map { it.cbfActiveGamma }.distinct().size >= 3,
            "fixture must cover the accelerated, nominal and relaxed gamma branches",
        )
    }

    @Test
    fun replayReproducesProductionSafeU() {
        val ticks = barrierTicks()

        val reproduction = BarrierReplay.reproduction(ticks)

        assertEquals(ticks.size, reproduction.ticks)
        assertEquals(
            reproduction.ticks, reproduction.gammaReproduced,
            "gamma branch not reproduced on every tick\n${reproduction.format()}",
        )
        assertEquals(
            reproduction.ticks, reproduction.safeUReproduced,
            "safe_u reproduction is the entry lock of this harness\n${reproduction.format()}",
        )
        // Lower on purpose, and it will never be 100 %. Past `safe_u`, the permitted total also
        // depends on `isHighRiseMeal` — which needs the velocity the export does not carry — and on
        // the profile basal the engine handed the barrier, which is not always the exported one. A
        // few ticks therefore split the same budget between TBR and SMB differently from
        // production. `safe_u` above is the number the floor question turns on; this one is
        // asserted loosely and printed exactly, so the gap stays visible instead of being claimed
        // away.
        assertTrue(
            reproduction.permittedRate >= 90.0,
            "permitted total reproduction fell below 90 %\n${reproduction.format()}",
        )
    }

    @Test
    fun lowerCoefficientNeverPermitsLessThanProduction() {
        val ticks = BarrierReplay.replayable(barrierTicks())

        ticks.forEach { tick ->
            val before = BarrierReplay.replay(tick, BarrierReplay.PRODUCTION)
            val after = BarrierReplay.replay(tick, BarrierReplay.UNFLOORED)
            assertNotNull(before)
            assertNotNull(after)
            if (after!!.coefficient > before!!.coefficient) return@forEach // nothing to prove here

            val recordedSafeU = before.safeU
            val candidateSafeU = after.safeU
            if (recordedSafeU != null && candidateSafeU != null) {
                assertTrue(
                    candidateSafeU >= recordedSafeU - 1e-12,
                    "a smaller coefficient must widen the barrier: safeU went from $recordedSafeU " +
                        "to $candidateSafeU at tick ${tick.offsetMinutes} min",
                )
            }
            // The permitted total is monotone here too, but not by construction: `enforce` switches
            // branch at safeU = 0.2 and the branch above it caps the TBR at the profile basal, so a
            // larger budget can hand back less when the controller asked for a very large TBR and no
            // SMB. If this ever fires, read the branch before suspecting the replay.
            assertTrue(
                after.permittedU >= before.permittedU - 1e-12,
                "permitted total fell from ${before.permittedU} to ${after.permittedU} at tick " +
                    "${tick.offsetMinutes} min — check the safeU = 0.2 branch of ControlBarrierShield",
            )
        }
    }

    @Test
    fun twoTicksInARowDoNotShareAccelerationMemory() {
        val ticks = BarrierReplay.replayable(barrierTicks())
        assertTrue(ticks.size >= 2)

        val alone = ticks.map { BarrierReplay.replay(it, BarrierReplay.PRODUCTION) }
        // Same ticks, same object, run one after the other through the same call path.
        val inSequence = BarrierReplay.run(ticks, BarrierReplay.PRODUCTION).outcomes

        assertEquals(alone.size, inSequence.size)
        alone.forEachIndexed { index, single ->
            assertEquals(
                single, inSequence[index],
                "tick $index changed when replayed after its neighbour: the shield's acceleration " +
                    "memory is leaking between ticks",
            )
        }
    }

    @Test
    fun unflooredCoefficientIsMeasuredAgainstProduction() {
        val comparison = BarrierReplay.compare(barrierTicks(), BarrierReplay.UNFLOORED)

        // Printed on purpose. This is the before/after the note on
        // `InsulinActionModel.controlCoefficient` asks for; a green test that hides it would only
        // prove the harness runs.
        println(comparison.format())

        assertEquals(comparison.production.ticks, comparison.candidate.ticks)
        // Strict, not "at least": if the floor ever stops costing anything on this fixture, either
        // the fixture changed or the candidate is measuring itself again — the failure mode
        // `AutodriveEngine.recordCoefficientShadow` documents, where the shadow went back through
        // `controlCoefficient` and re-applied the very floor it was measuring.
        assertTrue(
            comparison.candidate.totalPermittedU > comparison.production.totalPermittedU,
            "removing the floor must permit strictly more, or it is not being measured\n${comparison.format()}",
        )
        assertTrue(
            comparison.candidate.fullSuspensions <= comparison.production.fullSuspensions,
            "removing the floor cannot suspend more often\n${comparison.format()}",
        )
        // A tick can only be "unlocked" if production suspended it in the first place. This is a
        // consistency check on the metric, not an expectation about the data: on a small fixture
        // the count is legitimately allowed to be zero.
        assertTrue(
            comparison.unlockedTicks <= comparison.production.fullSuspensions,
            "more unlocked ticks than suspended ones\n${comparison.format()}",
        )
        assertTrue(
            comparison.unlockedAtLowCommandIsf <= comparison.unlockedTicks,
            "low-ISF unlocks must be a subset of all unlocks\n${comparison.format()}",
        )
    }
}
