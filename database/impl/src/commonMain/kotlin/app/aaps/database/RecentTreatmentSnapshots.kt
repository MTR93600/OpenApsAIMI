package app.aaps.database

import app.aaps.database.entities.Bolus
import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.interfaces.DBEntry
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch

/**
 * In-memory copies of the recent valid temp basals and boluses, for the reads that IOB/COB, the graphs, the
 * watch and AIMI repeat many times per loop run. Each read used to be a SQL query.
 *
 * ## Same answer as SQL
 *
 * A copy holds the rows the SQL would read: valid, not historic, starting at or after [Snapshot.fromTimestamp],
 * in the SQL order. A read is answered from the copy only when the copy holds everything the SQL would look at;
 * otherwise the SQL runs, as before.
 *
 * "Active at T" needs no upper limit on the length of a temp basal. The SQL gives the active temp basal that
 * started last. When the copy finds an active one, every row older than the copy started earlier, so it cannot
 * be the one the SQL gives. When the copy finds none, an older and longer temp basal (a Medtrum pump suspend is
 * stored as 80 hours) may still run, so the SQL is asked.
 *
 * ## Kept current
 *
 * [invalidateAfter] runs after every transaction, before its change event. Every transaction writes temp basals
 * and boluses through `insertNewEntry` / `updateExistingEntry`, which record the change. The few writes outside
 * a transaction call [invalidateAll] or [invalidateBoluses] themselves. A copy is also reloaded after
 * [MAX_AGE_MS]. Each copy remembers the write count it was loaded at, and is used only while that count has not
 * changed, so a copy loaded while a write ran is never used after that write.
 *
 * The returned entities are shared between callers. `PersistenceLayerImpl` turns every one into a new model
 * object, so nothing changes them.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class RecentTreatmentSnapshots(
    private val now: () -> Long,
    private val loadTemporaryBasals: suspend (from: Long) -> List<TemporaryBasal>,
    private val loadBoluses: suspend (from: Long) -> List<Bolus>,
) {

    /** [epoch] is the write count when the copy was loaded. A later write makes the copy unusable. */
    private class Snapshot<T>(val fromTimestamp: Long, val loadedAtMs: Long, val epoch: Long, val rows: List<T>)

    @Volatile private var temporaryBasals: Snapshot<TemporaryBasal>? = null
    @Volatile private var boluses: Snapshot<Bolus>? = null
    private val temporaryBasalEpoch = AtomicLong(0)
    private val bolusEpoch = AtomicLong(0)

    fun invalidateAfter(changes: List<DBEntry>) {
        if (changes.any { it is TemporaryBasal }) invalidateTemporaryBasals()
        if (changes.any { it is Bolus }) invalidateBoluses()
    }

    fun invalidateAll() {
        invalidateTemporaryBasals()
        invalidateBoluses()
    }

    fun invalidateTemporaryBasals() {
        temporaryBasalEpoch.incrementAndFetch()
        temporaryBasals = null
    }

    fun invalidateBoluses() {
        bolusEpoch.incrementAndFetch()
        boluses = null
    }

    /** Same as `TemporaryBasalDao.getTemporaryBasalActiveAt`. [sql] runs when the copy cannot be sure. */
    suspend fun temporaryBasalActiveAt(timestamp: Long, sql: suspend () -> TemporaryBasal?): TemporaryBasal? {
        val snapshot = temporaryBasalSnapshot()
        if (timestamp < snapshot.fromTimestamp) return sql()
        // Rows are oldest first, so the last match is the one that started last.
        return snapshot.rows.lastOrNull { it.timestamp <= timestamp && it.timestamp + it.duration > timestamp } ?: sql()
    }

    /** Same as `TemporaryBasalDao.getTemporaryBasalStartingFromTimeToTime`: start in [from]..[to], oldest first. */
    suspend fun temporaryBasalsStartingBetween(from: Long, to: Long, sql: suspend () -> List<TemporaryBasal>): List<TemporaryBasal> {
        val snapshot = temporaryBasalSnapshot()
        return if (from >= snapshot.fromTimestamp) snapshot.rows.filter { it.timestamp in from..to } else sql()
    }

    /** Same as `BolusDao.getBolusesFromTime(timestamp)`: newest id first. */
    suspend fun bolusesFrom(timestamp: Long, sql: suspend () -> List<Bolus>): List<Bolus> {
        val snapshot = bolusSnapshot()
        return if (timestamp >= snapshot.fromTimestamp) snapshot.rows.filter { it.timestamp >= timestamp } else sql()
    }

    /** Same as `BolusDao.getBolusesFromTime(start, end)`: newest id first. */
    suspend fun bolusesBetween(from: Long, to: Long, sql: suspend () -> List<Bolus>): List<Bolus> {
        val snapshot = bolusSnapshot()
        return if (from >= snapshot.fromTimestamp) snapshot.rows.filter { it.timestamp in from..to } else sql()
    }

    private suspend fun temporaryBasalSnapshot(): Snapshot<TemporaryBasal> {
        fresh(temporaryBasals, temporaryBasalEpoch)?.let { return it }
        // The epoch is read before the rows: a write that commits during the load counts after it, so the copy
        // is never used again, even if it is stored after that write. This read still gets an answer the SQL
        // could have given at that moment.
        val epoch = temporaryBasalEpoch.load()
        val loadedAt = now()
        val from = loadedAt - WINDOW_MS
        return Snapshot(from, loadedAt, epoch, loadTemporaryBasals(from)).also { temporaryBasals = it }
    }

    private suspend fun bolusSnapshot(): Snapshot<Bolus> {
        fresh(boluses, bolusEpoch)?.let { return it }
        val epoch = bolusEpoch.load()
        val loadedAt = now()
        val from = loadedAt - WINDOW_MS
        return Snapshot(from, loadedAt, epoch, loadBoluses(from)).also { boluses = it }
    }

    private fun <T> fresh(snapshot: Snapshot<T>?, epoch: AtomicLong): Snapshot<T>? =
        snapshot?.takeIf { it.epoch == epoch.load() && now() - it.loadedAtMs <= MAX_AGE_MS }

    companion object {

        /** History held in memory: 3 days, more than the IOB, COB, autosens and graph reads go back. */
        const val WINDOW_MS = 3L * 24 * 60 * 60 * 1000

        /** A copy is reloaded after this at the latest, even with no write. */
        const val MAX_AGE_MS = 6L * 60 * 60 * 1000
    }
}
