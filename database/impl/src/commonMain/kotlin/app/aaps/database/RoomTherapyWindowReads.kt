package app.aaps.database

import androidx.room.useReaderConnection
import app.aaps.database.entities.Bolus
import app.aaps.database.entities.HeartRate
import app.aaps.database.entities.StepsCount
import kotlinx.coroutines.runBlocking

/**
 * The three therapy windows the tick reads, on the calling thread.
 *
 * The DAOs stay `suspend`. The caller stays a normal function. [runBlocking] is the bridge already
 * used by `UnifiedActivityProviderMTR` for these same reads. Room runs the query on the builder's
 * `Dispatchers.IO` context and this call waits for it. It does not turn the tick into a coroutine.
 */
class RoomTherapyWindowReads internal constructor(
    private val database: AppDatabase,
) : AutoCloseable {

    fun userVersion(): Int = blocking {
        database.useReaderConnection { connection ->
            connection.usePrepared("PRAGMA user_version") { statement ->
                check(statement.step())
                statement.getLong(0).toInt()
            }
        }
    }

    fun heartRates(startMillis: Long, endMillis: Long): List<HeartRate> = blocking {
        database.heartRateDao.getFromTimeToTime(startMillis, endMillis)
    }

    fun steps(startMillis: Long, endMillis: Long): List<StepsCount> = blocking {
        database.stepsCountDao.getFromTimeToTime(startMillis, endMillis)
    }

    /**
     * Same order as [AppRepository.getBolusesDataFromTime]: the DAO returns id descending, and
     * [ascending] false reverses that.
     */
    fun bolusesFromTime(timestamp: Long, ascending: Boolean): List<Bolus> = blocking {
        val descending = database.bolusDao.getBolusesFromTime(timestamp)
        if (ascending) descending else descending.asReversed()
    }

    fun insertHeartRate(row: HeartRate) {
        blocking { database.heartRateDao.insert(row) }
    }

    fun insertSteps(row: StepsCount) {
        blocking { database.stepsCountDao.insert(row) }
    }

    fun insertBolus(row: Bolus) {
        blocking { database.bolusDao.insert(row) }
    }

    override fun close() {
        database.close()
    }

    private fun <T> blocking(block: suspend () -> T): T = runBlocking { block() }
}
