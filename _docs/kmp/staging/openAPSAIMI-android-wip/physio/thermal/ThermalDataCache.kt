package app.aaps.plugins.aps.openAPSAIMI.physio.thermal

import java.util.concurrent.atomic.AtomicReference

/**
 * In-memory thermal window shared between HC fetch and loop-time wcycle enrichment.
 */
internal object ThermalDataCache {

    private val windowRef = AtomicReference(ThermalDataWindowMTR())

    fun update(window: ThermalDataWindowMTR) {
        windowRef.set(window)
    }

    fun get(): ThermalDataWindowMTR = windowRef.get()
}
