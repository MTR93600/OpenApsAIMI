package app.aaps.plugins.aps.openAPSAIMI

import androidx.collection.LongSparseArray
import app.aaps.core.data.model.TDD
import app.aaps.core.interfaces.stats.TIR
import app.aaps.core.interfaces.stats.TddCalculator
import app.aaps.core.interfaces.stats.TirCalculator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Per-[determine_basal] invocation caches for suspend stats calls ([runBlocking]).
 *
 * **TDD 24h** — [TddCalculator.calculateDaily]`(-24, 0)` is read from several places in one
 * `determine_basal` pass. Caching avoids redundant DB work.
 *
 * **TDD 1d** — [TddCalculator.calculate]`(1, allowMissingDays = false)` mid-pipeline and again for safety.
 *
 * **TIR** — [TirCalculator.calculate]`(1, 65, 180)` after warmup block; [storeTir65180FromWarmup] fills the cache
 * from the existing warmup [runBlocking] to avoid a second identical suspend call.
 *
 * Call [beginInvocation] once at the start of each [determine_basal] pass. Thread-safe.
 *
 * **Not covered here** (still `runBlocking` in `DetermineBasalaimiSMB2`): basal history init;
 * boluses in finalize path; open-agent boluses; site changes; newest SMB bolus;
 * warmup COB + notes (single grouped block); recent bolus count; bolusesHistory;
 * [TddCalculator.calculate]`(2, false)`; HealthConnect steps/HR; other [TirCalculator] ranges
 * (daily/hour/3d).
 */
internal class DetermineBasalInvocationCaches {
    private companion object {
        private const val STALE_AGE_MS = 120_000L
    }

    private val lock = Any()
    private var invocationSeq: Long = 0L

    private var cachedTdd24hSeq: Long = -1L
    private var cachedTdd24hTotalAmount: Double? = null

    private var cachedTdd1DaySparseSeq: Long = -1L
    private var cachedTdd1DaySparse: LongSparseArray<TDD>? = null

    private var cachedTir65180Seq: Long = -1L
    private var cachedTir65180: LongSparseArray<TIR>? = null
    private val tdd24Ref = AtomicReference<Double?>(null)
    private val tdd1DayRef = AtomicReference<LongSparseArray<TDD>?>(null)
    private val tir1DayRef = AtomicReference<LongSparseArray<TIR>?>(null)
    private val tdd24TsRef = AtomicReference<Long?>(null)
    private val tdd1DayTsRef = AtomicReference<Long?>(null)
    private val tir1DayTsRef = AtomicReference<Long?>(null)
    private val tdd24InFlight = AtomicBoolean(false)
    private val tdd1DayInFlight = AtomicBoolean(false)
    private val tir1DayInFlight = AtomicBoolean(false)
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun beginInvocation() {
        synchronized(lock) {
            invocationSeq++
        }
    }

    /**
     * Call when [determine_basal] exits via uncaught exception after [beginInvocation].
     * Bumps the sequence so async stats work from a failed tick cannot be mistaken for the next pass.
     */
    fun abandonInvocationAfterUnhandledError() {
        synchronized(lock) {
            invocationSeq++
        }
    }

    fun getTdd24hTotalAmountCached(tddCalculator: TddCalculator): Double? {
        return getTdd24hTotalAmountState(tddCalculator).valueOrNull()
    }

    fun getTdd24hTotalAmountState(tddCalculator: TddCalculator): AsyncDataState<Double> {
        synchronized(lock) {
            if (cachedTdd24hSeq == invocationSeq) {
                return cachedTdd24hTotalAmount?.let { AsyncDataState.Fresh(it) }
                    ?: AsyncDataState.Missing("tdd24h_not_ready")
            }
            refreshTdd24hAsync(tddCalculator)
            val total = tdd24Ref.get()
            cachedTdd24hTotalAmount = total
            cachedTdd24hSeq = invocationSeq
            val ts = tdd24TsRef.get()
            return when {
                total == null -> AsyncDataState.Missing("tdd24h_missing")
                ts == null -> AsyncDataState.Stale(total, STALE_AGE_MS)
                else -> {
                    val age = (System.currentTimeMillis() - ts).coerceAtLeast(0L)
                    if (age <= STALE_AGE_MS) AsyncDataState.Fresh(total, age) else AsyncDataState.Stale(total, age)
                }
            }
        }
    }

