package app.aaps.plugins.sync.tidepool.elements

import app.aaps.core.data.model.EPS
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.plugins.sync.tidepool.utils.NameUuid
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("unused")
class ProfileElement(ps: EPS, serialNumber: String, dateUtil: DateUtil, profileUtil: ProfileUtil) :
    BaseElement(ps.timestamp, NameUuid.fromName("AAPS-profile" + ps.timestamp), dateUtil) {

    internal var activeSchedule = "Normal"

    internal var basalSchedules: BasalProfile = BasalProfile()

    internal var units: Units = Units()

    internal var bgTargets: TargetProfile = TargetProfile()

    internal var carbRatios: IcProfile = IcProfile()

    internal var insulinSensitivities: IsfProfile = IsfProfile()

    internal var deviceSerialNumber: String = serialNumber

    internal var clockDriftOffset: Long = 0

    internal var conversionOffset: Long = 0

    init {
        type = "pumpSettings"
        val profile: Profile = ProfileSealed.EPS(value = ps, activePlugin = null)
        // for (br in profile.getBasalValues())
        //     basalSchedules.Normal.add(BasalRate(br.timeAsSeconds * 1000, br.value))
        // for (target in profile.getSingleTargetsMgdl())
        //     bgTargets.Normal.add(Target(target.timeAsSeconds * 1000, target.value.toInt()))
        // for (ic in profile.getIcsValues())
        //     carbRatios.Normal.add(Ratio(ic.timeAsSeconds * 1000, ic.value.toInt()))
        // for (isf in profile.getIsfsMgdlValues())
        //     insulinSensitivities.Normal.add(Ratio(isf.timeAsSeconds * 1000, isf.value.toInt()))
        for (hour in 0..23) {
            val seconds = hour * 3600
            basalSchedules.Normal.add(BasalRate(seconds * 1000, profile.getBasalTimeFromMidnight(seconds)))
            bgTargets.Normal.add(
                Target(
                    seconds * 1000,
                    profileUtil.convertToMgdlDetect(((profile.getTargetLowMgdlTimeFromMidnight(seconds) + profile.getTargetLowMgdlTimeFromMidnight(seconds)) / 2)).toInt()
                )
            )
            carbRatios.Normal.add(Ratio(seconds * 1000, profile.getIcTimeFromMidnight(seconds).toInt()))
            insulinSensitivities.Normal.add(Ratio(seconds * 1000, profile.getIsfMgdlTimeFromMidnight(seconds).toInt()))
        }
    }

    override fun putFields(json: JsonObjectBuilder) {
        super.putFields(json)
        json.put("activeSchedule", activeSchedule)
        json.put("basalSchedules", schedule(basalSchedules.Normal.map { buildJsonObject { put("start", it.start); put("rate", it.rate) } }))
        json.put("units", buildJsonObject {
            put("carb", units.carb)
            put("bg", units.bg)
        })
        json.put("bgTargets", schedule(bgTargets.Normal.map { buildJsonObject { put("start", it.start); put("target", it.target) } }))
        json.put("carbRatios", schedule(carbRatios.Normal.map { buildJsonObject { put("start", it.start); put("amount", it.amount) } }))
        json.put("insulinSensitivities", schedule(insulinSensitivities.Normal.map { buildJsonObject { put("start", it.start); put("amount", it.amount) } }))
        json.put("deviceSerialNumber", deviceSerialNumber)
        json.put("clockDriftOffset", clockDriftOffset)
        json.put("conversionOffset", conversionOffset)
    }

    // "Normal" must be the same name as activeSchedule.
    private fun schedule(entries: List<JsonObject>): JsonObject = buildJsonObject { put("Normal", JsonArray(entries)) }

    inner class BasalProfile internal constructor(
        internal var Normal: ArrayList<BasalRate> = ArrayList() // must be the same var name as activeSchedule
    )

    inner class BasalRate internal constructor(
        internal var start: Int,
        internal var rate: Double
    )

    inner class Units internal constructor(
        internal var carb: String = "grams",
        internal var bg: String = "mg/dL"
    )

    inner class TargetProfile internal constructor(
        internal var Normal: ArrayList<Target> = ArrayList() // must be the same var name as activeSchedule
    )

    inner class Target internal constructor(
        internal var start: Int,
        internal var target: Int
    )

    inner class IcProfile internal constructor(
        internal var Normal: ArrayList<Ratio> = ArrayList() // must be the same var name as activeSchedule
    )

    inner class IsfProfile internal constructor(
        internal var Normal: ArrayList<Ratio> = ArrayList() // must be the same var name as activeSchedule
    )

    inner class Ratio internal constructor(
        internal var start: Int,
        internal var amount: Int
    )
}
