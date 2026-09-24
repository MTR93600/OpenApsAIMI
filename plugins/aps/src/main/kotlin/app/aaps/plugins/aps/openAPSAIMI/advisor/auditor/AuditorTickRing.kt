package app.aaps.plugins.aps.openAPSAIMI.advisor.auditor

/**
 * One loop tick as the engine saw it, before any auditor factor.
 *
 * Every value is raw: this is what the loop dosed on, and it is what the auditor must be judged
 * against. Taking it from the loop instead of from the database matters, because the database holds
 * the raw sensor value while the loop works on the calibrated, bucketed one.
 *
 * @param isfFactorApplied the auditor ISF factor that really reached the dose on this tick, 1.0 when
 *   none. It is recorded so a later reader can tell a clean window from one the auditor already
 *   touched.
 */
data class AuditorTickFact(
    val timestampMs: Long,
    val bgMgdl: Double,
    val deltaMgdl5m: Double,
    val shortAvgDeltaMgdl5m: Double,
    val iobU: Double,
    val cobG: Double,
    val profileIsfStaticMgdl: Double?,
    val dynamicIsfRawMgdl: Double,
    val commandIsfRawMgdl: Double,
    val commandIsfPreFloorMgdl: Double?,
    val commandFloorMultiplier: Double?,
    val workingIsfRawMgdl: Double?,
    val profileTargetMgdl: Double,
    val tempTargetActive: Boolean,
    val workingTargetRawMgdl: Double?,
    val minPredBgMgdl: Double?,
    val hypoThresholdMgdl: Double?,
    val runningBasalUph: Double,
    val profileBasalUph: Double,
    val postHypoActive: Boolean,
    val exerciseLockout: Boolean,
    val isfFactorApplied: Double,
    val targetFactorApplied: Double,
)

/**
 * The last 45 minutes of ticks, oldest first.
 *
 * The auditor needs 30 minutes of history and runs a few minutes after the tick it audits, so the
 * ring keeps a little more than the window it serves. It lives in memory only: after an app restart
 * it is empty, the context is then incomplete, and every proposal is refused for half an hour. That
 * is the safe way round.
 *
 * The loop writes it and the auditor coroutine reads it, so every access is behind one lock and
 * [snapshot] hands back an immutable copy.
 */
class AuditorTickRing(
    private val maxAgeMs: Long = 45 * 60_000L,
    private val maxSize: Int = 64,
) {

    private val lock = Any()
    private val facts = ArrayDeque<AuditorTickFact>()

    /** Adds one tick and drops what is too old or too far back. */
    fun record(fact: AuditorTickFact) {
        synchronized(lock) {
            facts.addLast(fact)
            while (facts.size > maxSize) facts.removeFirst()
            val oldestKept = fact.timestampMs - maxAgeMs
            while (facts.isNotEmpty() && facts.first().timestampMs < oldestKept) facts.removeFirst()
        }
    }

    /** The ticks of the last [maxAgeMs] before [nowMs], oldest first. */
    fun snapshot(nowMs: Long): List<AuditorTickFact> = synchronized(lock) {
        facts.filter { it.timestampMs >= nowMs - maxAgeMs && it.timestampMs <= nowMs }.toList()
    }

    /** The newest tick, or null when the ring is empty. */
    fun latest(): AuditorTickFact? = synchronized(lock) { facts.lastOrNull() }
}
