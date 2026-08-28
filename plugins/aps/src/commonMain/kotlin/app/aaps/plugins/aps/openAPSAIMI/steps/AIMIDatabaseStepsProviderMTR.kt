package app.aaps.plugins.aps.openAPSAIMI.steps

import app.aaps.core.data.model.SC
import app.aaps.core.interfaces.concurrent.aapsIoDispatcher
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.plugins.aps.openAPSAIMI.aimiWallClockMs
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Instant

/**
 * 🗄️ AIMI Database Steps Provider - MTR Implementation
 *
 * Wraps the existing PersistenceLayer steps data (from Garmin, Wear OS, etc.)
 * as a provider in the chain of responsibility.
 *
 * Priority: 99 (lowest - used as last resort when live sources unavailable)
 *
 * Data source: StepsCount table populated by:
 * - GarminPlugin
 * - DataHandlerMobile (Wear OS)
 * - Manual entries
 *
 * @author MTR & Lyra AI - AIMI Health Connect Integration
 */
@OptIn(ExperimentalAtomicApi::class)
@SingleIn(AppScope::class)
class AIMIDatabaseStepsProviderMTR @Inject constructor(
    private val persistenceLayer: PersistenceLayer,
    private val aapsLogger: AAPSLogger
) : AIMIStepsProviderMTR {
    private data class StepsWindowCache(
        val start: Long,
        val end: Long,
        val rows: List<SC>
    )

    private val stepsRef = AtomicReference<StepsWindowCache?>(null)
    private val stepsRefreshInFlight = AtomicBoolean(false)
    private val ioScope = CoroutineScope(SupervisorJob() + aapsIoDispatcher)

    companion object {
        private const val SOURCE_NAME = "Database"
        private const val PRIORITY = 99 // Lowest priority (last resort)
        private const val SEARCH_WINDOW_BUFFER_MS = 30 * 60 * 1000L // 30 min buffer for delays
    }

    override fun getStepsDelta(windowMinutes: Int, now: Instant): Int {
        val nowMs = now.toEpochMilliseconds()
        val windowStartMs = nowMs - (windowMinutes * 60 * 1000L)
        val searchStartMs = windowStartMs - SEARCH_WINDOW_BUFFER_MS // Add buffer for delayed data

        return try {
            val allStepsCounts = stepsCached(searchStartMs, nowMs)

            if (allStepsCounts.isEmpty()) {
                aapsLogger.debug(LTag.AIMI, "[$SOURCE_NAME] No steps data in DB for {$windowMinutes}min window")
                return 0
            }

            // Find most recent record within the window
            val validRecords = allStepsCounts.filter { it.timestamp >= windowStartMs }
            val steps = when (windowMinutes) {
                5 -> validRecords.maxByOrNull { it.timestamp }?.steps5min ?: 0
                10 -> validRecords.maxByOrNull { it.timestamp }?.steps10min ?: 0
                15 -> validRecords.maxByOrNull { it.timestamp }?.steps15min ?: 0
                30 -> validRecords.maxByOrNull { it.timestamp }?.steps30min ?: 0
                60 -> validRecords.maxByOrNull { it.timestamp }?.steps60min ?: 0
                180 -> validRecords.maxByOrNull { it.timestamp }?.steps180min ?: 0
                else -> {
                    aapsLogger.warn(LTag.AIMI, "[$SOURCE_NAME] Unsupported window: {$windowMinutes}min")
                    0
                }
            }

            val stepsInt = steps?.toInt() ?: 0

            if (stepsInt > 0) {
                aapsLogger.debug(LTag.AIMI, "[$SOURCE_NAME] Found $stepsInt steps for {$windowMinutes}min (${validRecords.size} valid records)")
            }

            stepsInt
        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "[$SOURCE_NAME] Error fetching steps from DB for {$windowMinutes}min", e)
            0
        }
    }

    override fun getLastUpdateMillis(): Long {
        return try {
            val now = aimiWallClockMs()
            val searchStart = now - 210 * 60 * 1000L // Last 3.5 hours
            val allSteps = stepsCached(searchStart, now)

            allSteps.maxOfOrNull { it.timestamp } ?: 0L
        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "[$SOURCE_NAME] Error getting last update time", e)
            0L
        }
    }

    override fun isAvailable(): Boolean {
        // Database is always available (table exists even if empty)
        return try {
            persistenceLayer != null
        } catch (e: Exception) {
            aapsLogger.error(LTag.AIMI, "[$SOURCE_NAME] Database availability check failed", e)
            false
        }
    }

    override fun sourceName(): String = SOURCE_NAME

    override fun priority(): Int = PRIORITY

    private fun stepsCached(start: Long, end: Long): List<SC> {
        refreshStepsAsync(start, end)
        val cached = stepsRef.load() ?: return emptyList()
        val sameWindow = cached.start == start && cached.end == end
        return if (sameWindow) cached.rows else emptyList()
    }

    private fun refreshStepsAsync(start: Long, end: Long) {
        if (!stepsRefreshInFlight.compareAndSet(false, true)) return
        ioScope.launch {
            try {
                stepsRef.store(
                    StepsWindowCache(
                        start = start,
                        end = end,
                        rows = persistenceLayer.getStepsCountFromTimeToTime(start, end)
                    )
                )
            } catch (_: Exception) {
                stepsRef.store(StepsWindowCache(start = start, end = end, rows = emptyList()))
            } finally {
                stepsRefreshInFlight.store(false)
            }
        }
    }
}
