package app.aaps.plugins.sync.tidepool.elements

import app.aaps.core.data.model.BS
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.plugins.sync.tidepool.utils.NameUuid
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.put

class BolusElement(bolus: BS, dateUtil: DateUtil) : BaseElement(bolus.timestamp, NameUuid.fromName("AAPS-bolus" + bolus.timestamp), dateUtil) {

    var subType = "normal"
    var normal: Double = 0.0
    var expectedNormal: Double = 0.0

    init {
        type = "bolus"
        normal = bolus.amount
        expectedNormal = bolus.amount
        if (bolus.type == BS.Type.SMB) {
            subType = "automated"
        }
    }

    override fun putFields(json: JsonObjectBuilder) {
        super.putFields(json)
        json.put("subType", subType)
        json.put("normal", normal)
        json.put("expectedNormal", expectedNormal)
    }
}