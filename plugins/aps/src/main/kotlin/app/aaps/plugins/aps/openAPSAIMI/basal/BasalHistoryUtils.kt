package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.data.model.TB
import kotlin.math.max

/**
 * Utilitaires robustes d'historique Basal (TBR).
 */
object BasalHistoryUtils {

    interface BasalHistoryProvider {
        fun zeroBasalDurationMinutes(lookBackHours: Int): Int
        fun lastTempIsZero(): Boolean
        fun minutesSinceLastChange(): Int
    }

    object EmptyProvider : BasalHistoryProvider {
        override fun zeroBasalDurationMinutes(lookBackHours: Int) = 0
        override fun lastTempIsZero() = false
        override fun minutesSinceLastChange() = 0
    }

    @Volatile
    private var _provider: BasalHistoryProvider = EmptyProvider

    var historyProvider: BasalHistoryProvider
        get() = _provider
        set(value) { _provider = value }

    // Méthode utilitaire sans clash
    fun installHistoryProvider(provider: BasalHistoryProvider) {
        historyProvider = provider
    }

    /**
     * Provider générique basé sur une fonction fetch :
     * - fetcher(fromMillis) doit retourner les TB triés du plus récent au plus ancien.
     */
    class FetcherProvider(
        private val fetcher: (fromMillis: Long) -> List<TB>,
        private val nowProvider: () -> Long = { System.currentTimeMillis() }
    ) : BasalHistoryProvider {

        override fun zeroBasalDurationMinutes(lookBackHours: Int): Int {
            val now = nowProvider()
            val from = now - lookBackHours * 60L * 60L * 1000L
            val events = safeFetch(from)
            if (events.isEmpty()) return 0

            var lastZeroTs: Long? = null
            for (tb in events) {
                val isZero = isZeroBasal(tb)
                if (isZero) {
                    lastZeroTs = tb.timestamp
                } else {
                    // Liste du plus récent au plus ancien : on s'arrête au premier non-zero.
                    break
                }
            }
            val ref = lastZeroTs ?: return 0
            val durMs = now - ref
            return max(0, (durMs / 60000L).toInt())
        }

        override fun lastTempIsZero(): Boolean {
            val now = nowProvider()
            val from = now - 3L * 60L * 60L * 1000L
            val events = safeFetch(from)
            if (events.isEmpty()) return false
            val current = events.first()

            // TB en cours si now ∈ [timestamp, timestamp + duration[
            val end = current.timestamp + current.duration
            val inProgress = now >= current.timestamp && now < end

            return inProgress && isZeroBasal(current)
        }

        override fun minutesSinceLastChange(): Int {
            val now = nowProvider()
            val from = now - 6L * 60L * 60L * 1000L
            val events = safeFetch(from)
            if (events.isEmpty()) return 0
            val current = events.first()

            val start = current.timestamp
            val durMs = now - start
            return max(0, (durMs / 60000L).toInt())
        }

        private fun safeFetch(from: Long): List<TB> = try {
            fetcher(from)
        } catch (_: Throwable) {
            emptyList()
        }

        private fun isZeroBasal(tb: TB): Boolean {
            return if (tb.isAbsolute) {
                tb.rate <= 0.05     // U/h
            } else {
                tb.rate <= 5.0      // %
            }
        }
    }
}