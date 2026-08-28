package app.aaps.plugins.aps.openAPSAIMI.autodrive.learning

import java.util.concurrent.locks.ReentrantLock

/**
 * Serialises every access to `autodrive_dataset.csv`.
 *
 * Three components share that file and they are not compatible:
 *
 * - [AutodriveDataLake] appends one row per Autodrive tick with a `FileWriter` in append mode.
 * - `AutodriveDataBackfiller` reads the whole file, writes a temporary copy with the outcome columns
 *   filled, then renames it over the original. It also reads the file on its own to work out which
 *   CGM window to load, and to count labelled rows for the training gate.
 * - `AutodriveNeuralTrainer` reads the whole file to build its training set.
 *
 * A row appended between the backfiller's read and its rename lands in a file that is about to be
 * replaced, so it is lost — silently, and precisely on the ticks the model most needs. A read that
 * lands there sees whichever half of the transaction happens to be on disk. They run on different
 * WorkManager threads, so this is not theoretical.
 *
 * The one access deliberately left outside is the backfiller's **database** query for CGM history:
 * it is slow, it does not touch the file, and holding the file lock across it would expose the
 * decision path to a database round-trip.
 *
 * ## Why [tryWithDataset] exists
 *
 * The backfiller's transaction is a full read-modify-rename over the whole file — on the production
 * corpus, 17 068 rows and 2 MB. Measured as pure I/O on a desktop SSD it takes about 14 ms; on a
 * phone, with `readLines` into 17 000 strings, a `split` per line and the `copyTo` fallback when
 * `renameTo` fails, it is an order of magnitude worse and unbounded from the caller's point of view.
 *
 * The data lake writes from the APS decision thread, on **every** tick. It must never wait behind
 * that transaction: a lost training row is cheap, a delayed dose is not. So it takes the lock with a
 * zero timeout and carries the row forward instead of blocking.
 *
 * A `ReentrantLock` rather than a monitor, because a zero-timeout attempt is exactly what
 * `synchronized` cannot express. It stays reentrant, so nested access still cannot deadlock.
 */
internal object AutodriveDatasetLock {

    private val lock = ReentrantLock()

    /** Runs [block] with exclusive access to the dataset file, waiting for it if necessary. */
    fun <T> withDataset(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Runs [block] only if the dataset is free **right now**, and returns `null` otherwise.
     *
     * For callers that must not block. Never waits, not even briefly.
     */
    fun <T : Any> tryWithDataset(block: () -> T): T? {
        if (!lock.tryLock()) return null
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
