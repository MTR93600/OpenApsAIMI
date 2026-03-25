package app.aaps.core.interfaces.smoothing

import app.aaps.core.data.iob.InMemoryGlucoseValue
import app.aaps.core.interfaces.rx.events.AdaptiveSmoothingQualitySnapshot

interface Smoothing {

    /**
     * Smooth values in List
     *
     * @param data  input glucose values ([0] to be the most recent one)
     *
     * @return new List with smoothed values (smoothed values are stored in [InMemoryGlucoseValue.smoothed])
     */
    fun smooth(data: MutableList<InMemoryGlucoseValue>): MutableList<InMemoryGlucoseValue>

    /**
     * Optional: last adaptive-smoothing quality snapshot (non-null only for plugins that support it).
     * Updated synchronously during [smooth] on the worker thread.
     */
    fun lastAdaptiveSmoothingQualitySnapshot(): AdaptiveSmoothingQualitySnapshot? = null
}