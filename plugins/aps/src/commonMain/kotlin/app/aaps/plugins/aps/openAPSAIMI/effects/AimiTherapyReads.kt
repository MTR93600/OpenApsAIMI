package app.aaps.plugins.aps.openAPSAIMI.effects

import app.aaps.core.data.model.BS
import app.aaps.core.data.model.HR
import app.aaps.core.data.model.SC

/**
 * The three therapy reads the Android tick asks of `persistenceLayer`.
 *
 * Filters match the Room queries still used on Android:
 * heart rate and steps are `timestamp BETWEEN start AND end` ordered by timestamp,
 * boluses are valid rows with no `referenceId` and `timestamp >= start`, ordered by id
 * descending when [AimiTherapyReads.getBolusesFromTime] is asked for ascending.
 *
 * This is not a second database. Android `DetermineBasalAIMI2` keeps calling `persistenceLayer`.
 * iOS feeds the same lists through [MemoryAimiTherapyReads].
 */
interface AimiTherapyReads {
    fun getHeartRatesFromTimeToTime(startTime: Long, endTime: Long): List<HR>
    fun getStepsCountFromTimeToTime(startTime: Long, endTime: Long): List<SC>
    fun getBolusesFromTime(startTime: Long, ascending: Boolean): List<BS>
}

/** Same lookback as `refreshHeartRatesAsync`: `now - 200 minutes`. */
internal const val THERAPY_HEART_RATE_LOOKBACK_MS: Long = 200L * 60 * 1000

/** Same lookback as `refreshStepsAsync`: `now - 210 minutes`. */
internal const val THERAPY_STEPS_LOOKBACK_MS: Long = 210L * 60 * 1000

/**
 * In-memory stand-in for the three reads. No schema and no new driver.
 * Rows outside the Android windows stay out of the lists the tick sees.
 */
class MemoryAimiTherapyReads(
    private val heartRates: List<HR> = emptyList(),
    private val steps: List<SC> = emptyList(),
    private val boluses: List<BS> = emptyList(),
) : AimiTherapyReads {

    override fun getHeartRatesFromTimeToTime(startTime: Long, endTime: Long): List<HR> =
        heartRates.filter { it.timestamp in startTime..endTime }.sortedBy { it.timestamp }

    override fun getStepsCountFromTimeToTime(startTime: Long, endTime: Long): List<SC> =
        steps.filter { it.timestamp in startTime..endTime }.sortedBy { it.timestamp }

    override fun getBolusesFromTime(startTime: Long, ascending: Boolean): List<BS> {
        val byIdDescending = boluses
            .filter { it.isValid && it.timestamp >= startTime && it.referenceId == null }
            .sortedByDescending { it.id }
        return if (ascending) byIdDescending else byIdDescending.asReversed()
    }
}

internal data class TherapyReadCaches(
    val heartRates: List<HR>,
    val steps: List<SC>,
    val boluses: List<BS>,
) {
    companion object {
        val EMPTY = TherapyReadCaches(emptyList(), emptyList(), emptyList())
    }
}

/**
 * Loads the three caches with the Android windows.
 *
 * An [Exception] is logged and that list is empty. An [Error] propagates.
 * An empty heart-rate list does not strengthen ISF: the caller keeps the substitute baseline.
 */
internal fun readTherapyCaches(
    reads: AimiTherapyReads,
    nowMs: Long,
    bolusFromMs: Long,
    bolusAscending: Boolean,
    consoleLog: MutableList<String>,
): TherapyReadCaches {
    val heartRates = readRbtOptional(
        source = "heartRates",
        consoleLog = consoleLog,
        failureLine = { errorType, message ->
            "HR windows failed ($errorType): ${message.orEmpty()} — averages 80, baseline not real"
        },
    ) { reads.getHeartRatesFromTimeToTime(nowMs - THERAPY_HEART_RATE_LOOKBACK_MS, nowMs) }
    val steps = readRbtOptional(
        source = "steps",
        consoleLog = consoleLog,
        failureLine = { errorType, message ->
            "Steps window failed ($errorType): ${message.orEmpty()} — steps empty"
        },
    ) { reads.getStepsCountFromTimeToTime(nowMs - THERAPY_STEPS_LOOKBACK_MS, nowMs) }
    val boluses = readRbtOptional(
        source = "boluses",
        consoleLog = consoleLog,
        failureLine = { errorType, message ->
            "Bolus window failed ($errorType): ${message.orEmpty()} — boluses empty"
        },
    ) { reads.getBolusesFromTime(bolusFromMs, bolusAscending) }
    return TherapyReadCaches(
        heartRates = heartRates.valueOrNull().orEmpty(),
        steps = steps.valueOrNull().orEmpty(),
        boluses = boluses.valueOrNull().orEmpty(),
    )
}
