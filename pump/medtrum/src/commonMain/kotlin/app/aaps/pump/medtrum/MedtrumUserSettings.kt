package app.aaps.pump.medtrum

import app.aaps.core.data.pump.defs.PumpType
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.collectResilient
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.keys.interfaces.TextRef
import app.aaps.pump.medtrum.comm.enums.AlarmSetting
import app.aaps.pump.medtrum.keys.MedtrumBooleanKey
import app.aaps.pump.medtrum.keys.MedtrumIntKey
import app.aaps.pump.medtrum.keys.MedtrumStringKey
import app.aaps.pump.medtrum.session.MedtrumSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlin.math.min

/**
 * The pump settings the person chose, read from [Preferences]. The patch gets them at activation
 * and with every SetPatch command.
 *
 * One mapping for Android and iPhone. Android `MedtrumPump.loadUserSettingsFromSP` reads it, and
 * so does the iPhone plugin, which copies it into the shared [MedtrumSessionState] with [applyTo].
 */
data class MedtrumUserSettings(
    val patchExpiration: Boolean,
    val alarmSetting: AlarmSetting,
    /** Units per hour. The patch suspends when more is given in one hour. */
    val hourlyMaxInsulin: Int,
    /** Units per day. The patch suspends when more is given in one day. */
    val dailyMaxInsulin: Int,
    val pumpWarning: Boolean,
    val pumpWarningExpiryThresholdHours: Int
) {

    /** Copies the settings into the state the iPhone driver sends to the patch. */
    fun applyTo(state: MedtrumSessionState) {
        state.desiredPatchExpiration = patchExpiration
        state.desiredAlarmSetting = alarmSetting
        state.desiredHourlyMaxInsulin = hourlyMaxInsulin
        state.desiredDailyMaxInsulin = dailyMaxInsulin
        state.desiredPumpWarning = pumpWarning
        state.desiredPumpWarningExpiryThresholdHours = pumpWarningExpiryThresholdHours
    }

    companion object {

        fun from(preferences: Preferences): MedtrumUserSettings = MedtrumUserSettings(
            patchExpiration = preferences.get(MedtrumBooleanKey.MedtrumPatchExpiration),
            alarmSetting = alarmSettingFromCode(preferences.get(MedtrumStringKey.MedtrumAlarmSettings)),
            hourlyMaxInsulin = preferences.get(MedtrumIntKey.MedtrumHourlyMaxInsulin),
            dailyMaxInsulin = preferences.get(MedtrumIntKey.MedtrumDailyMaxInsulin),
            pumpWarning = preferences.get(MedtrumBooleanKey.MedtrumWarningNotification),
            pumpWarningExpiryThresholdHours = preferences.get(MedtrumIntKey.MedtrumPumpExpiryWarningHours)
        )

        /**
         * The stored text is the [AlarmSetting] code. An unknown code gives light, vibrate and beep,
         * as on Android. Text that is not a number gives the same, where Android used to throw.
         */
        fun alarmSettingFromCode(code: String): AlarmSetting {
            val value = code.toByteOrNull()
            return AlarmSetting.entries.firstOrNull { it.code == value } ?: AlarmSetting.LIGHT_VIBRATE_AND_BEEP
        }
    }
}

/**
 * The limits of the settings screen that depend on the pump model. Android `MedtrumPlugin` had them
 * as private functions; they are shared now so that the iPhone screen offers the same choices.
 */
object MedtrumInsulinLimits {

    /** The highest hourly maximum the pump model takes, in units. */
    fun maxHourly(pumpType: PumpType): Int = if (pumpType == PumpType.MEDTRUM_300U) 60 else 40

    /** The highest daily maximum the pump model takes, in units. */
    fun maxDaily(pumpType: PumpType): Int = if (pumpType == PumpType.MEDTRUM_300U) 270 else 180

    /**
     * Sets the upper limit of the two insulin maximums for [pumpType] and lowers a stored value that
     * is above it. A lowered value is a changed preference, so it is sent to the patch like any other
     * change.
     */
    fun applyFor(pumpType: PumpType, preferences: Preferences) {
        MedtrumIntKey.MedtrumHourlyMaxInsulin.max = maxHourly(pumpType)
        MedtrumIntKey.MedtrumDailyMaxInsulin.max = maxDaily(pumpType)
        preferences.put(
            MedtrumIntKey.MedtrumHourlyMaxInsulin,
            min(preferences.get(MedtrumIntKey.MedtrumHourlyMaxInsulin), MedtrumIntKey.MedtrumHourlyMaxInsulin.max)
        )
        preferences.put(
            MedtrumIntKey.MedtrumDailyMaxInsulin,
            min(preferences.get(MedtrumIntKey.MedtrumDailyMaxInsulin), MedtrumIntKey.MedtrumDailyMaxInsulin.max)
        )
    }

    /** The alarm choices of [pumpType]. The Nano and the 300U offer only beep and silent. */
    fun alarmEntries(pumpType: PumpType): Map<String, TextRef> = when (pumpType) {
        PumpType.MEDTRUM_NANO, PumpType.MEDTRUM_300U -> MedtrumStringKey.MedtrumAlarmSettings.entries.filterKeys {
            it == AlarmSetting.BEEP_ONLY.code.toString() || it == AlarmSetting.NONE.code.toString()
        }

        else                                         -> MedtrumStringKey.MedtrumAlarmSettings.entries
    }
}

/**
 * Follows the pump settings and acts when one changes, like Android `MedtrumService` always did.
 *
 * - A change of the warning switch or the warning hour only reloads the settings: the phone shows
 *   these warnings, the patch does not need them.
 * - A change of the alarm, the patch expiration or an insulin maximum reloads the settings and then
 *   sends them to the patch with [sendToPatch].
 *
 * The value at the start is skipped; only later changes count. The collectors live in [scope].
 */
fun watchMedtrumUserSettings(
    preferences: Preferences,
    scope: CoroutineScope,
    aapsLogger: AAPSLogger,
    reload: () -> Unit,
    sendToPatch: suspend () -> Unit
) {
    preferences.observe(MedtrumBooleanKey.MedtrumWarningNotification).drop(1).collectResilient(scope, aapsLogger, LTag.PUMP) {
        reload()
    }
    preferences.observe(MedtrumIntKey.MedtrumPumpExpiryWarningHours).drop(1).collectResilient(scope, aapsLogger, LTag.PUMP) {
        reload()
    }
    preferences.observe(MedtrumStringKey.MedtrumAlarmSettings).drop(1).collectResilient(scope, aapsLogger, LTag.PUMP) {
        reload()
        sendToPatch()
    }
    preferences.observe(MedtrumBooleanKey.MedtrumPatchExpiration).drop(1).collectResilient(scope, aapsLogger, LTag.PUMP) {
        reload()
        sendToPatch()
    }
    preferences.observe(MedtrumIntKey.MedtrumHourlyMaxInsulin).drop(1).collectResilient(scope, aapsLogger, LTag.PUMP) {
        reload()
        sendToPatch()
    }
    preferences.observe(MedtrumIntKey.MedtrumDailyMaxInsulin).drop(1).collectResilient(scope, aapsLogger, LTag.PUMP) {
        reload()
        sendToPatch()
    }
}
