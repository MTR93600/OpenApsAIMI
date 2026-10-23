package app.aaps.plugins.sync.xdrip.extensions

import app.aaps.core.data.model.GV
import org.json.JSONObject

/**
 * @param sgvOverride value the app really shows for this reading (calibrated and smoothed), or `null`
 *   when there is none. Without it the receiver would show the plain sensor value while the phone shows
 *   a corrected one.
 */
fun GV.toXdripJson(sgvOverride: Double? = null): JSONObject =
    JSONObject()
        .put("device", sourceSensor.text)
        .put("mills", timestamp)
        .put("isValid", isValid)
        .put("mgdl", sgvOverride ?: value)
        .put("direction", trendArrow.text)

