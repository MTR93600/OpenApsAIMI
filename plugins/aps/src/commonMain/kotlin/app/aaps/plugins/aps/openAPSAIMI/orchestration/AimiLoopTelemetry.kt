package app.aaps.plugins.aps.openAPSAIMI.orchestration

import app.aaps.core.interfaces.aps.RT
import app.aaps.core.interfaces.concurrent.AapsLock
import app.aaps.core.interfaces.concurrent.withLock
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import app.aaps.plugins.aps.openAPSAIMI.physio.HormonitorStudyExporter
import kotlin.collections.ArrayDeque
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * Observe-only loop telemetry: tick id, phases, timing hints, and blackbox correlation
 * (stall watchdog lives in the hormonitor study exporter).
 */
@OptIn(ExperimentalAtomicApi::class)
object AimiLoopTelemetry {

    private const val RING_MAX = 128

    private val tickSeq = AtomicLong(0L)

    @Volatile
    internal var activeTickId: Long = 0L
        private set

    /** Wall clock at current tick start; 0 when idle. */
    @Volatile
    internal var activeTickStartedWallMs: Long = 0L
        private set

    @Volatile
    internal var currentLoopPhase: AimiLoopPhase = AimiLoopPhase.BOOTSTRAP
        private set

    private var lastPhaseMarkWallMs: Long = 0L

    private val ring = ArrayDeque<String>()

    /**
     * Guards [ring]. A dedicated lock rather than the deque itself, because `synchronized` is JVM
     * only; it is reentrant and blocking, so it behaves like the monitor it replaces.
     */
    private val ringLock = AapsLock()

    /**
     * Records a coarse phase for the active tick (ring + optional blackbox JSONL).
     * Adds [HormonitorStudyExporter.recordLoopPhase] timing fields when the wall anchor is set.
     */
    internal fun enterPhase(phase: AimiLoopPhase, blackbox: HormonitorStudyExporter?) {
        currentLoopPhase = phase
        val tickId = activeTickId
        val wall = aimiWallClockMs()
        val tickStart = activeTickStartedWallMs
        val msSinceTickStart = if (tickStart > 0L) wall - tickStart else null
        val prev = lastPhaseMarkWallMs
        val msSincePrevPhase = if (prev > 0L) wall - prev else null
        lastPhaseMarkWallMs = wall
        appendRing(
            "phase id=$tickId ${phase.name} wall_ms=$wall " +
                "ms_since_tick=${msSinceTickStart ?: -1} ms_since_prev_phase=${msSincePrevPhase ?: -1}"
        )
        if (blackbox == null || tickId <= 0L) return
        try {
            blackbox.recordLoopPhase(
                tickId = tickId,
                phaseName = phase.name,
                wallClockMs = wall,
                msSinceTickStart = msSinceTickStart,
                msSincePrevPhase = msSincePrevPhase
            )
        } catch (_: Throwable) {
            // Never break determine_basal on telemetry.
        }
    }

    fun isTickInProgress(): Boolean = activeTickId > 0L

    fun activeTickAgeMs(): Long {
        val started = activeTickStartedWallMs
        return if (started > 0L) (aimiWallClockMs() - started).coerceAtLeast(0L) else 0L
    }

    /**
     * Wraps one full AIMI determine_basal pass. Non-local returns from [block] still run `finally`.
     * On success: ring `tick_end` and onTickEnd.
     * On failure: ring `tick_abort`, onTickAbort, then [recoverFromError] (no process crash).
     * On lock timeout: [onLockTimeout] without running [block].
     */
    internal inline fun traceDetermineBasalTick(
        preferences: Preferences,
        wallClockMs: Long,
        noinline onLockTimeout: () -> RT,
        noinline recoverFromError: (Throwable) -> RT,
        noinline onTickEnd: ((tickId: Long, startedWallMs: Long, endedWallMs: Long) -> Unit)? = null,
        noinline onTickAbort: ((tickId: Long, startedWallMs: Long, endedWallMs: Long, error: Throwable) -> Unit)? = null,
        block: () -> RT
    ): RT {
        val exclusive = preferences.get(BooleanKey.OApsAIMILoopExclusiveInvocationEnabled)
        if (exclusive && !AimiLoopGate.tryAcquireExclusive()) {
            appendRing("tick_skip lock_timeout wall_ms=$wallClockMs")
            return onLockTimeout()
        }
        try {
            val id = nextTickId()
            val previousActive = activeTickId
            activeTickId = id
            activeTickStartedWallMs = wallClockMs
            lastPhaseMarkWallMs = 0L
            appendRing("tick_start id=$id wall_ms=$wallClockMs")
            var completedNormally = false
            try {
                val result = block()
                completedNormally = true
                return result
            } catch (t: Throwable) {
                val endedWallMs = aimiWallClockMs()
                val errSimple = t::class.simpleName ?: "Throwable"
                val phase = currentLoopPhase.name
                appendRing(
                    "tick_abort id=$id phase=$phase wall_ms=$endedWallMs duration_ms=${endedWallMs - wallClockMs} error=$errSimple"
                )
                try {
                    onTickAbort?.invoke(id, wallClockMs, endedWallMs, t)
                } catch (_: Throwable) {
                    // Never break the loop on telemetry.
                }
                return recoverFromError(t)
            } finally {
                if (completedNormally) {
                    val endedWallMs = aimiWallClockMs()
                    appendRing("tick_end id=$id wall_ms=$endedWallMs duration_ms=${endedWallMs - wallClockMs}")
                    try {
                        onTickEnd?.invoke(id, wallClockMs, endedWallMs)
                    } catch (_: Throwable) {
                        // Never break the loop on telemetry.
                    }
                }
                activeTickId = previousActive
                activeTickStartedWallMs = 0L
                lastPhaseMarkWallMs = 0L
            }
        } finally {
            if (exclusive) {
                AimiLoopGate.releaseExclusive()
            }
        }
    }

    internal fun ringSnapshotTail(maxLines: Int = 32): List<String> {
        val cap = maxLines.coerceIn(1, RING_MAX)
        ringLock.withLock {
            if (ring.isEmpty()) return emptyList()
            return ring.takeLast(cap)
        }
    }

    /**
     * Next tick id, counted once per call. Kept out of the inlined tick body so the atomic API, which
     * still needs an opt-in, is not inlined into every call site.
     */
    private fun nextTickId(): Long = tickSeq.incrementAndFetch()

    private fun appendRing(line: String) {
        val stamped = "${aimiWallClockMs()} $line"
        ringLock.withLock {
            ring.addLast(stamped)
            while (ring.size > RING_MAX) ring.removeFirst()
        }
    }
}
