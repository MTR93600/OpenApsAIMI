package app.aaps.pump.medtrum.ios

import app.aaps.pump.medtrum.comm.enums.AlarmSetting
import app.aaps.pump.medtrum.comm.enums.MedtrumPumpState
import app.aaps.pump.medtrum.session.MedtrumSessionState
import app.aaps.pump.medtrum.util.MedtrumDeviceName
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
        defaults.setInteger(state.pumpSN, forKey = key("pumpSN"))
        defaults.setInteger(state.patchId, forKey = key("patchId"))
        defaults.setInteger(state.patchSessionToken, forKey = key("patchSessionToken"))
        defaults.setInteger(state.patchStartTime, forKey = key("patchStartTime"))
        defaults.setInteger(state.currentSequenceNumber.toLong(), forKey = key("currentSequenceNumber"))
        defaults.setInteger(state.syncedSequenceNumber.toLong(), forKey = key("syncedSequenceNumber"))
        // The patch state. Without it the driver does not know after a restart that a patch is active.
        defaults.setInteger(state.pumpState.state.toLong(), forKey = key("pumpState"))
        defaults.setInteger(state.desiredAlarmSetting.ordinal.toLong(), forKey = key("desiredAlarmSetting"))
        defaults.setInteger(state.desiredHourlyMaxInsulin.toLong(), forKey = key("desiredHourlyMaxInsulin"))
        defaults.setInteger(state.desiredDailyMaxInsulin.toLong(), forKey = key("desiredDailyMaxInsulin"))
        defaults.setObject(state.actualBasalProfile.toHex(), forKey = key("actualBasalProfile"))
        defaults.setDouble(state.reservoir, forKey = key("reservoir"))
        // The offset the pump was last given, so a restart does not hide a time zone change.
        defaults.setInteger(state.pumpTimeZoneOffset.toLong(), forKey = key("pumpTimeZoneOffset"))
        state.lastBolusTime?.let { defaults.setInteger(it, forKey = key("lastBolusTime")) }
            ?: defaults.removeObjectForKey(key("lastBolusTime"))
        state.lastBolusAmount?.let { defaults.setDouble(it, forKey = key("lastBolusAmount")) }
            ?: defaults.removeObjectForKey(key("lastBolusAmount"))
        defaults.synchronize()
    }

    /**
     * Saves the serial of the pump the person picked in the scan step.
     *
     * It is kept as hex text, which is what the Android driver keeps under
     * `MedtrumStringNonKey.SnInput`, so the two platforms store the same thing and read back the
     * same number. Use [MedtrumDeviceName.serialHex] to make the text.
     */
    fun savePumpSerial(serialHex: String) {
        defaults.setObject(serialHex, forKey = key("snInput"))
        defaults.synchronize()
    }

    /** The saved serial, or null when none was saved or the saved text is not a usable serial. */
    fun loadPumpSerial(): Long? = MedtrumDeviceName.serialFromHex(defaults.stringForKey(key("snInput")))

    /**
     * Remembers the CoreBluetooth identifier of the pump with [serial]. iOS gives each device its
     * own identifier on this phone, so a direct connect can reach the pump without a scan.
     */
    fun savePeripheralId(serial: Long, id: String) {
        defaults.setObject(id, forKey = key("peripheralId"))
        defaults.setObject(MedtrumDeviceName.serialHex(serial), forKey = key("peripheralSerial"))
        defaults.synchronize()
    }

    /** The identifier saved for [serial], or null: an identifier of another pump is never returned. */
    fun loadPeripheralId(serial: Long): String? {
        if (serial == 0L) return null
        val savedFor = MedtrumDeviceName.serialFromHex(defaults.stringForKey(key("peripheralSerial")))
        return defaults.stringForKey(key("peripheralId"))?.takeIf { savedFor == serial }
    }

    fun load(state: MedtrumSessionState) {
        // The serial can be there on its own: a pump was picked in the scan step but no patch has
        // run yet, so nothing else has been saved.
        val serial = loadPumpSerial()
        if (defaults.objectForKey(key("pumpSN")) == null) {
            if (serial != null) state.pumpSN = serial
            return // nothing else persisted yet
        }
        state.pumpSN = serial ?: defaults.integerForKey(key("pumpSN"))
        state.patchId = defaults.integerForKey(key("patchId"))
        state.patchSessionToken = defaults.integerForKey(key("patchSessionToken"))
        state.patchStartTime = defaults.integerForKey(key("patchStartTime"))
        state.currentSequenceNumber = defaults.integerForKey(key("currentSequenceNumber")).toInt()
        state.syncedSequenceNumber = defaults.integerForKey(key("syncedSequenceNumber")).toInt()
        // Only when saved: a missing key must stay NONE (not active), not become a made-up state.
        if (defaults.objectForKey(key("pumpState")) != null) {
            val saved = defaults.integerForKey(key("pumpState")).toByte()
            // An unknown byte is not trusted: stay NONE (not active).
            state.pumpState = MedtrumPumpState.entries.find { it.state == saved } ?: MedtrumPumpState.NONE
        }
        state.desiredAlarmSetting = AlarmSetting.entries.getOrNull(
            defaults.integerForKey(key("desiredAlarmSetting")).toInt()
        ) ?: AlarmSetting.LIGHT_VIBRATE_AND_BEEP
        state.desiredHourlyMaxInsulin = defaults.integerForKey(key("desiredHourlyMaxInsulin")).toInt()
        state.desiredDailyMaxInsulin = defaults.integerForKey(key("desiredDailyMaxInsulin")).toInt()
        defaults.stringForKey(key("actualBasalProfile"))?.fromHex()?.let {
            state.actualBasalProfile = it
        }
        state.reservoir = defaults.doubleForKey(key("reservoir"))
        state.pumpTimeZoneOffset = defaults.integerForKey(key("pumpTimeZoneOffset")).toInt()
        state.lastBolusTime = defaults.objectForKey(key("lastBolusTime"))?.let {
            defaults.integerForKey(key("lastBolusTime"))
        }
        state.lastBolusAmount = defaults.objectForKey(key("lastBolusAmount"))?.let {
            defaults.doubleForKey(key("lastBolusAmount"))
        }
        // Volatile state is deliberately NOT restored: connection, bolus progress.
    }

    fun clear() {
        listOf(
            "snInput", "peripheralId", "peripheralSerial", "pumpSN", "patchId", "patchSessionToken", "patchStartTime",
            "currentSequenceNumber", "syncedSequenceNumber", "pumpState", "desiredAlarmSetting",
            "desiredHourlyMaxInsulin", "desiredDailyMaxInsulin", "actualBasalProfile", "pumpTimeZoneOffset",
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
