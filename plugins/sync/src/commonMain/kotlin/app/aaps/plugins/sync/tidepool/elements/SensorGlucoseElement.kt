package app.aaps.plugins.sync.tidepool.elements

import app.aaps.core.data.model.GV
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.tidepool.utils.NameUuid
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put
import kotlin.math.round

class SensorGlucoseElement(bgReading: GV, dateUtil: DateUtil) :
    BaseElement(bgReading.timestamp, NameUuid.fromName("AAPS-cgm" + bgReading.timestamp), dateUtil) {

    internal var units: String = "mg/dL"

    internal var value: Int = 0

    init {
        this.type = "cbg"
        // Rounded, half-even as AAPS shows it. toInt() cut the decimals off, and a source that delivers
        // them (one that converts from mmol/L, or BG from Nightscout) was stored up to 1 mg/dL low
        value = round(bgReading.value).toInt()
    }

    override fun putFields(json: JsonObjectBuilder) {
        super.putFields(json)
        json.put("units", units)
        json.put("value", value)
    }

    companion object {

        internal fun fromBgReadings(bgReadingList: List<GV>, dateUtil: DateUtil): List<SensorGlucoseElement> {
            val results = ArrayList<SensorGlucoseElement>()
            for (bgReading in bgReadingList) {
                results.add(SensorGlucoseElement(bgReading, dateUtil))
            }
            return results
        }
    }
}