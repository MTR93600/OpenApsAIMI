package app.aaps.pump.medtrum.ios

import app.aaps.pump.medtrum.comm.enums.AlarmSetting
import app.aaps.pump.medtrum.session.MedtrumSessionState
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSUserDefaults

/**
 * Persists the [MedtrumSessionState] identity/therapy fields to NSUserDefaults.
 *
 * Only long-lived state is persisted: pump/patch identity, sequence numbers,
 * alarm settings, insulin limits, basal profile, reservoir, last bolus.
 * Volatile state (connection, bolus in progress) is never restored — the
 * driver always starts disconnected with no bolus in flight, so a restart
 * can never resurrect a "ghost" bolus.
 *
 * Basal profile is stored as hex (mirrors the Android driver's byte[] handling
 * without depending on Android's Base64).
 */
@OptIn(ExperimentalForeignApi::class)
class IosMedtrumStateStore(
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults,
    private val prefix: String = "medtrum.",
) {

    fun save(state: MedtrumSessionState) {
        defaults.setLong(state.pumpSN, forKey = key("pumpSN"))
        defaults.setLong(state.patchId, forKey = key("patchId"))
        defaults.setLong(state.patchSessionToken, forKey = key("patchSessionToken"))
        defaults.setLong(state.patchStartTime, forKey = key("patchStartTime"))
        defaults.setInteger(state.currentSequenceNumber.toLong(), forKey = key("currentSequenceNumber"))
        defaults.setInteger(state.syncedSequenceNumber.toLong(), forKey = key("syncedSequenceNumber"))
        defaults.setInteger(state.desiredAlarmSetting.ordinal.toLong(), forKey = key("desiredAlarmSetting"))
        defaults.setInteger(state.desiredHourlyMaxInsulin.toLong(), forKey = key("desiredHourlyMaxInsulin"))
        defaults.setInteger(state.desiredDailyMaxInsulin.toLong(), forKey = key("desiredDailyMaxInsulin"))
        defaults.setString(state.actualBasalProfile.toHex(), forKey = key("actualBasalProfile"))
        defaults.setDouble(state.reservoir, forKey = key("reservoir"))
        state.lastBolusTime?.let { defaults.setLong(it, forKey = key("lastBolusTime")) }
            ?: defaults.removeObjectForKey(key("lastBolusTime"))
        state.lastBolusAmount?.let { defaults.setDouble(it, forKey = key("lastBolusAmount")) }
            ?: defaults.removeObjectForKey(key("lastBolusAmount"))
        defaults.synchronize()
    }

    fun load(state: MedtrumSessionState) {
        if (defaults.objectForKey(key("pumpSN")) == null) return // nothing persisted yet
        state.pumpSN = defaults.longForKey(key("pumpSN"))
        state.patchId = defaults.longForKey(key("patchId"))
        state.patchSessionToken = defaults.longForKey(key("patchSessionToken"))
        state.patchStartTime = defaults.longForKey(key("patchStartTime"))
        state.currentSequenceNumber = defaults.integerForKey(key("currentSequenceNumber")).toInt()
        state.syncedSequenceNumber = defaults.integerForKey(key("syncedSequenceNumber")).toInt()
        state.desiredAlarmSetting = AlarmSetting.entries.getOrNull(
            defaults.integerForKey(key("desiredAlarmSetting")).toInt()
        ) ?: AlarmSetting.LIGHT_VIBRATE_AND_BEEP
        state.desiredHourlyMaxInsulin = defaults.integerForKey(key("desiredHourlyMaxInsulin")).toInt()
        state.desiredDailyMaxInsulin = defaults.integerForKey(key("desiredDailyMaxInsulin")).toInt()
        defaults.stringForKey(key("actualBasalProfile"))?.fromHex()?.let {
            state.actualBasalProfile = it
        }
        state.reservoir = defaults.doubleForKey(key("reservoir"))
        state.lastBolusTime = defaults.objectForKey(key("lastBolusTime"))?.let {
            defaults.longForKey(key("lastBolusTime"))
        }
        state.lastBolusAmount = defaults.objectForKey(key("lastBolusAmount"))?.let {
            defaults.doubleForKey(key("lastBolusAmount"))
        }
        // Volatile state is deliberately NOT restored: connection, bolus progress.
    }

    fun clear() {
        listOf(
            "pumpSN", "patchId", "patchSessionToken", "patchStartTime",
            "currentSequenceNumber", "syncedSequenceNumber", "desiredAlarmSetting",
            "desiredHourlyMaxInsulin", "desiredDailyMaxInsulin", "actualBasalProfile",
            "reservoir", "lastBolusTime", "lastBolusAmount",
        ).forEach { defaults.removeObjectForKey(key(it)) }
        defaults.synchronize()
    }

    private fun key(name: String) = "$prefix$name"

    private fun ByteArray.toHex(): String =
        joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }

    private fun String.fromHex(): ByteArray? {
        if (length % 2 != 0) return null
        return try {
            ByteArray(length / 2) { i ->
                substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: NumberFormatException) {
            null
        }
    }
}
