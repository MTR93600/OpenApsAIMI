package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC
import app.aaps.database.RoomTherapyWindowReads
import app.aaps.database.persistence.converters.fromDb

/**
 * [AimiTherapyReads] over the three Room DAOs.
 *
 * Android `DetermineBasalAIMI2` does not use this. The iOS tick does.
 * The DAOs stay `suspend`. These methods stay ordinary functions, so the tick stays synchronous.
 */
class RoomAimiTherapyReads(
    internal val windows: RoomTherapyWindowReads,
) : AimiTherapyReads, AutoCloseable {

    override fun getHeartRatesFromTimeToTime(startTime: Long, endTime: Long): List<HR> =
        windows.heartRates(startTime, endTime).map { it.fromDb() }

    override fun getStepsCountFromTimeToTime(startTime: Long, endTime: Long): List<SC> =
        windows.steps(startTime, endTime).map { it.fromDb() }

    override fun getBolusesFromTime(startTime: Long, ascending: Boolean): List<BS> =
        windows.bolusesFromTime(startTime, ascending).map { it.fromDb() }

    override fun close() {
        windows.close()
    }
}

/** The file the iOS tick opens. A missing file is created empty at schema 35. */
const val IOS_TICK_DATABASE_FILE: String = "aaps-ios.db"

/** Opens [IOS_TICK_DATABASE_FILE] with the platform builder and the common migration list. */
expect fun openIosTickTherapyReads(): RoomAimiTherapyReads
