package app.aaps.plugins.aps.openAPSAIMI.replay

import java.io.File

/**
 * Loads replay fixtures.
 *
 * Two sources, on purpose:
 *
 * - **Bundled** (`src/test/resources/replay/`) — a few of the maintainer's own days, versioned so
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
 * See `docs/adr/0001-replay-harness.md`.
 */
object ReplayCorpus {

    /** Directory holding extra, uncommitted packages projected to the fixture format. */
    const val ENV_LOCAL_CORPUS = "AIMI_REPLAY_CORPUS"

    private const val RESOURCE_DIR = "replay"

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
        val stream = javaClass.classLoader.getResourceAsStream("$RESOURCE_DIR/$name")
            ?: error("Replay fixture not found on the test classpath: $RESOURCE_DIR/$name")
        return stream.bufferedReader().useLines { lines -> parse(lines) }
    }

    /**
     * Every package of the private corpus, or an empty list when [ENV_LOCAL_CORPUS] is not set.
     * Callers must treat an empty result as "skip", never as "nothing to report".
     */
    fun loadLocal(): Map<String, List<ReplayTick>> {
        val dir = System.getenv(ENV_LOCAL_CORPUS)?.let(::File) ?: return emptyMap()
        if (!dir.isDirectory) return emptyMap()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") }
            .orEmpty()
            .sortedBy { it.name }
            .associate { file -> file.name to file.bufferedReader().useLines { lines -> parse(lines) } }
    }

    private fun parse(lines: Sequence<String>): List<ReplayTick> =
        lines.filter { it.isNotBlank() }
            .map { ReplayTick.fromJson(it) }
            .sortedBy { it.timestampMs }
            .toList()
}
