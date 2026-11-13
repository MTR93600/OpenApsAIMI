package app.aaps.pump.omnipod.dashctl.util

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.ICfg
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.notifications.NotificationManager
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.Profile.ProfileValue
import app.aaps.core.interfaces.profile.PureProfile
import app.aaps.core.interfaces.pump.Pump
import app.aaps.core.interfaces.pump.PumpProfile
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.HardLimits
import kotlinx.serialization.json.JsonObject

/**
 * A [PumpProfile] that has only basal values, as the Android tests mock `Profile.getBasalValues()`.
 * The Dash driver reads nothing else; every other member fails the test when it is called.
 */
internal class BasalOnlyProfile(private val basalValues: Array<ProfileValue>) : PumpProfile {

    /** Pairs of (seconds from midnight, U/h). */
    constructor(vararg values: Pair<Int, Double>) : this(values.map { ProfileValue(it.first, it.second) }.toTypedArray())

    override fun getBasalValues(): Array<ProfileValue> = basalValues

    private fun notUsed(): Nothing = error("Not used by the Dash driver")

    override fun isValid(
        from: String,
        pump: Pump,
        config: Config,
        rh: TextResolver,
        notificationManager: NotificationManager,
        hardLimits: HardLimits,
        sendNotifications: Boolean
    ): Profile.ValidityCheck = notUsed()

    override val units: GlucoseUnit get() = notUsed()
    override val iCfg: ICfg? get() = notUsed()
    override val percentage: Int get() = notUsed()
    override val timeshift: Int get() = notUsed()
    override fun isEqual(profile: Profile, ignoreName: Boolean): Boolean = notUsed()
    override fun getBasal(): Double = notUsed()
    override fun getBasal(timestamp: Long): Double = notUsed()
    override fun getIc(): Double = notUsed()
    override fun getIc(timestamp: Long): Double = notUsed()
    override fun getIsfMgdl(caller: String): Double = notUsed()
    override fun getProfileIsfMgdl(): Double = notUsed()
    override fun getTargetMgdl(): Double = notUsed()
    override fun getTargetLowMgdl(): Double = notUsed()
    override fun getTargetLowMgdl(timestamp: Long): Double = notUsed()
    override fun getTargetHighMgdl(): Double = notUsed()
    override fun getTargetHighMgdl(timestamp: Long): Double = notUsed()
    override fun getBasalTimeFromMidnight(timeAsSeconds: Int): Double = notUsed()
    override fun getIcTimeFromMidnight(timeAsSeconds: Int): Double = notUsed()
    override fun getIsfMgdlTimeFromMidnight(timeAsSeconds: Int): Double = notUsed()
    override fun getTargetLowMgdlTimeFromMidnight(timeAsSeconds: Int): Double = notUsed()
    override fun getTargetHighMgdlTimeFromMidnight(timeAsSeconds: Int): Double = notUsed()
    override fun getIcList(rh: TextResolver, dateUtil: DateUtil): String = notUsed()
    override fun getIsfList(rh: TextResolver, dateUtil: DateUtil): String = notUsed()
    override fun getBasalList(rh: TextResolver, dateUtil: DateUtil): String = notUsed()
    override fun getTargetList(rh: TextResolver, dateUtil: DateUtil): String = notUsed()
    override fun convertToNonCustomizedProfile(dateUtil: DateUtil): PureProfile = notUsed()
    override fun toPureNsJson(dateUtil: DateUtil): JsonObject = notUsed()
    override fun getMaxDailyBasal(): Double = notUsed()
    override fun baseBasalSum(): Double = notUsed()
    override fun percentageBasalSum(): Double = notUsed()
    override fun getIcsValues(): Array<ProfileValue> = notUsed()
    override fun getIsfsMgdlValues(): Array<ProfileValue> = notUsed()
    override fun getSingleTargetsMgdl(): Array<ProfileValue> = notUsed()
}