    fun getTddCalculate1DaySparseCached(tddCalculator: TddCalculator): LongSparseArray<TDD>? {
        return getTddCalculate1DaySparseState(tddCalculator).valueOrNull()
    }

    fun getTddCalculate1DaySparseState(tddCalculator: TddCalculator): AsyncDataState<LongSparseArray<TDD>> {
        synchronized(lock) {
            if (cachedTdd1DaySparseSeq == invocationSeq) {
                return cachedTdd1DaySparse?.let { AsyncDataState.Fresh(it) }
                    ?: AsyncDataState.Missing("tdd_1day_sparse_not_ready")
            }
            refreshTdd1DayAsync(tddCalculator)
            val r = tdd1DayRef.get()
            cachedTdd1DaySparse = r
            cachedTdd1DaySparseSeq = invocationSeq
            val ts = tdd1DayTsRef.get()
            return when {
                r == null -> AsyncDataState.Missing("tdd_1day_sparse_missing")
                ts == null -> AsyncDataState.Stale(r, STALE_AGE_MS)
                else -> {
                    val age = (System.currentTimeMillis() - ts).coerceAtLeast(0L)
                    if (age <= STALE_AGE_MS) AsyncDataState.Fresh(r, age) else AsyncDataState.Stale(r, age)
                }
            }
        }
    }

    fun getTirCalculate1Day65180Cached(tirCalculator: TirCalculator): LongSparseArray<TIR> {
        return getTirCalculate1Day65180State(tirCalculator).valueOrNull() ?: LongSparseArray()
    }

    fun getTirCalculate1Day65180State(tirCalculator: TirCalculator): AsyncDataState<LongSparseArray<TIR>> {
        synchronized(lock) {
            if (cachedTir65180Seq == invocationSeq && cachedTir65180 != null) {
                return AsyncDataState.Fresh(cachedTir65180!!)
            }
        }
        refreshTir1DayAsync(tirCalculator)
        val r = tir1DayRef.get() ?: LongSparseArray()
        synchronized(lock) {
            cachedTir65180 = r
            cachedTir65180Seq = invocationSeq
        }
        if (r.size() == 0) return AsyncDataState.Missing("tir_1day_65180_missing")
        val ts = tir1DayTsRef.get()
        if (ts == null) return AsyncDataState.Stale(r, STALE_AGE_MS)
        val age = (System.currentTimeMillis() - ts).coerceAtLeast(0L)
        return if (age <= STALE_AGE_MS) AsyncDataState.Fresh(r, age) else AsyncDataState.Stale(r, age)
    }

    fun storeTir65180FromWarmup(result: LongSparseArray<TIR>) {
        synchronized(lock) {
            cachedTir65180 = result
            cachedTir65180Seq = invocationSeq
        }
        tir1DayRef.set(result)
        tir1DayTsRef.set(System.currentTimeMillis())
    }

    private fun refreshTdd24hAsync(tddCalculator: TddCalculator) {
        if (!tdd24InFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                tdd24Ref.set(tddCalculator.calculateDaily(-24, 0)?.totalAmount)
                tdd24TsRef.set(System.currentTimeMillis())
            } finally {
                tdd24InFlight.set(false)
            }
        }
    }

    private fun refreshTdd1DayAsync(tddCalculator: TddCalculator) {
        if (!tdd1DayInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                tdd1DayRef.set(tddCalculator.calculate(1, allowMissingDays = false))
                tdd1DayTsRef.set(System.currentTimeMillis())
            } finally {
                tdd1DayInFlight.set(false)
            }
        }
    }

    private fun refreshTir1DayAsync(tirCalculator: TirCalculator) {
        if (!tir1DayInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                tir1DayRef.set(tirCalculator.calculate(1, 65.0, 180.0))
                tir1DayTsRef.set(System.currentTimeMillis())
            } finally {
                tir1DayInFlight.set(false)
            }
        }
    }
}
