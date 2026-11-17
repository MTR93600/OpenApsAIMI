package app.aaps.plugins.sync.tidepool.elements

import app.aaps.core.data.model.TE
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.tidepool.utils.NameUuid
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

class BloodGlucoseElement(therapyEvent: TE, dateUtil: DateUtil, profileUtil: ProfileUtil) :
    BaseElement(therapyEvent.timestamp, NameUuid.fromName("AAPS-bg" + therapyEvent.timestamp), dateUtil) {

    var subType: String = "manual"

    var units: String = "mg/dL"

    var value: Int = 0

    init {
        type = "smbg"
        subType = "manual"
        value = if (therapyEvent.glucose != null)
            profileUtil.convertToMgdl(therapyEvent.glucose!!, therapyEvent.glucoseUnit).toInt()
        else 0
    }

    override fun putFields(json: JsonObjectBuilder) {
        super.putFields(json)
        json.put("subType", subType)
        json.put("units", units)
        json.put("value", value)
    }

    companion object {

        fun fromCareportalEvents(careportalList: List<TE>, dateUtil: DateUtil, profileUtil: ProfileUtil): List<BloodGlucoseElement> {
            val results = ArrayList<BloodGlucoseElement>()
            for (bt in careportalList) {
                if (bt.type == TE.Type.NS_MBG || bt.type == TE.Type.FINGER_STICK_BG_VALUE) {
                    val bge = BloodGlucoseElement(bt, dateUtil, profileUtil)
                    if (bge.value > 0)
                        results.add(BloodGlucoseElement(bt, dateUtil, profileUtil))
                }
            }
            return results
        }
    }
}