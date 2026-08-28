package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import kotlin.concurrent.Volatile

/**
 * In-memory thermal window shared between HC fetch and loop-time wcycle enrichment.
 */
internal object ThermalDataCache {

    @Volatile
    private var window = ThermalDataWindowMTR()

    fun update(window: ThermalDataWindowMTR) {
        this.window = window
    }

    fun get(): ThermalDataWindowMTR = window
}
