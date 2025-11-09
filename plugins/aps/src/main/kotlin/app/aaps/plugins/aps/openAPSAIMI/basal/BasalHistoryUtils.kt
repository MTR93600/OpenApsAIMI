package app.aaps.plugins.aps.openAPSAIMI.basal

import app.aaps.core.data.pump.TB
import app.aaps.core.interfaces.persistence.PersistenceLayer

object BasalHistoryUtils {

    fun getZeroBasalDurationMinutes(persistenceLayer: PersistenceLayer, lookBackHours: Int): Int {
        val now = System.currentTimeMillis()
        val fromTime = now - lookBackHours * 60 * 60 * 1000L
        val tempBasals: List<TB> = persistenceLayer
            .getTemporaryBasalsStartingFromTime(fromTime, ascending = false)
            .blockingGet()
        if (tempBasals.isEmpty()) return 0

        var lastZeroTimestamp = fromTime
        for (event in tempBasals) {
            if (event.rate > 0.05) break
            lastZeroTimestamp = event.timestamp
        }
        val zeroDuration = if (lastZeroTimestamp == fromTime) now - fromTime else now - lastZeroTimestamp
        return (zeroDuration / 60000L).toInt()
    }
}
