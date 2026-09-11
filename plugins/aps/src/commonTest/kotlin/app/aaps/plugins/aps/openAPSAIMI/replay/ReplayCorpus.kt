package app.aaps.plugins.aps.openAPSAIMI.replay

/**
 * Loads replay fixtures.
 *
 * Two sources, on purpose:
 *
 * - **Bundled** (`src/commonTest/resources/replay/`) — a few of the maintainer's own days, versioned so
 *   CI can run regression checks. Enough to answer *"did this change alter the decision stream?"*.
 * - **Local** (directory named by [ENV_LOCAL_CORPUS]) — a larger private corpus that is never
 *   committed. Needed to answer *"is this threshold right?"*, because a bundled or synthetic set
 *   only contains patterns someone already believed in. During the audit that produced these ADRs,
 *   the wider corpus contradicted the working hypothesis twice; a curated set would have agreed
 *   with it.
 *
 * Tests that only need regression use [load]. Tests that calibrate use [loadLocal] and skip
 * themselves when the private corpus is absent.
 *
 * Study port of `79588b42cb`. P0.12 bundles only [BARRIER_TICKS]: the day fixtures stay on
 * `origin/dev_OAPSAIMI` and are out of this lot. [load] of a missing name fails clearly; it does
 * not pretend the day was empty. [loadLocal] is a no-op in `commonTest` (no `java.io.File` /
 * `System.getenv` on Native).
 *
 * This is the APS barrier harness — not `quality/ReplayQualityExport`.
 */
object ReplayCorpus {

    /** Directory holding extra, uncommitted packages projected to the fixture format. */
    const val ENV_LOCAL_CORPUS = "AIMI_REPLAY_CORPUS"

    /** A day that scored 95.4 % time in range with no time below 70. The non-regression reference. */
    const val DAY_IN_RANGE = "day_in_range.jsonl"

    /** A day with four chained post-hypo correction cycles (2026-08-04). */
    const val DAY_REBOUND_CYCLES = "day_rebound_cycles.jsonl"

    /** A day spending 21 % above 180 mg/dL. */
    const val DAY_HYPER = "day_hyper.jsonl"

    /**
     * Barrier ticks only, de-identified — see the README.
     *
     * Deliberately **not** in [bundled]: it is not a day. It carries no clock, no decision, no
     * owner, and only the ticks where `ControlBarrierShield` actually ran, so a day summary of it
     * would be meaningless. Read it with [BarrierReplay].
     */
    const val BARRIER_TICKS = "barrier_ticks.jsonl"

    /** All bundled *day* fixtures, in the order a report should present them. */
    val bundled: List<String> = listOf(DAY_IN_RANGE, DAY_REBOUND_CYCLES, DAY_HYPER)

    fun load(name: String): List<ReplayTick> {
        val text = bundledText(name)
            ?: error("Replay fixture not found on the test classpath: replay/$name")
        return parse(text.lineSequence())
    }

    /**
     * Every package of the private corpus, or an empty list when [ENV_LOCAL_CORPUS] is not set.
     * Callers must treat an empty result as "skip", never as "nothing to report".
     *
     * Study `commonTest` cannot read a host directory, so this always returns empty here.
     */
    fun loadLocal(): Map<String, List<ReplayTick>> = emptyMap()

    private fun bundledText(name: String): String? = when (name) {
        BARRIER_TICKS -> BarrierTicksJsonl.TEXT
        else -> null
    }

    private fun parse(lines: Sequence<String>): List<ReplayTick> =
        lines.filter { it.isNotBlank() }
            .map { ReplayTick.fromJson(it) }
            .sortedBy { it.timestampMs }
            .toList()
}
