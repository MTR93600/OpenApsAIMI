package app.aaps.database.transactions

import app.aaps.database.entities.TemporaryBasal
import app.aaps.database.entities.interfaces.end
import kotlin.math.abs

/**
 * Sync the Temporary Basal from NS
 */
class SyncNsTemporaryBasalTransaction(
    private val temporaryBasals: List<TemporaryBasal>,
    private val nsClientMode: Boolean,
    val acceptCorrections: Boolean = false
) : Transaction<SyncNsTemporaryBasalTransaction.TransactionResult>() {

    override suspend fun run(): TransactionResult {
        val result = TransactionResult()

        for (temporaryBasal in temporaryBasals) {
            if (temporaryBasal.duration != 0L) {
                // not ending event
                val current: TemporaryBasal? =
                    temporaryBasal.interfaceIDs.nightscoutId?.let {
                        database.temporaryBasalDao.findByNSId(it)
                    }

                if (current != null) {
                    // Invalidation always. A follower with the switch on also copies rate, absolute or
                    // percent, type and duration. The start time stays. A master ignores the switch.
                    // Without it, only a shorter duration is copied. srvModified is not stored on the row.
                    if (current.isValid && !temporaryBasal.isValid) {
                        current.isValid = false
                        database.temporaryBasalDao.updateExistingEntry(current)
                        result.invalidated.add(current)
                    }
                    if (nsClientMode && acceptCorrections) {
                        // A longer duration must not run over the next valid temp basal.
                        val nextStart = database.temporaryBasalDao
                            .getTemporaryBasalStartingFromTimeToTime(current.timestamp + 1, current.timestamp + temporaryBasal.duration)
                            .minOfOrNull { it.timestamp }
                        val duration = if (nextStart == null) temporaryBasal.duration
                        else minOf(temporaryBasal.duration, nextStart - current.timestamp)
                        if (current.rate != temporaryBasal.rate ||
                            current.isAbsolute != temporaryBasal.isAbsolute ||
                            current.type != temporaryBasal.type ||
                            current.duration != duration
                        ) {
                            current.rate = temporaryBasal.rate
                            current.isAbsolute = temporaryBasal.isAbsolute
                            current.type = temporaryBasal.type
                            current.duration = duration
                            database.temporaryBasalDao.updateExistingEntry(current)
                            result.updated.add(current)
                        }
                    } else if (current.duration != temporaryBasal.duration && nsClientMode && temporaryBasal.duration < current.duration) {
                        // Allow update duration to shorter only
                        current.duration = temporaryBasal.duration
                        database.temporaryBasalDao.updateExistingEntry(current)
                        result.updatedDuration.add(current)
                    }
                    continue
                }

                // not known nsId
                // Check by pumpId + pumpType + pumpSerial (primary deduplication - prevents NS duplicate _id records)
                val existingByPumpId = if (temporaryBasal.interfaceIDs.pumpId != null && temporaryBasal.interfaceIDs.pumpType != null && temporaryBasal.interfaceIDs.pumpSerial != null) {
                    database.temporaryBasalDao.findByPumpIds(temporaryBasal.interfaceIDs.pumpId!!, temporaryBasal.interfaceIDs.pumpType!!, temporaryBasal.interfaceIDs.pumpSerial!!)
                } else {
                    null
                }

                if (existingByPumpId != null) {
                    // Same pump TBR exists, just update/add the new nsId
                    if (existingByPumpId.interfaceIDs.nightscoutId == null) {
                        existingByPumpId.interfaceIDs.nightscoutId = temporaryBasal.interfaceIDs.nightscoutId
                        existingByPumpId.isValid = temporaryBasal.isValid
                        database.temporaryBasalDao.updateExistingEntry(existingByPumpId)
                        result.updatedNsId.add(existingByPumpId)
                    }
                    // If existing already has a different nsId, this is a duplicate NS record - ignore it
                    continue
                }

                // Fallback: check by active TBR at timestamp
                val running = database.temporaryBasalDao.getTemporaryBasalActiveAt(temporaryBasal.timestamp)
                if (running != null && abs(running.timestamp - temporaryBasal.timestamp) < 1000) { // allow missing milliseconds
                    // the same record, update nsId only
                    running.interfaceIDs.nightscoutId = temporaryBasal.interfaceIDs.nightscoutId
                    database.temporaryBasalDao.updateExistingEntry(running)
                    result.updatedNsId.add(running)
                } else if (running != null) {
                    // another running record. end current and insert new
                    running.end = temporaryBasal.timestamp
                    database.temporaryBasalDao.updateExistingEntry(running)
                    database.temporaryBasalDao.insertNewEntry(temporaryBasal)
                    result.ended.add(running)
                    result.inserted.add(temporaryBasal)
                } else {
                    database.temporaryBasalDao.insertNewEntry(temporaryBasal)
                    result.inserted.add(temporaryBasal)
                }
            }
        }
        return result
    }

    class TransactionResult {

        val updatedNsId = mutableListOf<TemporaryBasal>()
        val updatedDuration = mutableListOf<TemporaryBasal>()
        val updated = mutableListOf<TemporaryBasal>()
        val inserted = mutableListOf<TemporaryBasal>()
        val invalidated = mutableListOf<TemporaryBasal>()
        val ended = mutableListOf<TemporaryBasal>()
    }
}